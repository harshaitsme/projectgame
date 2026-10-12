package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.StretchViewport
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
import io.github.shootgame.proto.LoginResponsePacket
import io.github.shootgame.proto.PlayerConnectedPacket
import io.github.shootgame.proto.PlayerDisconnectedPacket
import io.github.shootgame.proto.PlayerStatePacket
import ktx.app.KtxScreen
import ktx.assets.disposeSafely
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

enum class MenuStep {
    AUTH,
    LOBBY
}

data class WaitingRoomMember(
    val id: Int,
    var name: String,
    val isLocal: Boolean,
    var x: Float = 12.47f,
    var y: Float = 5.34f
)

class MenuScreen(private val game: Main) : KtxScreen {

    companion object {
        private const val VIRTUAL_W = 1024f
        private const val VIRTUAL_H = 461f
    }

    private val stage = Stage(StretchViewport(VIRTUAL_W, VIRTUAL_H))
    private val audioService = AudioService()

    private var currentStep = MenuStep.AUTH
    private var selectedMode = GameMode.DUO
    private var activeSession: AuthSessionRecord? = null
    private var authBusy = false

    private var serverPingMs = 109L
    private var serverOnline = true

    private var authFeedbackLabel: VisLabel? = null
    private var sessionSubLabel: VisLabel? = null
    private var joinRoomCodeField: VisTextField? = null
    private var roomHintLabel: VisLabel? = null
    private var generatedRoomCode: String = GameConfig.generateRoomCode()
    private var generatedCodeLabel: VisLabel? = null

    private val modeSlotTables = mutableMapOf<GameMode, Table>()
    private val modeSlotLabels = mutableMapOf<GameMode, VisLabel>()

    // Loaded textures
    private var bgFrameTexture: Texture? = null
    private var avatarTexture: Texture? = null
    private var rankChevronTexture: Texture? = null
    private var rankShieldTexture: Texture? = null
    private val generatedTextures = mutableListOf<Texture>()

    private fun loadTextureSafe(path: String): Texture? {
        return try {
            Texture(Gdx.files.internal(path)).apply {
                setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureAssetsLoaded() {
        if (bgFrameTexture == null) bgFrameTexture = loadTextureSafe("graphics/sp_menu_frame_bg.png")
        if (avatarTexture == null) avatarTexture = loadTextureSafe("graphics/sp_avatar.png")
        if (rankChevronTexture == null) rankChevronTexture = loadTextureSafe("graphics/sp_rank_chevron.png")
        if (rankShieldTexture == null) rankShieldTexture = loadTextureSafe("graphics/sp_rank_shield.png")
    }

    /**
     * Creates a chamfered sci-fi metallic panel/button drawable with optional carbon weave and glowing border.
     */
    private fun createSciFiPanelDrawable(
        w: Int,
        h: Int,
        bgR: Float,
        bgG: Float,
        bgB: Float,
        bgA: Float,
        borderR: Float,
        borderG: Float,
        borderB: Float,
        borderA: Float,
        chamfer: Int = 6,
        carbonWeave: Boolean = true,
        leftCyanBar: Boolean = false
    ): TextureRegionDrawable {
        val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val distCorner = minOf(
                    x + y,
                    (w - 1 - x) + y,
                    x + (h - 1 - y),
                    (w - 1 - x) + (h - 1 - y)
                )
                if (distCorner < chamfer) continue

                val isBorder = x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2 || distCorner <= chamfer + 1
                if (isBorder) {
                    p.setColor(borderR, borderG, borderB, borderA)
                    p.drawPixel(x, y)
                } else {
                    var r = bgR
                    var g = bgG
                    var b = bgB
                    if (carbonWeave && (x + y) % 5 == 0) {
                        r = (r + 0.025f).coerceAtMost(1f)
                        g = (g + 0.03f).coerceAtMost(1f)
                        b = (b + 0.04f).coerceAtMost(1f)
                    }
                    p.setColor(r, g, b, bgA)
                    p.drawPixel(x, y)
                }
            }
        }
        if (leftCyanBar) {
            p.setColor(0.15f, 0.92f, 1f, 0.95f)
            p.fillRectangle(1, h / 4, 3, h / 2)
        }
        val tex = Texture(p).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        p.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    /**
     * Creates the signature hexagonal red-to-green neon bordered "PLAY / QUICK MATCH" button drawable.
     */
    private fun createPlayQuickMatchDrawable(w: Int = 216, h: Int = 64): TextureRegionDrawable {
        val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
        val chamfer = 14
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = minOf(
                    x + y,
                    (w - 1 - x) + y,
                    x + (h - 1 - y),
                    (w - 1 - x) + (h - 1 - y)
                )
                if (d < chamfer) continue

                // Outer metallic gunmetal frame
                if (x < 4 || x >= w - 4 || y < 4 || y >= h - 4 || d < chamfer + 4) {
                    p.setColor(0.22f, 0.28f, 0.36f, 0.98f)
                    p.drawPixel(x, y)
                } else if (x < 7 || x >= w - 7 || y < 7 || y >= h - 7 || d < chamfer + 7) {
                    // Glowing neon inner ring: red on left/right edges blending into bright green at center
                    val centerRatio = 1f - kotlin.math.abs((x - w / 2f) / (w / 2f))
                    val nr = (0.95f * (1f - centerRatio * 0.75f)).coerceIn(0.2f, 1f)
                    val ng = (0.25f + centerRatio * 0.72f).coerceIn(0.2f, 1f)
                    val nb = (0.32f + centerRatio * 0.25f).coerceIn(0.2f, 1f)
                    p.setColor(nr, ng, nb, 1f)
                    p.drawPixel(x, y)
                } else {
                    // Dark green/gunmetal illuminated interior
                    val centerGlow = (1f - kotlin.math.abs((x - w / 2f) / (w / 2f))) *
                        (1f - kotlin.math.abs((y - h / 2f) / (h / 2f)))
                    val ir = 0.08f + centerGlow * 0.06f
                    val ig = 0.14f + centerGlow * 0.22f
                    val ib = 0.16f + centerGlow * 0.12f
                    p.setColor(ir, ig, ib, 0.96f)
                    p.drawPixel(x, y)
                }
            }
        }
        val tex = Texture(p).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        p.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    private fun createProgressBarDrawable(
        w: Int,
        h: Int,
        fillRatio: Float
    ): TextureRegionDrawable {
        val p = Pixmap(w, h, Pixmap.Format.RGBA8888)
        p.setColor(0.06f, 0.09f, 0.14f, 0.95f)
        p.fill()
        p.setColor(0.22f, 0.34f, 0.48f, 0.9f)
        p.drawRectangle(0, 0, w, h)

        val fillW = ((w - 4) * fillRatio.coerceIn(0f, 1f)).toInt()
        for (x in 2 until 2 + fillW) {
            val t = x.toFloat() / w.toFloat()
            val r = 0.12f + t * 0.18f
            val g = 0.72f + t * 0.24f
            val b = 0.95f - t * 0.35f
            p.setColor(r, g, b, 1f)
            p.drawLine(x, 2, x, h - 3)
        }
        val tex = Texture(p)
        p.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        ensureAssetsLoaded()

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
        sessionSubLabel = null
        joinRoomCodeField = null
        roomHintLabel = null
        generatedCodeLabel = null
        modeSlotTables.clear()
        modeSlotLabels.clear()

        // 1. Add full-bleed SHOOT POWER 3D metallic frame & sci-fi corridor background
        val bgTex = bgFrameTexture
        if (bgTex != null) {
            val bgImage = Image(TextureRegionDrawable(bgTex)).apply {
                setFillParent(true)
                setScaling(Scaling.stretch)
            }
            stage.addActor(bgImage)
        }

        // Top-right gear hotspots on the metallic frame (toggle audio)
        val gearHotspot = Actor().apply {
            setBounds(724f, 461f - 82f, 66f, 34f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.toggleSound()
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                }
            })
        }
        stage.addActor(gearHotspot)

        when (currentStep) {
            MenuStep.AUTH -> buildAuthInterior()
            MenuStep.LOBBY -> buildLobbyInterior()
        }
    }

    // =========================================================================
    // 1. USERNAME & PASSWORD LOGIN / REGISTER SCREEN (Inside SHOOT POWER Frame)
    // =========================================================================
    private fun buildAuthInterior() {
        val interior = VisTable(false)
        // Exact bounds of the dark sci-fi screen interior (x=256..768, y=62..362)
        interior.setBounds(258f, 64f, 508f, 296f)
        interior.center()

        val authPanel = VisTable(false)
        authPanel.background = createSciFiPanelDrawable(
            440, 250,
            0.07f, 0.11f, 0.17f, 0.94f,
            0.22f, 0.78f, 0.98f, 0.9f,
            chamfer = 10,
            leftCyanBar = true
        )
        authPanel.pad(18f, 26f, 18f, 26f)

        val header = VisLabel("OPERATIVE ACCOUNT LOGIN").apply {
            color = Color(0.35f, 0.92f, 1f, 1f)
            setFontScale(1.05f)
        }
        authPanel.add(header).colspan(2).center().padBottom(4f).row()

        val sub = VisLabel("Firebase Realtime DB Auth  •  SQLite JWT Session Storage").apply {
            color = Color(0.68f, 0.78f, 0.9f, 1f)
            setFontScale(0.72f)
        }
        authPanel.add(sub).colspan(2).center().padBottom(14f).row()

        val userLbl = VisLabel("Username:").apply {
            color = Color(0.9f, 0.95f, 1f, 1f)
            setFontScale(0.82f)
        }
        val usernameField = VisTextField("").apply {
            messageText = "Enter username..."
            maxLength = 20
        }
        authPanel.add(userLbl).left().padRight(12f).padBottom(10f)
        authPanel.add(usernameField).width(240f).height(32f).left().padBottom(10f).row()

        val passLbl = VisLabel("Password:").apply {
            color = Color(0.9f, 0.95f, 1f, 1f)
            setFontScale(0.82f)
        }
        val passwordField = VisTextField("").apply {
            messageText = "Enter password..."
            isPasswordMode = true
            setPasswordCharacter('*')
            maxLength = 32
        }
        authPanel.add(passLbl).left().padRight(12f).padBottom(12f)
        authPanel.add(passwordField).width(240f).height(32f).left().padBottom(12f).row()

        val feedback = VisLabel("Enter your username & password to Login or Register.").apply {
            setAlignment(Align.center)
            color = Color(0.75f, 0.88f, 1f, 1f)
            setFontScale(0.72f)
        }
        authFeedbackLabel = feedback
        authPanel.add(feedback).colspan(2).center().padBottom(12f).row()

        val btnRow = VisTable(false)

        val loginBtnTable = createSciFiActionButton(
            text = "LOGIN",
            w = 156,
            h = 38,
            bgR = 0.12f, bgG = 0.28f, bgB = 0.38f,
            borderR = 0.25f, borderG = 0.92f, borderB = 1f
        ) {
            if (!authBusy) {
                audioService.play(SoundType.CLICK)
                performLogin(usernameField.text, passwordField.text)
            }
        }

        val registerBtnTable = createSciFiActionButton(
            text = "REGISTER",
            w = 156,
            h = 38,
            bgR = 0.12f, bgG = 0.32f, bgB = 0.24f,
            borderR = 0.32f, borderG = 0.95f, borderB = 0.58f
        ) {
            if (!authBusy) {
                audioService.play(SoundType.CLICK)
                performRegister(usernameField.text, passwordField.text)
            }
        }

        btnRow.add(loginBtnTable).size(156f, 38f).padRight(14f)
        btnRow.add(registerBtnTable).size(156f, 38f)
        authPanel.add(btnRow).colspan(2).center()

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

        interior.add(authPanel).width(440f).height(250f)
        stage.addActor(interior)
        stage.keyboardFocus = usernameField
    }

    private fun performLogin(username: String, password: String) {
        authBusy = true
        authFeedbackLabel?.setText("Authenticating with Firebase Realtime DB...")
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
        authFeedbackLabel?.setText("Creating account in Firebase & storing JWT in SQLite...")
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
    // 2. SHOOT POWER MAIN MENU LOBBY (Matching Reference Image 1:1)
    // =========================================================================
    private fun buildLobbyInterior() {
        val session = activeSession
        val username = session?.username ?: "kushan"

        // --- A. TOP PLAYER BAR (x=268..752, y=326..364) ---
        val topBar = VisTable(false)
        topBar.setBounds(268f, 326f, 484f, 36f)

        val leftProfile = VisTable(false)
        val avTex = avatarTexture
        if (avTex != null) {
            val avImg = Image(TextureRegionDrawable(avTex)).apply {
                setScaling(Scaling.fit)
            }
            leftProfile.add(avImg).size(30f, 30f).padRight(8f)
        }

        val nameStack = VisTable(false)
        val playerNameLbl = VisLabel("Player: $username").apply {
            color = Color(0.35f, 0.95f, 0.52f, 1f)
            setFontScale(0.84f)
        }
        val regionText = if (serverOnline) {
            "SQLite Session Active  •  Region: Global West - ${serverPingMs}ms"
        } else {
            "SQLite Session Active  •  Region: Global West (Offline Ready)"
        }
        val subLbl = VisLabel(regionText).apply {
            color = Color(0.78f, 0.84f, 0.92f, 1f)
            setFontScale(0.62f)
        }
        sessionSubLabel = subLbl
        nameStack.add(playerNameLbl).left().row()
        nameStack.add(subLbl).left().padTop(1f)
        leftProfile.add(nameStack).left()

        val logoutBtn = createSciFiActionButton(
            text = "LOGOUT",
            w = 78,
            h = 28,
            bgR = 0.14f, bgG = 0.19f, bgB = 0.26f,
            borderR = 0.48f, borderG = 0.58f, borderB = 0.70f,
            fontScale = 0.68f
        ) {
            audioService.play(SoundType.CLICK)
            SqliteSessionStore.clearSession()
            activeSession = null
            switchStep(MenuStep.AUTH)
        }

        topBar.add(leftProfile).expandX().left()
        topBar.add(logoutBtn).size(78f, 28f).right()
        stage.addActor(topBar)

        // --- B. SELECT GAME MODE SECTION (x=302..736, y=274..322) ---
        val modeLabel = VisLabel("Select Game Mode:").apply {
            color = Color(0.92f, 0.84f, 0.58f, 1f)
            setFontScale(0.68f)
            setPosition(302f, 308f)
        }
        stage.addActor(modeLabel)

        val modesRow = VisTable(false)
        modesRow.setBounds(302f, 272f, 444f, 34f)
        listOf(
            GameMode.DUO to "2 Players (Duo)",
            GameMode.SQUAD to "4 Players (Squad)",
            GameMode.OFFLINE to "Solo Practice"
        ).forEachIndexed { idx, (mode, title) ->
            val slot = Table()
            val lbl = VisLabel(title).apply {
                setAlignment(Align.center)
                setFontScale(0.70f)
            }
            slot.add(lbl).expand().center()
            slot.addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    selectedMode = mode
                    refreshModeSlots()
                }
            })
            modeSlotTables[mode] = slot
            modeSlotLabels[mode] = lbl
            modesRow.add(slot).width(140f).height(32f).padRight(if (idx < 2) 12f else 0f)
        }
        stage.addActor(modesRow)

        // --- C. MULTIPLAYER ROOM CODE PANEL (x=312..608, y=148..264) ---
        val roomPanel = VisTable(false)
        roomPanel.setBounds(312f, 146f, 294f, 120f)
        roomPanel.background = createSciFiPanelDrawable(
            294, 120,
            0.07f, 0.10f, 0.15f, 0.96f,
            0.25f, 0.42f, 0.58f, 0.9f,
            chamfer = 8,
            leftCyanBar = true
        )
        roomPanel.pad(8f, 12f, 8f, 10f)
        roomPanel.top().left()

        val roomTitle = VisLabel("Multiplayer Room Code (Duo / Squad)").apply {
            color = Color(0.45f, 0.82f, 1f, 1f)
            setFontScale(0.68f)
        }
        roomPanel.add(roomTitle).left().padBottom(6f).row()

        // Create Room Row: Room Code + COPY + NEW + CREATE
        val createRow = VisTable(false)
        val rCodeLbl = VisLabel("Room Code: $generatedRoomCode").apply {
            color = Color(0.96f, 0.88f, 0.62f, 1f)
            setFontScale(0.66f)
        }
        generatedCodeLabel = rCodeLbl

        val copyBtn = createSciFiActionButton(
            text = "COPY",
            w = 46, h = 22,
            bgR = 0.20f, bgG = 0.24f, bgB = 0.30f,
            borderR = 0.45f, borderG = 0.52f, borderB = 0.62f,
            fontScale = 0.58f
        ) {
            audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
            Gdx.app.clipboard.contents = generatedRoomCode
            roomHintLabel?.setText("✓ Copied Room Code $generatedRoomCode to clipboard!")
            roomHintLabel?.color = Color(0.35f, 0.95f, 0.55f, 1f)
        }

        val newBtn = createSciFiActionButton(
            text = "NEW",
            w = 42, h = 22,
            bgR = 0.20f, bgG = 0.24f, bgB = 0.30f,
            borderR = 0.45f, borderG = 0.52f, borderB = 0.62f,
            fontScale = 0.58f
        ) {
            audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
            generatedRoomCode = GameConfig.generateRoomCode()
            generatedCodeLabel?.setText("Room Code: $generatedRoomCode")
        }

        val createRoomBtn = createSciFiActionButton(
            text = "CREATE",
            w = 56, h = 22,
            bgR = 0.12f, bgG = 0.30f, bgB = 0.36f,
            borderR = 0.25f, borderG = 0.90f, borderB = 1f,
            fontScale = 0.58f
        ) {
            audioService.play(SoundType.CLICK)
            if (selectedMode == GameMode.OFFLINE) selectedMode = GameMode.DUO
            Gdx.app.clipboard.contents = generatedRoomCode
            launchMatch(roomCode = generatedRoomCode, createRoom = true)
        }

        createRow.add(rCodeLbl).width(116f).left().padRight(4f)
        createRow.add(copyBtn).size(46f, 22f).padRight(4f)
        createRow.add(newBtn).size(42f, 22f).padRight(4f)
        createRow.add(createRoomBtn).size(56f, 22f)
        roomPanel.add(createRow).left().padBottom(8f).row()

        // Join by Code Row
        val joinRow = VisTable(false)
        val joinLbl = VisLabel("Join by Code:").apply {
            color = Color(0.92f, 0.95f, 1f, 1f)
            setFontScale(0.66f)
        }
        val codeField = VisTextField("").apply {
            messageText = "Enter 6-char code"
            maxLength = 8
        }
        joinRoomCodeField = codeField

        val pasteBtn = createSciFiActionButton(
            text = "PASTE",
            w = 46, h = 22,
            bgR = 0.20f, bgG = 0.24f, bgB = 0.30f,
            borderR = 0.45f, borderG = 0.52f, borderB = 0.62f,
            fontScale = 0.58f
        ) {
            audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
            val clip = Gdx.app.clipboard.contents?.trim()?.uppercase() ?: ""
            val clean = clip.filter { it.isLetterOrDigit() }.take(8)
            if (clean.isNotEmpty()) codeField.text = clean
        }

        val joinBtn = createSciFiActionButton(
            text = "JOIN",
            w = 48, h = 22,
            bgR = 0.12f, bgG = 0.28f, bgB = 0.38f,
            borderR = 0.28f, borderG = 0.88f, borderB = 1f,
            fontScale = 0.58f
        ) {
            audioService.play(SoundType.CLICK)
            val entered = codeField.text.trim().uppercase().filter { it.isLetterOrDigit() }
            if (entered.length < 4) {
                roomHintLabel?.setText("⚠ Enter a valid 4-6 char Room Code to join!")
                roomHintLabel?.color = Color(1f, 0.45f, 0.4f, 1f)
            } else {
                if (selectedMode == GameMode.OFFLINE) selectedMode = GameMode.DUO
                launchMatch(roomCode = entered, createRoom = false)
            }
        }

        joinRow.add(joinLbl).width(70f).left().padRight(4f)
        joinRow.add(codeField).width(96f).height(24f).padRight(4f)
        joinRow.add(pasteBtn).size(46f, 22f).padRight(4f)
        joinRow.add(joinBtn).size(48f, 22f)
        roomPanel.add(joinRow).left().padBottom(6f).row()

        val rHint = VisLabel("Create a room to share the code, or enter a friend's code.").apply {
            color = Color(0.62f, 0.70f, 0.80f, 1f)
            setFontScale(0.56f)
        }
        roomHintLabel = rHint
        roomPanel.add(rHint).left()
        stage.addActor(roomPanel)

        // --- D. CURRENT MATCH STATS OVERLAY CARD (x=544..750, y=132..236) ---
        val statsCard = VisTable(false)
        statsCard.setBounds(546f, 128f, 204f, 108f)
        statsCard.background = createSciFiPanelDrawable(
            204, 108,
            0.08f, 0.13f, 0.20f, 0.98f,
            0.35f, 0.68f, 0.88f, 0.95f,
            chamfer = 8
        )
        statsCard.pad(8f, 12f, 8f, 12f)
        statsCard.top().left()

        val statsHeader = VisLabel("Current Match Stats").apply {
            color = Color(0.55f, 0.88f, 1f, 1f)
            setFontScale(0.76f)
        }
        statsCard.add(statsHeader).left().colspan(2).padBottom(4f).row()

        val leftStatsCol = VisTable(false)
        val lvlLbl = VisLabel("» Level: 45").apply {
            color = Color.WHITE
            setFontScale(0.70f)
        }
        val killsLbl = VisLabel("• Kills: 347").apply {
            color = Color.WHITE
            setFontScale(0.70f)
        }
        val xpLbl = VisLabel("★ XP Earned: 15,250 / 20,000").apply {
            color = Color(0.85f, 0.92f, 1f, 1f)
            setFontScale(0.60f)
        }
        leftStatsCol.add(lvlLbl).left().padBottom(2f).row()
        leftStatsCol.add(killsLbl).left().padBottom(2f).row()
        leftStatsCol.add(xpLbl).left()

        statsCard.add(leftStatsCol).expandX().left()
        val chevTex = rankChevronTexture
        if (chevTex != null) {
            val chevImg = Image(TextureRegionDrawable(chevTex)).apply {
                setScaling(Scaling.fit)
            }
            statsCard.add(chevImg).size(38f, 40f).right()
        }
        statsCard.row()

        // XP Progress Bar + 76%
        val xpBarRow = VisTable(false)
        val xpBarImg = Image(createProgressBarDrawable(142, 10, 0.76f))
        val pctLbl = VisLabel("76%").apply {
            color = Color(0.45f, 0.92f, 1f, 1f)
            setFontScale(0.60f)
        }
        xpBarRow.add(xpBarImg).width(142f).height(9f).padRight(6f)
        xpBarRow.add(pctLbl).right()
        statsCard.add(xpBarRow).colspan(2).left().padTop(5f)
        stage.addActor(statsCard)

        // --- E. BOTTOM-LEFT GLOBAL STATS PANEL (x=274..526, y=68..136) ---
        val globalCard = VisTable(false)
        globalCard.setBounds(274f, 66f, 250f, 68f)
        globalCard.background = createSciFiPanelDrawable(
            250, 68,
            0.07f, 0.11f, 0.16f, 0.96f,
            0.26f, 0.42f, 0.56f, 0.9f,
            chamfer = 7
        )
        globalCard.pad(6f, 10f, 6f, 10f)
        globalCard.top().left()

        val globalHeaderRow = VisTable(false)
        val gTitle = VisLabel("GLOBAL STATS").apply {
            color = Color(0.9f, 0.95f, 1f, 1f)
            setFontScale(0.64f)
        }
        val gCount = VisLabel("16   ★").apply {
            color = Color(0.68f, 0.78f, 0.88f, 1f)
            setFontScale(0.60f)
        }
        globalHeaderRow.add(gTitle).expandX().left()
        globalHeaderRow.add(gCount).right()
        globalCard.add(globalHeaderRow).growX().padBottom(4f).row()

        val globalBodyRow = VisTable(false)
        if (avTex != null) {
            val miniAv = Image(TextureRegionDrawable(avTex)).apply {
                setScaling(Scaling.fit)
            }
            globalBodyRow.add(miniAv).size(26f, 26f).padRight(5f)
        }
        val onlineCol = VisTable(false)
        val count48k = VisLabel("48k").apply {
            color = Color(0.35f, 0.95f, 0.52f, 1f)
            setFontScale(0.64f)
        }
        val onlineTxt = VisLabel("Online").apply {
            color = Color(0.78f, 0.86f, 0.92f, 1f)
            setFontScale(0.56f)
        }
        onlineCol.add(count48k).left().row()
        onlineCol.add(onlineTxt).left()
        globalBodyRow.add(onlineCol).padRight(10f)

        val shTex = rankShieldTexture
        if (shTex != null) {
            val shImg = Image(TextureRegionDrawable(shTex)).apply {
                setScaling(Scaling.fit)
            }
            globalBodyRow.add(shImg).size(28f, 28f).padRight(6f)
        }

        val challengeCol = VisTable(false)
        val chTopRow = VisTable(false)
        val chLbl = VisLabel("Daily Challenge").apply {
            color = Color(0.85f, 0.9f, 0.96f, 1f)
            setFontScale(0.58f)
        }
        val chScore = VisLabel("230").apply {
            color = Color(0.35f, 0.95f, 0.65f, 1f)
            setFontScale(0.60f)
        }
        chTopRow.add(chLbl).expandX().left().padRight(8f)
        chTopRow.add(chScore).right()
        val chBar = Image(createProgressBarDrawable(104, 8, 0.68f))
        challengeCol.add(chTopRow).growX().padBottom(3f).row()
        challengeCol.add(chBar).width(104f).height(8f).left()

        globalBodyRow.add(challengeCol).expandX().left()
        globalCard.add(globalBodyRow).growX()
        stage.addActor(globalCard)

        // --- F. BOTTOM-RIGHT "PLAY / QUICK MATCH" HEXAGONAL SCI-FI BUTTON (x=538..754, y=62..126) ---
        val playQuickBtn = Table()
        playQuickBtn.setBounds(538f, 62f, 214f, 62f)
        playQuickBtn.background = createPlayQuickMatchDrawable(214, 62)

        val playLbl = VisLabel("PLAY / QUICK MATCH").apply {
            setAlignment(Align.center)
            color = Color.WHITE
            setFontScale(0.84f)
        }
        playQuickBtn.add(playLbl).expand().center()
        playQuickBtn.addListener(object : ClickListener() {
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
        stage.addActor(playQuickBtn)

        refreshModeSlots()
    }

    private fun createSciFiActionButton(
        text: String,
        w: Int,
        h: Int,
        bgR: Float,
        bgG: Float,
        bgB: Float,
        borderR: Float,
        borderG: Float,
        borderB: Float,
        fontScale: Float = 0.72f,
        onClick: () -> Unit
    ): Table {
        val t = Table()
        t.background = createSciFiPanelDrawable(
            w, h,
            bgR, bgG, bgB, 0.96f,
            borderR, borderG, borderB, 0.95f,
            chamfer = 5
        )
        val lbl = VisLabel(text).apply {
            setAlignment(Align.center)
            color = Color.WHITE
            setFontScale(fontScale)
        }
        t.add(lbl).expand().center()
        t.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                onClick()
            }
        })
        return t
    }

    private fun refreshModeSlots() {
        val activeDrawable = createSciFiPanelDrawable(
            140, 32,
            0.12f, 0.25f, 0.34f, 0.98f,
            0.22f, 0.92f, 1f, 1f,
            chamfer = 5
        )
        val inactiveDrawable = createSciFiPanelDrawable(
            140, 32,
            0.08f, 0.11f, 0.16f, 0.95f,
            0.26f, 0.34f, 0.44f, 0.85f,
            chamfer = 5
        )

        modeSlotTables.forEach { (mode, table) ->
            table.background = if (mode == selectedMode) activeDrawable else inactiveDrawable
        }
        modeSlotLabels.forEach { (mode, lbl) ->
            lbl.color = if (mode == selectedMode) {
                Color(0.85f, 0.98f, 1f, 1f)
            } else {
                Color(0.75f, 0.80f, 0.88f, 1f)
            }
        }
    }

    private fun pingServer() {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        Thread {
            val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
            val serverOk = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, NetworkConfig.TCP_PORT), 1500)
                }
                true
            } catch (_: Exception) {
                false
            }

            val netOk = if (serverOk) {
                true
            } else {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("8.8.8.8", 53), 1200)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }

            Gdx.app.postRunnable {
                serverOnline = reachable
                serverPingMs = ping
                sessionSubLabel?.setText(
                    if (reachable) {
                        "SQLite Session Active  •  Region: Global West - ${ping}ms"
                    } else {
                        "SQLite Session Active  •  Region: Global West (Offline Ready)"
                    }
                )
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
        ScreenUtils.clear(0.04f, 0.06f, 0.09f, 1f)
        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stopWaitingRoomClient()
        stage.disposeSafely()
        audioService.dispose()
        bgFrameTexture?.disposeSafely()
        avatarTexture?.disposeSafely()
        rankChevronTexture?.disposeSafely()
        rankShieldTexture?.disposeSafely()
        generatedTextures.forEach { it.disposeSafely() }
        generatedTextures.clear()
        generatedFonts.forEach { it.disposeSafely() }
        generatedFonts.clear()
        fontGenerator?.disposeSafely()
        fontGenerator = null
    }
}
