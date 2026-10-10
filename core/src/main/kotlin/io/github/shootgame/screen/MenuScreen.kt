package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.ScreenViewport
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import com.kotcrab.vis.ui.widget.VisTextField
import io.github.shootgame.Main
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.network.NetworkConfig
import ktx.app.KtxScreen

class MenuScreen(private val game: Main) : KtxScreen {

    private val stage = Stage(ScreenViewport())
    private val audioService = AudioService()
    private val prefs = Gdx.app.getPreferences("shootgame")

    private var selectedMode = GameMode.DUO
    private lateinit var usernameField: VisTextField
    private lateinit var serverHostField: VisTextField
    private lateinit var modeDescriptionLabel: VisLabel

    private lateinit var duoButton: VisTextButton
    private lateinit var squadButton: VisTextButton
    private lateinit var offlineButton: VisTextButton

    override fun show() {
        Gdx.input.inputProcessor = stage
        stage.clear()

        val savedUsername = prefs.getString("username", "Player_${(100..999).random()}")
        val savedHost = prefs.getString("server_host", NetworkConfig.serverHost)

        val rootTable = VisTable(true)
        rootTable.setFillParent(true)
        rootTable.pad(30f)

        // --- 1. TITLE & SUBTITLE ---
        val titleLabel = VisLabel("SHOOTGAME MULTIPLAYER").apply {
            setAlignment(Align.center)
            color = Color(0.2f, 0.85f, 1f, 1f)
            setFontScale(1.8f)
        }
        val subtitleLabel = VisLabel("Rust Dedicated Server • Protobuf Wire Protocol").apply {
            setAlignment(Align.center)
            color = Color(0.7f, 0.75f, 0.8f, 0.9f)
            setFontScale(1.0f)
        }

        rootTable.add(titleLabel).padTop(10f).row()
        rootTable.add(subtitleLabel).padBottom(25f).row()

        // --- 2. LOGIN / CREDENTIALS TABLE ---
        val formTable = VisTable(true)

        val userLabel = VisLabel("PLAYER USERNAME:").apply {
            color = Color.WHITE
        }
        usernameField = VisTextField(savedUsername).apply {
            messageText = "Enter player name..."
            maxLength = 20
        }

        val hostLabel = VisLabel("SERVER IP / HOST:").apply {
            color = Color.WHITE
        }
        serverHostField = VisTextField(savedHost).apply {
            messageText = "e.g. 161.118.255.172"
            maxLength = 60
        }

        formTable.add(userLabel).left().padRight(15f).padBottom(10f)
        formTable.add(usernameField).width(320f).height(44f).padBottom(10f).row()

        formTable.add(hostLabel).left().padRight(15f).padBottom(15f)
        formTable.add(serverHostField).width(320f).height(44f).padBottom(15f).row()

        rootTable.add(formTable).padBottom(15f).row()

        // --- 3. GAME MODE SELECTOR ---
        val modeTitle = VisLabel("SELECT GAME MODE:").apply {
            color = Color(1f, 0.85f, 0.3f, 1f)
        }
        rootTable.add(modeTitle).padBottom(10f).row()

        val modeTable = VisTable(true)
        duoButton = VisTextButton("2 PLAYERS (DUO)").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    setMode(GameMode.DUO)
                }
            })
        }

        squadButton = VisTextButton("4 PLAYERS (SQUAD)").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    setMode(GameMode.SQUAD)
                }
            })
        }

        offlineButton = VisTextButton("OFFLINE / SOLO").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    setMode(GameMode.OFFLINE)
                }
            })
        }

        modeTable.add(duoButton).width(180f).height(48f).padRight(12f)
        modeTable.add(squadButton).width(180f).height(48f).padRight(12f)
        modeTable.add(offlineButton).width(180f).height(48f)
        rootTable.add(modeTable).padBottom(10f).row()

        modeDescriptionLabel = VisLabel(selectedMode.description).apply {
            setAlignment(Align.center)
            color = Color(0.6f, 0.9f, 0.7f, 1f)
        }
        rootTable.add(modeDescriptionLabel).padBottom(25f).row()

        // --- 4. ACTION BUTTONS ---
        val startButton = VisTextButton("JOIN & START GAME").apply {
            color = Color(0.3f, 1f, 0.4f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    onStartGame()
                }
            })
        }
        rootTable.add(startButton).width(280f).height(56f).padBottom(10f).row()

        val footerLabel = VisLabel("Port: TCP ${NetworkConfig.TCP_PORT} | UDP ${NetworkConfig.UDP_PORT}").apply {
            setAlignment(Align.center)
            color = Color(0.5f, 0.5f, 0.5f, 0.8f)
            setFontScale(0.85f)
        }
        rootTable.add(footerLabel)

        stage.addActor(rootTable)
        updateModeButtonHighlights()
    }

    private fun setMode(mode: GameMode) {
        selectedMode = mode
        modeDescriptionLabel.setText(mode.description)
        updateModeButtonHighlights()
    }

    private fun updateModeButtonHighlights() {
        val activeColor = Color(0.2f, 0.9f, 0.9f, 1f)
        val defaultColor = Color(0.7f, 0.7f, 0.7f, 1f)

        duoButton.color = if (selectedMode == GameMode.DUO) activeColor else defaultColor
        squadButton.color = if (selectedMode == GameMode.SQUAD) activeColor else defaultColor
        offlineButton.color = if (selectedMode == GameMode.OFFLINE) activeColor else defaultColor
    }

    private fun onStartGame() {
        val username = usernameField.text.trim().ifEmpty { "Player" }
        val host = serverHostField.text.trim().ifEmpty { NetworkConfig.serverHost }

        // Save preferences
        prefs.putString("username", username)
        prefs.putString("server_host", host)
        prefs.flush()

        // Update active network config
        NetworkConfig.serverHost = host

        val config = GameConfig(
            mode = selectedMode,
            playerName = username,
            serverHost = host
        )

        game.startGame(config)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.08f, 0.11f, 0.16f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stage.dispose()
        audioService.dispose()
    }
}
