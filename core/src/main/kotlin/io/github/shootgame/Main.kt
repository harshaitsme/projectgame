package io.github.shootgame

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import io.github.shootgame.screen.GameScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

// main start point here
class Main : KtxGame<KtxScreen>() {

    override fun create() {
        Gdx.app.logLevel = Application.LOG_INFO

        addScreen(GameScreen())
        setScreen<GameScreen>()
    }
}

