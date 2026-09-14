package io.github.shootgame.screen

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.maps.tiled.TiledMap
import com.badlogic.gdx.maps.tiled.TmxMapLoader
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.World
import io.github.shootgame.system.EntitySpawnSystem
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.event.MapChangeEvent
import io.github.shootgame.event.fire
import io.github.shootgame.system.AnimationSystem
import io.github.shootgame.system.RenderSystem
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import ktx.box2d.createWorld
import ktx.log.logger
import ktx.math.vec2


//game screen shown and dispose things |
class GameScreen : KtxScreen {

    private val spriteBatch : Batch = SpriteBatch()
    private val stage: Stage = Stage(ExtendViewport(16f,9f));
    private val textureAtlas = TextureAtlas("graphics/PlayerObject.atlas")

    private var currentMap: TiledMap? = null
    private val phWorld = createWorld(gravity = vec2()).apply {
        autoClearForces = false
    }
    private val eWorld: World = World {

        inject(stage)
        inject(textureAtlas)
        inject(phWorld)

        componentListener<ImageComponent.Companion.ImageComponentListener>()
        componentListener<PhysicComponent.Companion.PhysicComponentListener>()
            system<EntitySpawnSystem>()
            system<AnimationSystem>()
            system<RenderSystem>()
    }

  // ==================================================================================

    /*Test Animation added here to test and see if it works. */


    override fun show() {
       log.debug { "GameScreen get shown" }

        /*We laod the map and fire this trigger event and hold the map event.handler*/
        eWorld.systems.forEach { system ->
            if(system is EventListener){
                stage.addListener(system)
            }
        }
        currentMap = TmxMapLoader().load("map/map1.tmx")
        stage.fire(MapChangeEvent(currentMap!!))

    }

    // ==================================================================================
    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width,height,true)
    }

    override fun render(delta: Float) {
        eWorld.update(delta.coerceAtMost(0.25f))
    }



    override fun dispose() {
        stage.disposeSafely()
        textureAtlas.disposeSafely()
        eWorld.dispose()
        currentMap?.disposeSafely()
        phWorld.disposeSafely()
    }

    companion object{
        private val log = logger<GameScreen>()
    }
}
