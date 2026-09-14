package io.github.shootgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.github.quillraven.fleks.World
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.system.RenderSystem
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import ktx.log.logger


//game screen shown and dispose things |
class GameScreen : KtxScreen {

    private val stage: Stage = Stage(ExtendViewport(16f,9f))
    private val texture: Texture = Texture("graphics/player.png")

    private val world: World = World {

        inject(stage)

        componentListener<ImageComponent.Companion.ImageComponentListener>()
            system<RenderSystem>()
    }

    override fun show() {
       log.debug { "GameScreen get shown" }

        world.entity {
            add<ImageComponent>{
                image = Image(texture).apply {

                    setSize(8f,8f)
                }
            }
        }
    }
    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width,height,true)
    }

    override fun render(delta: Float) {
        world.update(delta)
    }



    override fun dispose() {
        stage.disposeSafely()
        texture.disposeSafely()
        world.dispose()
    }

    companion object{
        private val log = logger<GameScreen>()
    }
}
