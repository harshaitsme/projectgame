package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.maps.tiled.TiledMap
import com.badlogic.gdx.maps.tiled.TmxMapLoader
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.github.quillraven.fleks.World
import io.github.shootgame.audio.AudioService
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.event.MapChangeEvent
import io.github.shootgame.event.fire
import io.github.shootgame.system.AnimationSystem
import io.github.shootgame.system.BulletSystem
import io.github.shootgame.system.CameraShakeSystem
import io.github.shootgame.system.CollisionSystem
import io.github.shootgame.system.CombatSystem
import io.github.shootgame.system.DamageSystem
import io.github.shootgame.system.DeathSystem
import io.github.shootgame.system.EntitySpawnSystem
import io.github.shootgame.system.ExplosionSystem
import io.github.shootgame.system.FragSystem
import io.github.shootgame.system.HealthSystem
import io.github.shootgame.system.MoveSystem
import io.github.shootgame.system.PhysicSystem
import io.github.shootgame.system.PlayerInputSystem
import io.github.shootgame.system.RenderSystem
import io.github.shootgame.system.UiSystem
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import ktx.box2d.createWorld
import ktx.log.logger
import ktx.math.vec2

/** Main gameplay screen responsible for ECS, rendering, physics, audio, and resource lifecycle. */
class GameScreen : KtxScreen {

    private val audioService = AudioService()
    private val spriteBatch: Batch = SpriteBatch()
    private val stage: Stage = Stage(ExtendViewport(16f, 9f), spriteBatch)
    private val uiStage: Stage = Stage(ScreenViewport(), spriteBatch)
    private val textureAtlas by lazy { TextureAtlas("graphics/PlayerObject.atlas") }

    private var currentMap: TiledMap? = null
    private val phWorld = createWorld(gravity = vec2(0f, -20f)).apply {
        autoClearForces = false
    }
    private val eWorld: World = World {
        inject(stage)
        inject("uiStage", uiStage)
        inject(textureAtlas)
        inject(phWorld)
        inject(audioService)

        componentListener<ImageComponent.Companion.ImageComponentListener>()
        componentListener<PhysicComponent.Companion.PhysicComponentListener>()
        componentListener<PlayerComponent.Companion.PlayerComponentListener>()

        system<PlayerInputSystem>()
        system<CombatSystem>()
        system<CollisionSystem>()
        system<FragSystem>()
        system<MoveSystem>()
        system<PhysicSystem>()
        system<ExplosionSystem>()
        system<DamageSystem>()
        system<HealthSystem>()
        system<DeathSystem>()
        system<UiSystem>()
        system<BulletSystem>()
        system<AnimationSystem>()
        system<CameraShakeSystem>()
        system<RenderSystem>()
        system<EntitySpawnSystem>()
    }

    override fun show() {
        log.debug { "GameScreen shown" }
        Gdx.input.inputProcessor = InputMultiplexer(uiStage, stage)
        attachSystemEventListeners()

        val map = TmxMapLoader().load("map/map1.tmx")
        currentMap = map
        stage.fire(MapChangeEvent(map))
        audioService.playMusic()
    }

    private fun attachSystemEventListeners() {
        eWorld.systems.filterIsInstance<EventListener>().forEach(stage::addListener)
    }

    override fun pause() {
        audioService.pauseMusic()
    }

    override fun resume() {
        audioService.resumeMusic()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
        uiStage.viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        eWorld.update(delta.coerceAtMost(MAX_FRAME_DELTA))
    }

    override fun dispose() {
        audioService.dispose()
        stage.disposeSafely()
        uiStage.disposeSafely()
        textureAtlas.disposeSafely()
        eWorld.dispose()
        currentMap?.disposeSafely()
        phWorld.disposeSafely()
    }

    companion object {
        private const val MAX_FRAME_DELTA = 0.25f
        private val log = logger<GameScreen>()
    }
}
