package io.github.shootgame.config

/**
 * Centralized game configuration constants.
 * Modify these values to tune gameplay without changing code logic.
 */
object GameConfig {
    // Physics & Movement
    const val UNIT_SCALE = 1 / 32f
    const val GRAVITY = -20f
    const val PLAYER_SPEED = 8f
    const val PLAYER_JUMP_IMPULSE = 15f
    const val PHYSICS_STEP_TIME = 1 / 60f

    // Combat - Ammunition
    const val INITIAL_AMMO = 60
    const val AMMO_PER_MAGAZINE = 20
    const val RELOAD_TIME = 1.2f

    // Combat - Fire Rate
    const val FIRE_RATE = 0.1f
    const val BULLET_LIFETIME = 10f

    // Combat - Grenades
    const val INITIAL_GRENADES = 3
    const val FUSE_TIME = 2f
    const val EXPLOSION_DURATION = 0.3f
    const val EXPLOSION_RADIUS = 5f
    const val EXPLOSION_INNER_RADIUS = 2f
    const val EXPLOSION_DAMAGE_INNER = 10f  // 100% damage
    const val EXPLOSION_DAMAGE_OUTER = 5f   // 50% damage

    // Health & Damage
    const val PLAYER_MAX_HEALTH = 100
    const val PLAYER_INITIAL_HEALTH = 100
    const val BULLET_DAMAGE = 10
    const val INVULNERABILITY_TIME = 1f
    const val DEATH_ANIMATION_DURATION = 3f

    // Camera
    const val CAMERA_SHAKE_INTENSITY = 0.15f
    const val CAMERA_LERP_SPEED = 0.1f

    // Animation
    const val ANIMATION_FRAME_DURATION = 1 / 8f
    const val ANIMATION_FRAME_RATE_FPS = 8

    // Audio
    const val DEFAULT_MUSIC_VOLUME = 0.5f
    const val DEFAULT_SOUND_VOLUME = 0.8f

    // Viewport
    const val VIEWPORT_WIDTH = 16f
    const val VIEWPORT_HEIGHT = 9f

    // Map
    const val MAP_PATH = "map/map1.tmx"
    const val TEXTURE_ATLAS_PATH = "graphics/PlayerObject.atlas"

    // UI
    const val MAX_DELTA_TIME = 0.25f
}
