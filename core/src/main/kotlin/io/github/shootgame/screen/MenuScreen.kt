package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
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
import io.github.shootgame.auth.AuthSessionRecord
import io.github.shootgame.auth.FirebaseAuthService
import io.github.shootgame.auth.SqliteSessionStore
import io.github.shootgame.network.NetworkConfig
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import java.net.InetSocketAddress
import java.net.Socket

enum class MenuStep {
    AUTH,
    LOBBY
}

class MenuScreen(private val game: Main) : KtxScreen {

    private val stage = Stage(ScreenViewport())
    private val audioService = AudioService()

    private var currentStep = MenuStep.AUTH
    private var selectedMode = GameMode.DUO
    private var activeSession: AuthSessionRecord? = null
    private var authBusy = false

    private var serverStatusText = "Checking server..."
    private var serverStatusColor = Color(0.9f, 0.8f, 0.3f, 1f)

    private var authFeedbackLabel: VisLabel? = null
    private var liveServerStatusLabel: VisLabel? = null
    private var joinRoomCodeField: VisTextField? = null
    private var roomHintLabel: VisLabel? = null
    private var generatedRoomCode: String = GameConfig.generateRoomCode()
    private var generatedCodeLabel: VisLabel? = null

    private val modeButtons = mutableMapOf<GameMode, VisTextButton>()
    private val generatedTextures = mutableListOf<Texture>()

    private fun createBoxDrawable(
        bgR: Float,
        bgG: Float,
        bgB: Float,
        bgA: Float,
        borderR: Float,
        borderG: Float,
        borderB: Float,
        borderA: Float
    ): TextureRegionDrawable {
        val size = 24
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixmap.setColor(bgR, bgG, bgB, bgA)
        pixmap.fill()
        pixmap.setColor(borderR, borderG, borderB, borderA)
        pixmap.drawRectangle(0, 0, size, size)
        val tex = Texture(pixmap)
        pixmap.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage

        // Load saved JWT + Session from client SQLite database
        val savedSession = SqliteSessionStore.loadActiveSession()
        if (savedSession != null && !savedSession.isExpired) {
            activeSession = savedSession
            currentStep = MenuStep.LOBBY
        } else {
            activeSession = null
            currentStep = MenuStep.AUTH
        }

        pingServer()
        rebuildStage()
    }

    private fun switchStep(step: MenuStep) {
        currentStep = step
        rebuildStage()
    }

    private fun rebuildStage() {
        stage.clear()
        authFeedbackLabel = null
        liveServerStatusLabel = null
        joinRoomCodeField = null
        roomHintLabel = null
        generatedCodeLabel = null
        modeButtons.clear()

        when (currentStep) {
            MenuStep.AUTH -> buildAuthScreen()
            MenuStep.LOBBY -> buildLobbyScreen()
        }
    }

    // =========================================================================
    // 1. CLEAN USERNAME & PASSWORD LOGIN / REGISTER SCREEN (Firebase + SQLite)
    // =========================================================================
    private fun buildAuthScreen() {
        val root = VisTable(false)
        root.setFillParent(true)
        root.center()

        val card = VisTable(false)
        card.background = createBoxDrawable(
            0.10f, 0.12f, 0.16f, 0.96f,
            0.28f, 0.65f, 0.95f, 0.85f
        )
        card.pad(28f, 36f, 28f, 36f)

        val title = VisLabel("MULTIPLAYER LOGIN").apply {
            setAlignment(Align.center)
            color = Color(0.35f, 0.88f, 1f, 1f)
            setFontScale(1.4f)
        }
        card.add(title).colspan(2).center().padBottom(6f).row()

        val subtitle = VisLabel("Sign in or create an account (Firebase Auth + SQLite JWT Session)").apply {
            setAlignment(Align.center)
            color = Color(0.72f, 0.78f, 0.86f, 1f)
            setFontScale(0.85f)
        }
        card.add(subtitle).colspan(2).center().padBottom(20f).row()

        val userLbl = VisLabel("Username:").apply {
            color = Color.WHITE
        }
        val usernameField = VisTextField("").apply {
            messageText = "Enter username..."
            maxLength = 20
        }
        card.add(userLbl).left().padRight(14f).padBottom(12f)
        card.add(usernameField).width(260f).height(38f).left().padBottom(12f).row()

        val passLbl = VisLabel("Password:").apply {
            color = Color.WHITE
        }
        val passwordField = VisTextField("").apply {
            messageText = "Enter password..."
            isPasswordMode = true
            setPasswordCharacter('*')
            maxLength = 32
        }
        card.add(passLbl).left().padRight(14f).padBottom(14f)
        card.add(passwordField).width(260f).height(38f).left().padBottom(14f).row()

        val feedbackLbl = VisLabel("Enter your username and password to continue.").apply {
            setAlignment(Align.center)
            color = Color(0.75f, 0.85f, 0.95f, 1f)
            setFontScale(0.85f)
        }
        authFeedbackLabel = feedbackLbl
        card.add(feedbackLbl).colspan(2).center().padBottom(16f).row()

        val btnRow = VisTable(false)
        val loginBtn = VisTextButton("LOGIN").apply {
            color = Color(0.28f, 0.92f, 0.55f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (authBusy) return
                    audioService.play(SoundType.CLICK)
                    performLogin(usernameField.text, passwordField.text)
                }
            })
        }

        val registerBtn = VisTextButton("REGISTER").apply {
            color = Color(0.35f, 0.82f, 1f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    if (authBusy) return
                    audioService.play(SoundType.CLICK)
                    performRegister(usernameField.text, passwordField.text)
                }
            })
        }

        btnRow.add(loginBtn).width(170f).height(44f).padRight(12f)
        btnRow.add(registerBtn).width(170f).height(44f)
        card.add(btnRow).colspan(2).center().padBottom(14f).row()

        val srvLbl = VisLabel(serverStatusText).apply {
            setAlignment(Align.center)
            color = serverStatusColor
            setFontScale(0.82f)
        }
        liveServerStatusLabel = srvLbl
        card.add(srvLbl).colspan(2).center()

        passwordField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER && !authBusy) {
                    audioService.play(SoundType.CLICK)
                    performLogin(usernameField.text, passwordField.text)
                    return true
                }
                return false
            }
        })

        root.add(card)
        stage.addActor(root)
        stage.keyboardFocus = usernameField
    }

    private fun performLogin(username: String, password: String) {
        authBusy = true
        authFeedbackLabel?.setText("Verifying credentials with Firebase...")
        authFeedbackLabel?.color = Color(1f, 0.88f, 0.35f, 1f)

        FirebaseAuthService.login(username, password) { result ->
            authBusy = false
            result.onSuccess { session ->
                activeSession = session
                switchStep(MenuStep.LOBBY)
            }.onFailure { err ->
                authFeedbackLabel?.setText(err.message ?: "Login failed.")
                authFeedbackLabel?.color = Color(1f, 0.42f, 0.42f, 1f)
            }
        }
    }

    private fun performRegister(username: String, password: String) {
        authBusy = true
        authFeedbackLabel?.setText("Creating account in Firebase & saving JWT to SQLite...")
        authFeedbackLabel?.color = Color(1f, 0.88f, 0.35f, 1f)

        FirebaseAuthService.register(username, password) { result ->
            authBusy = false
            result.onSuccess { session ->
                activeSession = session
                switchStep(MenuStep.LOBBY)
            }.onFailure { err ->
                authFeedbackLabel?.setText(err.message ?: "Registration failed.")
                authFeedbackLabel?.color = Color(1f, 0.42f, 0.42f, 1f)
            }
        }
    }

    // =========================================================================
    // 2. CLEAN MATCHMAKING & ROOM CODE LOBBY
    // =========================================================================
    private fun buildLobbyScreen() {
        val session = activeSession
        val username = session?.username ?: "Player"

        val root = VisTable(false)
        root.setFillParent(true)
        root.center()

        val card = VisTable(false)
        card.background = createBoxDrawable(
            0.10f, 0.12f, 0.16f, 0.96f,
            0.28f, 0.65f, 0.95f, 0.85f
        )
        card.pad(20f, 28f, 20f, 28f)

        // --- TOP BAR: User + SQLite JWT Session + Logout ---
        val topBar = VisTable(false)
        val userInfoStack = VisTable(false)
        val userLbl = VisLabel("Player: $username").apply {
            color = Color(0.35f, 0.95f, 0.55f, 1f)
            setFontScale(1.1f)
        }
        val jwtPreview = session?.shortJwtPreview ?: "active"
        val sqliteLbl = VisLabel("SQLite Session Active • JWT: $jwtPreview").apply {
            color = Color(0.65f, 0.78f, 0.92f, 1f)
            setFontScale(0.8f)
        }
        userInfoStack.add(userLbl).left().row()
        userInfoStack.add(sqliteLbl).left().padTop(2f)

        val logoutBtn = VisTextButton("LOGOUT").apply {
            color = Color(1f, 0.5f, 0.45f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    SqliteSessionStore.clearSession()
                    activeSession = null
                    switchStep(MenuStep.AUTH)
                }
            })
        }

        topBar.add(userInfoStack).expandX().left()
        topBar.add(logoutBtn).width(105f).height(34f).right()
        card.add(topBar).growX().padBottom(14f).row()

        // --- MODE SELECTION ---
        val modeHeader = VisLabel("Select Game Mode:").apply {
            color = Color(1f, 0.88f, 0.35f, 1f)
        }
        card.add(modeHeader).left().padBottom(8f).row()

        val modeRow = VisTable(false)
        listOf(
            GameMode.DUO to "2 Players (Duo)",
            GameMode.SQUAD to "4 Players (Squad)",
            GameMode.OFFLINE to "Solo Practice"
        ).forEachIndexed { idx, (mode, label) ->
            val btn = VisTextButton(label).apply {
                addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                        selectedMode = mode
                        refreshModeButtons()
                    }
                })
            }
            modeButtons[mode] = btn
            modeRow.add(btn).width(210f).height(44f).padRight(if (idx < 2) 12f else 0f)
        }
        card.add(modeRow).padBottom(16f).row()

        // --- GAME ROOM SECTION (CREATE OR JOIN WITH ROOM CODE) ---
        val roomSection = VisTable(false)
        roomSection.background = createBoxDrawable(
            0.07f, 0.09f, 0.13f, 0.95f,
            0.22f, 0.52f, 0.78f, 0.8f
        )
        roomSection.pad(14f, 18f, 14f, 18f)

        val roomTitle = VisLabel("Multiplayer Room Code (Duo / Squad)").apply {
            color = Color(0.4f, 0.88f, 1f, 1f)
        }
        roomSection.add(roomTitle).left().padBottom(10f).row()

        // Create Room Row
        val createRow = VisTable(false)
        val codeLbl = VisLabel("Room Code: $generatedRoomCode").apply {
            color = Color(1f, 0.88f, 0.35f, 1f)
        }
        generatedCodeLabel = codeLbl

        val copyBtn = VisTextButton("COPY").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    Gdx.app.clipboard.contents = generatedRoomCode
                    roomHintLabel?.setText("Copied Room Code $generatedRoomCode to clipboard.")
                    roomHintLabel?.color = Color(0.35f, 0.95f, 0.55f, 1f)
                }
            })
        }

        val newCodeBtn = VisTextButton("NEW CODE").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    generatedRoomCode = GameConfig.generateRoomCode()
                    generatedCodeLabel?.setText("Room Code: $generatedRoomCode")
                }
            })
        }

        val createRoomBtn = VisTextButton("CREATE ROOM").apply {
            color = Color(0.28f, 0.92f, 0.55f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    if (selectedMode == GameMode.OFFLINE) {
                        selectedMode = GameMode.DUO
                    }
                    Gdx.app.clipboard.contents = generatedRoomCode
                    launchMatch(roomCode = generatedRoomCode, createRoom = true)
                }
            })
        }

        createRow.add(codeLbl).width(175f).left().padRight(10f)
        createRow.add(copyBtn).width(80f).height(36f).padRight(8f)
        createRow.add(newCodeBtn).width(105f).height(36f).padRight(12f)
        createRow.add(createRoomBtn).width(160f).height(38f)
        roomSection.add(createRow).left().padBottom(12f).row()

        // Join Room Row
        val joinRow = VisTable(false)
        val joinLbl = VisLabel("Join by Code:").apply {
            color = Color.WHITE
        }
        val codeField = VisTextField("").apply {
            messageText = "Enter 6-char code"
            maxLength = 8
        }
        joinRoomCodeField = codeField

        val pasteBtn = VisTextButton("PASTE").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    val clip = Gdx.app.clipboard.contents?.trim()?.uppercase() ?: ""
                    val clean = clip.filter { it.isLetterOrDigit() }.take(8)
                    if (clean.isNotEmpty()) {
                        codeField.text = clean
                    }
                }
            })
        }

        val joinRoomBtn = VisTextButton("JOIN ROOM").apply {
            color = Color(0.35f, 0.85f, 1f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    val entered = codeField.text.trim().uppercase().filter { it.isLetterOrDigit() }
                    if (entered.length < 4) {
                        roomHintLabel?.setText("Please enter a valid 4-6 character Room Code.")
                        roomHintLabel?.color = Color(1f, 0.45f, 0.4f, 1f)
                        return
                    }
                    if (selectedMode == GameMode.OFFLINE) {
                        selectedMode = GameMode.DUO
                    }
                    launchMatch(roomCode = entered, createRoom = false)
                }
            })
        }

        joinRow.add(joinLbl).width(115f).left().padRight(10f)
        joinRow.add(codeField).width(155f).height(36f).padRight(8f)
        joinRow.add(pasteBtn).width(80f).height(36f).padRight(12f)
        joinRow.add(joinRoomBtn).width(160f).height(38f)
        roomSection.add(joinRow).left().padBottom(8f).row()

        val hintLbl = VisLabel("Create a room to share the code, or enter a friend's code to join.").apply {
            color = Color(0.68f, 0.78f, 0.88f, 1f)
            setFontScale(0.82f)
        }
        roomHintLabel = hintLbl
        roomSection.add(hintLbl).left()

        card.add(roomSection).growX().padBottom(16f).row()

        // --- BOTTOM BAR: Server Status + Quick Play ---
        val bottomRow = VisTable(false)
        val srvStatus = VisLabel(serverStatusText).apply {
            color = serverStatusColor
            setFontScale(0.85f)
        }
        liveServerStatusLabel = srvStatus

        val quickPlayBtn = VisTextButton("PLAY / QUICK MATCH").apply {
            color = Color(0.28f, 0.95f, 0.55f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    val typedCode = joinRoomCodeField?.text?.trim()?.uppercase()?.filter { it.isLetterOrDigit() } ?: ""
                    if (typedCode.length >= 4 && selectedMode != GameMode.OFFLINE) {
                        launchMatch(roomCode = typedCode, createRoom = false)
                    } else {
                        launchMatch(roomCode = "", createRoom = false)
                    }
                }
            })
        }

        bottomRow.add(srvStatus).expandX().left()
        bottomRow.add(quickPlayBtn).width(220f).height(46f).right()
        card.add(bottomRow).growX()

        root.add(card)
        stage.addActor(root)

        refreshModeButtons()
    }

    private fun refreshModeButtons() {
        modeButtons.forEach { (mode, btn) ->
            val baseLabel = when (mode) {
                GameMode.DUO -> "2 Players (Duo)"
                GameMode.SQUAD -> "4 Players (Squad)"
                GameMode.OFFLINE -> "Solo Practice"
            }
            if (mode == selectedMode) {
                btn.setText("✓ $baseLabel")
                btn.color = Color(0.3f, 0.92f, 1f, 1f)
            } else {
                btn.setText(baseLabel)
                btn.color = Color(0.65f, 0.7f, 0.78f, 1f)
            }
        }
    }

    private fun pingServer() {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        serverStatusText = "Checking server status..."
        serverStatusColor = Color(0.95f, 0.85f, 0.35f, 1f)
        liveServerStatusLabel?.setText(serverStatusText)
        liveServerStatusLabel?.color = serverStatusColor

        Thread {
            val start = System.currentTimeMillis()
            val reachable = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, NetworkConfig.TCP_PORT), 2200)
                }
                true
            } catch (_: Exception) {
                false
            }
            val ping = System.currentTimeMillis() - start
            Gdx.app.postRunnable {
                if (reachable) {
                    serverStatusText = "● Server Online (${ping}ms)"
                    serverStatusColor = Color(0.32f, 0.95f, 0.52f, 1f)
                } else {
                    serverStatusText = "○ Server Offline (Solo Practice Available)"
                    serverStatusColor = Color(1f, 0.55f, 0.35f, 1f)
                }
                liveServerStatusLabel?.setText(serverStatusText)
                liveServerStatusLabel?.color = serverStatusColor
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun launchMatch(roomCode: String = "", createRoom: Boolean = false) {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val session = activeSession
        val config = GameConfig(
            mode = selectedMode,
            playerName = session?.username ?: "Player",
            serverHost = host,
            roomCode = roomCode.trim().uppercase(),
            createRoom = createRoom,
            jwtToken = session?.jwtToken ?: "",
            sessionToken = session?.sessionToken ?: ""
        )
        game.startGame(config)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.06f, 0.08f, 0.11f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stage.disposeSafely()
        audioService.dispose()
        generatedTextures.forEach { it.disposeSafely() }
        generatedTextures.clear()
    }
}
