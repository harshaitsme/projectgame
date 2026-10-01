package io.github.shootgame

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import io.github.shootgame.screen.GameScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

/** Application entry point for the game. */
class Main : KtxGame<KtxScreen>() {

    override fun create() {
        Gdx.app.logLevel = Application.LOG_INFO

        addScreen(GameScreen())
        setScreen<GameScreen>()
    }

    companion object {
        /** Converts the game's 32-pixel tile units into world units. */
        const val UNIT_SCALE = 1 / 32f
    }
}
