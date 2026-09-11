package io.github.shootgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.maps.tiled.TmxMapLoader
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.World
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationModel
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.event.MapChangeEvent
import io.github.shootgame.event.fire
import io.github.shootgame.system.AnimationSystem
import io.github.shootgame.system.RenderSystem
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import ktx.log.logger


//game screen shown and dispose things |
class GameScreen : KtxScreen {

    private val spriteBatch : Batch = SpriteBatch()
    private val stage: Stage = Stage(ExtendViewport(16f,9f));
    private val textureAtlas = TextureAtlas("graphics/PlayerObject.atlas")

    private val world: World = World {

        inject(stage)
        inject(textureAtlas)

        componentListener<ImageComponent.Companion.ImageComponentListener>()
            system<AnimationSystem>()
            system<RenderSystem>()
    }

  // ==================================================================================

    /*Test Animation added here to test and see if it works. */


    override fun show() {
       log.debug { "GameScreen get shown" }

        /*We laod the map and fire this trigger event and hold the map event.handler*/
        world.systems.forEach { system ->
            if(system is EventListener){
                stage.addListener(system)
            }
        }
        val tiledMap = TmxMapLoader().load("map/map1.tmx")
        stage.fire(MapChangeEvent(tiledMap))

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(3.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.RUN)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(1.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.IDLE)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(2.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.WALK)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(4.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.ATTACK)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(5.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.HURT)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(6.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.DEAD)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(7.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.RECHARGE)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(8.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.GRENADE)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(12.5f,3f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.EXPLOSION)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(3f,1f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.SHOT1)
            }
        }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                    setPosition(5f,1f)
                }
            }
            add<AnimationComponent> {
                nextAnimation(AnimationModel.PLAYER, AnimationType.SHOT2)
            }
        }


    }

    // ==================================================================================





    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width,height,true)
    }

    override fun render(delta: Float) {
        world.update(delta)
    }



    override fun dispose() {
        stage.disposeSafely()
        textureAtlas.disposeSafely()
        world.dispose()
    }

    companion object{
        private val log = logger<GameScreen>()
    }
}
