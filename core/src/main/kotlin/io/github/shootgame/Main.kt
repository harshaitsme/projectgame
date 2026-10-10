package io.github.shootgame

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.kotcrab.vis.ui.VisUI
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameScreen
import io.github.shootgame.screen.MenuScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

class Main : KtxGame<KtxScreen>() {

    override fun create() {
        Gdx.app.logLevel = Application.LOG_INFO
        if (!VisUI.isLoaded()) {
            VisUI.load()
        }

        addScreen(MenuScreen(this))
        setScreen<MenuScreen>()
    }

    fun startGame(config: GameConfig) {
        if (containsScreen<GameScreen>()) {
            removeScreen<GameScreen>()
        }
        addScreen(GameScreen(config))
        setScreen<GameScreen>()
    }

    fun showMenu() {
        if (containsScreen<GameScreen>()) {
            removeScreen<GameScreen>()
        }
        setScreen<MenuScreen>()
    }

    override fun dispose() {
        super.dispose()
        if (VisUI.isLoaded()) {
            VisUI.dispose()
        }
    }

    companion object {
        const val UNIT_SCALE = 1 / 32f
    }
}
