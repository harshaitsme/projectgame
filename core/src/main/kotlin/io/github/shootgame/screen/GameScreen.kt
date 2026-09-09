package io.github.shootgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.viewport.ExtendViewport
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import ktx.collections.defaultSetSize
import ktx.graphics.use
import ktx.log.logger

class GameScreen : KtxScreen {

    private val spriteBatch : Batch = SpriteBatch()
    //player test image implementation
    private val stage: Stage = Stage(ExtendViewport(16f,9f))
    private val texture: Texture = Texture("graphics/player.png")

    override fun show() {
       log.debug { "GameScreen get shown" }
        stage.addActor(
            Image(texture).apply {
                setPosition(1f,1f)
                setSize(6f,6f)
                setScaling(Scaling.fill)
            }
        )
    }
    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width,height,true)
    }

    override fun render(delta: Float) {
        with(stage){
            act(delta)
            draw()
        }
    }



    override fun dispose() {
        stage.disposeSafely()
        texture.disposeSafely()
    }

    companion object{
        private val log = logger<GameScreen>()
    }
}
