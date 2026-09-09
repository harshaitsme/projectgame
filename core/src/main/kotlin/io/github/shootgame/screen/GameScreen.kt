package io.github.shootgame.screen

import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.ExtendViewport
import ktx.app.KtxScreen
import ktx.graphics.use
import ktx.log.logger

class GameScreen : KtxScreen {

    private val spriteBatch : Batch = SpriteBatch()
    private val stage: Stage = Stage(ExtendViewport(16f,9f))
    private val texture: Texture = Texture("graphics/player.png")

    override fun show() {
       log.debug { "GameScreen get shown" }
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
        super.dispose()
    }

    companion object{
        private val log = logger<GameScreen>()
    }
}
