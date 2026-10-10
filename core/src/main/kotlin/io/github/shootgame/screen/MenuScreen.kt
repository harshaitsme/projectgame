package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
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
import java.net.InetSocketAddress
import java.net.Socket

class MenuScreen(private val game: Main) : KtxScreen {

    private val stage = Stage(ScreenViewport())
    private val audioService = AudioService()
    private val prefs = Gdx.app.getPreferences("shootgame")

    private var selectedMode = GameMode.DUO
    private lateinit var usernameField: VisTextField
    private lateinit var serverHostField: VisTextField
    private lateinit var modeDescriptionLabel: VisLabel
    private lateinit var serverStatusLabel: VisLabel

    private lateinit var duoButton: VisTextButton
    private lateinit var squadButton: VisTextButton
    private lateinit var offlineButton: VisTextButton

    private var cardTexture: Texture? = null

    private fun createCardDrawable(): TextureRegionDrawable {
        if (cardTexture == null) {
            val pixmap = Pixmap(4, 4, Pixmap.Format.RGBA8888)
            pixmap.setColor(0.11f, 0.15f, 0.22f, 0.92f)
            pixmap.fill()
            cardTexture = Texture(pixmap)
            pixmap.dispose()
        }
        return TextureRegionDrawable(cardTexture!!)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        stage.clear()

        val savedUsername = prefs.getString("username", "Player_${(100..999).random()}")
        val savedHost = prefs.getString("server_host", NetworkConfig.serverHost)

        val rootTable = VisTable(true)
        rootTable.setFillParent(true)
        rootTable.pad(20f)

        val cardTable = VisTable(true)
        cardTable.background = createCardDrawable()
        cardTable.pad(28f, 36f, 28f, 36f)

        // --- 1. TITLE & SUBTITLE ---
        val titleLabel = VisLabel("SHOOTGAME ARENA").apply {
            setAlignment(Align.center)
            color = Color(0.25f, 0.9f, 1f, 1f)
            setFontScale(1.85f)
        }
        val subtitleLabel = VisLabel("Real-Time Multiplayer • Rust Authoritative Server").apply {
            setAlignment(Align.center)
            color = Color(0.7f, 0.78f, 0.88f, 0.9f)
            setFontScale(1.0f)
        }

        cardTable.add(titleLabel).padTop(4f).row()
        cardTable.add(subtitleLabel).padBottom(20f).row()

        // --- 2. LOGIN / CREDENTIALS TABLE ---
        val formTable = VisTable(true)

        val userLabel = VisLabel("USERNAME:").apply {
            color = Color(0.85f, 0.9f, 1f, 1f)
        }
        usernameField = VisTextField(savedUsername).apply {
            messageText = "Enter player name..."
            maxLength = 20
        }
        val randomNameBtn = VisTextButton("NEW").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    usernameField.text = "Player_${(100..999).random()}"
                }
            })
        }

        val hostLabel = VisLabel("SERVER IP:").apply {
            color = Color(0.85f, 0.9f, 1f, 1f)
        }
        serverHostField = VisTextField(savedHost).apply {
            messageText = "e.g. 161.118.255.172"
            maxLength = 60
        }
        val pingBtn = VisTextButton("PING").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    checkServerStatus(serverHostField.text.trim().ifEmpty { NetworkConfig.serverHost })
                }
            })
        }

        formTable.add(userLabel).left().padRight(12f).padBottom(10f)
        formTable.add(usernameField).width(260f).height(40f).padBottom(10f).padRight(8f)
        formTable.add(randomNameBtn).width(64f).height(40f).padBottom(10f).row()

        formTable.add(hostLabel).left().padRight(12f).padBottom(8f)
        formTable.add(serverHostField).width(260f).height(40f).padBottom(8f).padRight(8f)
        formTable.add(pingBtn).width(64f).height(40f).padBottom(8f).row()

        cardTable.add(formTable).padBottom(6f).row()

        serverStatusLabel = VisLabel("Checking server status...").apply {
            setAlignment(Align.center)
            color = Color(0.8f, 0.85f, 0.4f, 1f)
            setFontScale(0.9f)
        }
        cardTable.add(serverStatusLabel).padBottom(18f).row()

        // --- 3. GAME MODE SELECTOR ---
        val modeTitle = VisLabel("SELECT MATCH MODE:").apply {
            color = Color(1f, 0.85f, 0.3f, 1f)
        }
        cardTable.add(modeTitle).padBottom(8f).row()

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

        offlineButton = VisTextButton("SOLO PRACTICE").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    setMode(GameMode.OFFLINE)
                }
            })
        }

        modeTable.add(duoButton).width(175f).height(46f).padRight(10f)
        modeTable.add(squadButton).width(175f).height(46f).padRight(10f)
        modeTable.add(offlineButton).width(175f).height(46f)
        cardTable.add(modeTable).padBottom(8f).row()

        modeDescriptionLabel = VisLabel(selectedMode.description).apply {
            setAlignment(Align.center)
            color = Color(0.6f, 0.9f, 0.7f, 1f)
        }
        cardTable.add(modeDescriptionLabel).padBottom(20f).row()

        // --- 4. ACTION BUTTONS ---
        val startButton = VisTextButton("JOIN & START MATCH").apply {
            color = Color(0.3f, 1f, 0.45f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    onStartGame()
                }
            })
        }
        cardTable.add(startButton).width(300f).height(54f).padBottom(10f).row()

        val footerLabel = VisLabel("Controls: A/D Move • W Jump • SPACE Shoot • R Reload • G Frag • 1-3 Weapons • ESC Menu").apply {
            setAlignment(Align.center)
            color = Color(0.55f, 0.62f, 0.72f, 0.9f)
            setFontScale(0.85f)
        }
        cardTable.add(footerLabel)

        rootTable.add(cardTable)
        stage.addActor(rootTable)
        updateModeButtonHighlights()

        checkServerStatus(savedHost)
    }

    private fun checkServerStatus(host: String) {
        serverStatusLabel.setText("Pinging $host:${NetworkConfig.TCP_PORT}...")
        serverStatusLabel.color = Color(0.95f, 0.85f, 0.35f, 1f)
        Thread {
            val start = System.currentTimeMillis()
            val reachable = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, NetworkConfig.TCP_PORT), 2000)
                }
                true
            } catch (_: Exception) {
                false
            }
            val pingMs = System.currentTimeMillis() - start
            Gdx.app.postRunnable {
                if (reachable) {
                    serverStatusLabel.setText("● Server Online at $host:${NetworkConfig.TCP_PORT} (${pingMs}ms)")
                    serverStatusLabel.color = Color(0.35f, 0.95f, 0.5f, 1f)
                } else {
                    serverStatusLabel.setText("○ Server Unreachable at $host:${NetworkConfig.TCP_PORT} (Offline Mode Available)")
                    serverStatusLabel.color = Color(1f, 0.45f, 0.45f, 1f)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun setMode(mode: GameMode) {
        selectedMode = mode
        modeDescriptionLabel.setText(mode.description)
        updateModeButtonHighlights()
    }

    private fun updateModeButtonHighlights() {
        val activeColor = Color(0.25f, 0.95f, 1f, 1f)
        val defaultColor = Color(0.65f, 0.7f, 0.75f, 1f)

        duoButton.color = if (selectedMode == GameMode.DUO) activeColor else defaultColor
        squadButton.color = if (selectedMode == GameMode.SQUAD) activeColor else defaultColor
        offlineButton.color = if (selectedMode == GameMode.OFFLINE) activeColor else defaultColor
    }

    private fun onStartGame() {
        val username = usernameField.text.trim().ifEmpty { "Player_${(100..999).random()}" }
        val host = serverHostField.text.trim().ifEmpty { NetworkConfig.serverHost }

        prefs.putString("username", username)
        prefs.putString("server_host", host)
        prefs.flush()

        NetworkConfig.serverHost = host

        val config = GameConfig(
            mode = selectedMode,
            playerName = username,
            serverHost = host
        )

        game.startGame(config)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.06f, 0.08f, 0.13f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stage.dispose()
        audioService.dispose()
        cardTexture?.dispose()
    }
}
