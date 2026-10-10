# shootPower

**A 2D side-scrolling action shooter built with libGDX, Kotlin and the Fleks ECS.**

[![Build shootPower](https://github.com/harshaitsme/projectgame/actions/workflows/build.yml/badge.svg)](https://github.com/harshaitsme/projectgame/actions/workflows/build.yml)
![License](https://img.shields.io/badge/license-MIT-green) — see [LICENSE](LICENSE)

| | | | | |
| --- | --- | --- | --- | --- |
| **Kotlin** 2.4.10 | **libGDX** 1.14.2 | **Fleks** 1.3-JVM | **Gradle** 9.7.1 | **Android Min SDK** 21 |

`shootPower` is a landscape, touch-and-keyboard friendly shooter for Android and desktop JVMs.
You control a lone soldier on a tiled 2D map: run, jump, shoot, reload and lob grenades while the
camera tracks your movement, explosions shake the screen and a fully data-driven entity/component
system drives everything on screen.

---

## Table of contents

- [Overview](#overview)
- [Features](#features)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Architecture](#architecture)
    - [Game loop and system order](#game-loop-and-system-order)
    - [Components](#components)
    - [Spawn pipeline](#spawn-pipeline)
    - [Damage pipeline](#damage-pipeline)
- [Controls](#controls)
- [Getting started](#getting-started)
    - [Prerequisites](#prerequisites)
    - [Run on Android](#run-on-android)
    - [Build a release APK](#build-a-release-apk)
    - [Server module](#server-module)
- [Assets and content pipeline](#assets-and-content-pipeline)
    - [Texture atlas](#texture-atlas)
    - [Tiled map conventions](#tiled-map-conventions)
    - [Audio](#audio)
- [Configuration reference](#configuration-reference)
- [Continuous integration](#continuous-integration)
- [Roadmap](#roadmap)
- [Known limitations](#known-limitations)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Overview

`shootPower` started life as a [gdx-liftoff](https://github.com/libgdx/gdx-liftoff) template and grew
into a complete, playable action game. All gameplay lives in the **`core`** module and is written in
Kotlin against three pillars:

| Pillar | Role |
| --- | --- |
| **libGDX** | Windowing, input, 2D rendering (scene2d + Tiled renderer), Box2D physics, audio, asset I/O |
| **[Fleks](https://github.com/quillraven/fleks)** | Entity–Component–System world, mappers, system ordering, component listeners |
| **[ktx](https://github.com/libktx/libktx)** | Idiomatic Kotlin extensions for libGDX (box2d, tiled, actors, math, logging, assets) |

Everything you see on screen is a scene2d `Image` actor owned by a Fleks entity. Physics is Box2D.
The Tiled map supplies both the visuals *and* the collision geometry.

## Features

- **Side-scrolling movement** — run left/right, jump, with sprite flipping driven by facing direction.
- **Shooting** — magazine-based ammo, timed reloads, fire-rate gating, animation-synchronised muzzle
  timing (the bullet spawns on the `Shot1` fire frame, not on the key press).
- **Grenades** — throwable frags with a bouncing Box2D body, a fuse timer, and an area-of-effect
  explosion that damages everything in range and triggers screen shake.
- **Box2D physics** — gravity, static level collision generated from the Tiled `physics` layer,
  sensor fixtures for projectiles, restitution for grenades.
- **Deferred damage resolution** — Box2D contact callbacks are only *queued*; damage is applied on
  the next system tick so the physics world is never mutated mid-step.
- **Health, i-frames and death** — invulnerability window after every hit, death animation, entity
  teardown after a fixed corpse delay.
- **Animation state machine** — atlas-key based (`Player/Run`), cached, with per-state play modes.
- **Camera follow + camera shake** — smoothed player tracking with a configurable shake envelope.
- **Two-layer map rendering** — background tile layers render behind actors, `fg*` layers render in
  front, both through the shared `SpriteBatch`.
- **HUD and touch controls** — health / ammo / frag readouts, SFX & BGM toggles, and a circular
  on-screen control pad that only appears on Android.
- **Audio service** — cached sound effects with pitch variation, looping background music, mute
  toggles, pause/resume lifecycle handling.

## Tech stack

| Concern | Library |
| --- | --- |
| Language | Kotlin (JVM target 1.8) |
| Framework | libGDX `1.14.2` |
| ECS | Fleks `1.3-JVM` |
| Physics | Box2D (via `gdx-box2d` + `ktx-box2d`) |
| UI / rendering | scene2d (`ktx-scene2d`), `OrthogonalTiledMapRenderer` |
| Level design | [Tiled](https://www.mapeditor.org/) `1.12` (`.tmx`) |
| Audio | libGDX `Music` / `Sound` |
| Build | Gradle `9.7.1`, AGP `8.9.3` |
| CI | GitHub Actions (build + CodeQL) |
| Target | Android `minSdk 21` / `targetSdk 36`, desktop JVM |

## Project layout

```
shootPower/
├── core/                       # All gameplay code (platform-independent)
│   └── src/main/kotlin/io/github/shootgame/
│       ├── Main.kt             # KtxGame entry point, UNIT_SCALE constant
│       ├── screen/
│       │   └── GameScreen.kt   # Wires world, stage, physics, systems
│       ├── system/             # 16 Fleks systems — see below
│       ├── component/          # 14 plain-data components
│       ├── audio/
│       │   └── AudioService.kt # Sound cache, music, toggles
│       └── event/
│           └── event.kt        # MapChangeEvent + Stage.fire helper
├── android/                    # Android launcher, manifest, resources, natives
├── server/                     # Placeholder JVM entry point (not implemented)
├── assets/                     # Runtime assets (packed into the APK)
│   ├── graphics/               # PlayerObject.atlas/.png, map.atlas/.png
│   ├── map/map1.tmx            # Tiled level
│   ├── sounds/                 # bgm.wav + 6 SFX
│   └── assets_raw/             # Source PNGs + TexturePacker project
├── assets_raw/                 # Copied into assets/assets_raw at build time
├── .github/workflows/          # build.yml, codeql.yml
└── gradle/                     # Wrapper + daemon JVM properties
```

## Architecture

### Game loop and system order

`GameScreen` builds a Fleks `World`, injects its dependencies and registers systems **in execution
order**. `GameScreen.render(delta)` calls `eWorld.update(delta.coerceAtMost(0.25f))`.

| # | System | Kind | Responsibility |
| ---: | --- | --- | --- |
| 1 | `PlayerInputSystem` | Iterating | Reads keyboard + touch flags into `Move` / `Attack` |
| 2 | `CombatSystem` | Iterating | Fire rate, ammo, reload, spawns bullets & grenade requests |
| 3 | `CollisionSystem` | Interval + `EventListener` | Builds static Box2D bodies from the map `physics` layer |
| 4 | `FragSystem` | Iterating | Counts grenade fuse, detonates, spawns explosions |
| 5 | `MoveSystem` | Iterating | Writes body velocities, jump impulse, animation state, flipping |
| 6 | `PhysicSystem` | Iterating (fixed 1/60) | Steps Box2D, syncs body position → `Image` |
| 7 | `ExplosionSystem` | Iterating | AoE damage on first tick, despawn after duration |
| 8 | `DamageSystem` | Interval + `ContactListener` | Queues contacts, applies damage next tick |
| 9 | `HealthSystem` | Iterating | i-frames, HP reduction, triggers death |
| 10 | `DeathSystem` | Iterating | Corpse timer, final entity removal |
| 11 | `UiSystem` | Interval | HUD labels, touch buttons, SFX/BGM toggles |
| 12 | `BulletSystem` | Iterating | Bullet lifetime countdown |
| 13 | `AnimationSystem` | Iterating | Swaps/caches animations, drives `Image.drawable` |
| 14 | `CameraShakeSystem` | Interval | Shake envelope; applied by `RenderSystem` |
| 15 | `RenderSystem` | Iterating + `EventListener` | Map layers, camera follow, stage act/draw, UI draw |
| 16 | `EntitySpawnSystem` | Iterating + `EventListener` | Materialises `SpawnComponent` requests; spawns map entities |

> **Why is `EntitySpawnSystem` last?** Spawns are requested during the frame by other systems and
> instantiated at the very end, so a new entity is never half-processed by systems that already ran.

### Components

| Component | Key fields | Attached to |
| --- | --- | --- |
| `ImageComponent` | `image: Image` (z-sorted by y then x) | every visible entity |
| `PhysicComponent` | `body: Body` (Box2D) | player, bullets, grenades |
| `MoveComponent` | `cos`, `sin`, `speed`, `doJump`, `jumpImpulse` | player, bullets, grenades |
| `AttackComponent` | `ammo`, `fragAmmo`, `fireRate`, `reloadTime`, flags | player |
| `AnimationComponent` | `model`, `type`, `stateTime`, `playMode` | player, explosions |
| `SpawnComponent` | `type: String`, `location` | spawn *requests* only (consumed) |
| `OwnerComponent` | `owner: Entity?` | player (self), bullets, grenades (shooter) |
| `HealthComponent` | `currentHealth`, `maxHealth`, `invulnerableTime` | player |
| `DamageComponent` | `amount` | bullets |
| `BulletComponent` | `lifeTime` | bullets |
| `FragComponent` | `fuseTime` | grenades |
| `ExplosionComponent` | `duration`, `range`, `damageApplied` | explosions |
| `DeadComponent` | `time` | dying entities |
| `PlayerComponent` | marker (identifies the player entity) | player |

**Component listeners:** `ImageComponent`, `PhysicComponent` and `PlayerComponent` each ship a
companion `ComponentListener` registered in `GameScreen`. They add/remove the scene2d actor and
destroy the Box2D body automatically whenever the component is added to or removed from an entity.

### Spawn pipeline

Two-phase spawning keeps physics and rendering deterministic:

```
CombatSystem / FragSystem / map load
        │  world.entity { add SpawnComponent(type, location) [+ Move + Owner] }
        ▼
EntitySpawnSystem.onTickEntity      (system #16)
        │  builds Image (+ Animation)      → stage listener adds the actor
        │  builds Box2D body               → Physic listener sets body.userData = entity
        │  copies Move/Owner from the request
        │  world.remove(request)
        ▼
live entity
```

Supported spawn types: `Player`, `Bullet`, `Frag`, `Explosion`.

### Damage pipeline

```
Box2D contact  →  DamageSystem.beginContact
                     │ resolve owner (self / ally / enemy), dedupe
                     ▼
                pendingDamage list
                     ▼  next onTick()
                HealthSystem.damage(target, amount)
                     │ i-frames check
                     ├─ HP > 0  → nothing (already applied)
                     └─ HP == 0 → DeadComponent + remove Move/Physic + Dead animation
                                         ▼
                                   DeathSystem (3 s) → world.remove(entity)
```

Grenades bypass Box2D for damage: `ExplosionSystem` measures centre-to-centre distance and applies
`HealthSystem.damagePercent` (10 % inner radius, 50 % outer radius), then triggers a camera shake.

## Controls

### Keyboard (desktop / Android with keyboard)

| Action | Keys |
| --- | --- |
| Move left / right | `A` `D` or `←` `→` |
| Jump | `W` or `↑` |
| Shoot (hold) | `Space` |
| Reload | `R` |
| Throw grenade | `G` |

### Touch (Android)

Circular buttons rendered on the `uiStage`, only created when `Gdx.app.type == Android`:

| Side | Buttons |
| --- | --- |
| Bottom-left | `L`, `R` movement |
| Bottom-right | `SHOOT` (large), `RELOAD`, `JUMP`, `GRENADE` |
| Top-left | Health / Ammo / Frag HUD |
| Top-right | `SFX: ON/OFF`, `BGM: ON/OFF` toggles |

## Getting started

### Prerequisites

- **JDK 17+** (CI uses Temurin 17)
- **Android SDK** with `platforms;android-36` and `build-tools;36.0.0` — path written to
  `local.properties` as `sdk.dir=...`
- No need to install Gradle: the wrapper is committed.

### Run on Android

```bash
# Build and install the debug APK on a connected device/emulator
./gradlew android:installDebug

# Or launch the app directly via adb (what `android:run` does)
./gradlew android:run
```

### Build a release APK

```bash
./gradlew clean build android:assembleRelease
# → android/build/outputs/apk/release/shootPower-v1.0-release.apk
```

Release builds are minified (`minifyEnabled true`) with the default Android ProGuard rules.

### Useful Gradle tasks

| Task | Description |
| --- | --- |
| `./gradlew build` | Compiles and tests every subproject |
| `./gradlew core:test` | Runs unit tests in `core` (none yet — task exists by default) |
| `./gradlew android:lint` | Android lint validation |
| `./gradlew server:run` | Runs the (placeholder) server entry point |
| `./gradlew generateAssetList` | Regenerates `assets/assets.txt` (auto-run before `processResources`) |

### Server module

`server/src/main/kotlin/.../ServerLauncher.kt` is intentionally a stub:

```kotlin
fun main() {
    TODO("Implement server application.")
}
```

It builds into a self-contained, executable fat JAR but has no gameplay code today. Multiplayer is
on the roadmap, not implemented.

## Assets and content pipeline

### Texture atlas

`assets/graphics/PlayerObject.atlas` (+ `.png`) is produced by
[TexturePacker](https://github.com/libgdx/libgdx/wiki/Texture-Packer) from the source frames in
`assets/assets_raw/Soldier_1_ALL/`. Animation keys are `<Model>/<Type>`:

| Model | Animation types present in the atlas |
| --- | --- |
| `Player` | `Idle`, `Walk`, `Run`, `Shot1`, `Shot2`, `Attack`, `Recharge`, `Grenade`, `Hurt`, `Dead`, `Explosion` |

`AnimationComponent.nextAnimation(model, type)` maps to the key `Player/Run` etc., and
`AnimationSystem` caches each resolved `Animation<TextureRegionDrawable>` at 8 FPS
(`DEFAULT_FRAME_DURATION = 1/8f`).

Frame sizing for spawns comes from `originalWidth/originalHeight * UNIT_SCALE`, so the atlas must
keep consistent frame dimensions.

### Tiled map conventions

`assets/map/map1.tmx` — 32 × 18 tiles at 32 px, orthogonal.

| Layer | Type | Consumed by | Meaning |
| --- | --- | --- | --- |
| `background` | tile layer | `RenderSystem` (behind actors) | Parallax/static backdrop |
| `fg*` (e.g. `fg`) | tile layer | `RenderSystem` (in front of actors) | Foreground overlay |
| `entities` | object layer | `EntitySpawnSystem` | Spawn points — each object **must** have a `Type` (class) property, e.g. `Player` |
| `physics` | tile or object layer | `CollisionSystem` | Solid geometry → one static Box2D body per filled tile / rectangle |
| `mapMusic` | map property | *(reserved)* | Per-map music track (currently empty) |

> ⚠️ The tileset inside `map1.tmx` is named `entites` (a typo) — that is a *tileset* name, not the
> object layer. The object layer that game code reads is exactly `entities`.

**Coordinate conversion:** Tiled pixel coordinates → world units via `Main.UNIT_SCALE = 1/32f`
(32 px = 1 world unit).

### Audio

`AudioService` loads eagerly at construction:

| Kind | File | Notes |
| --- | --- | --- |
| Music | `sounds/bgm.wav` | Looping, `musicVolume` default `0.5` |
| SFX | `sounds/shot.wav`, `reload.wav`, `throw.wav`, `explosion.wav`, `jump.wav`, `click.wav` | Cached; `soundVolume` default `0.8`; optional ±pitch variation per play |

Missing files are logged as errors and skipped rather than crashing.

## Configuration reference

All version pins live in [`gradle.properties`](gradle.properties):

| Property | Value | Purpose |
| --- | --- | --- |
| `kotlinVersion` | `2.4.10` | Kotlin toolchain |
| `gdxVersion` | `1.14.2` | libGDX |
| `fleksversion` | `1.3-JVM` | ECS |
| `ktxVersion` | `1.14.2-rc1` | Kotlin extensions |
| `visUiVersion` | `1.5.9` | VisUI (declared, unused) |
| `artemisOdbVersion`, `ashleyVersion`, `aiVersion` | — | Declared but not used by gameplay code |
| `projectVersion` | `1.0.0` | Maven/Git project version |
| `enableGraalNative` | `false` | Optional GraalVM native-image helper |
| `org.gradle.daemon` | `false` | Avoids orphan daemons on CI/dev machines |

Android specifics (`android/build.gradle`): namespace/applicationId `io.github.shootgame`,
`compileSdk 36`, `minSdk 21`, landscape orientation, immersive mode enabled in `AndroidLauncher`.

## Continuous integration

**`.github/workflows/build.yml`** — runs on every push and pull request:

1. Checkout → Temurin JDK 17 (Gradle cache) → Android SDK (`platform-tools`, `android-36`,
   `build-tools;36.0.0`) → Gradle setup
2. `./gradlew --no-daemon clean build android:assembleRelease`
3. Uploads `shootPower-v1.0-release.apk` as a workflow artifact

**`.github/workflows/codeql.yml`** — CodeQL Advanced security analysis.

## Contributing

1. Fork and create a feature branch (`git checkout -b feature/my-feature`).
2. Follow the existing style — [.editorconfig](.editorconfig) enforces 4-space Kotlin/Java,
   2-space Gradle, LF line endings, UTF-8.
3. Keep gameplay logic in `core`; the platform modules should stay launchers.
4. Run `./gradlew clean build android:assembleRelease` locally before opening a PR.
5. Open a pull request; CI must be green.

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE)
Copyright © 2026 harshaitsme.

## Acknowledgements

- [libGDX](https://libgdx.com/) — cross-platform game framework
- [gdx-liftoff](https://github.com/libgdx/gdx-liftoff) — project generator
- [Fleks](https://github.com/quillraven/fleks) — Kotlin ECS
- [ktx](https://github.com/libktx/libktx) — Kotlin extensions for libGDX
- [Tiled](https://www.mapeditor.org/) — level editor
- Soldier sprite sheet and tile art under `assets/assets_raw/`
