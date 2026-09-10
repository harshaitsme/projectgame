package io.github.shootgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.World
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.ImageComponent
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

    /*This code area shows all the object we want to in game window if you want to add any object
    * add it this  code area. remember : if you change any code block plc comment it properly
    * if you want to show  any Component call `override fun show()` world.entity implanted fun
    * because we used entity component system for that... */


    override fun show() {
       log.debug { "GameScreen get shown" }

        world.entity {
            add<ImageComponent>{
                image = Image().apply {
                    setSize(4f,4f)
                }
            }
            add<AnimationComponent> {
                nextAnimation("", AnimationType.IDLE)
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
