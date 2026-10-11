package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Animation
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
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
import ktx.assets.disposeSafely
import ktx.collections.gdxArrayOf
import java.net.InetSocketAddress
import java.net.Socket

enum class MenuStep {
    SPLASH,
    LOGIN,
    MODE_SELECT
}

class MenuScreen(private val game: Main) : KtxScreen {

    private val stage = Stage(ScreenViewport())
    private val audioService = AudioService()
    private val prefs = Gdx.app.getPreferences("shootgame")

    private var currentStep = MenuStep.SPLASH
    private var hasCompletedSplash = false
    private var splashTimer = 0f
    private val splashDuration = 2.4f

    private var selectedMode = GameMode.DUO
    private var loggedInUsername: String = ""

    private var serverOnline = false
    private var serverPingMs = -1L
    private var serverStatusText = "● CONNECTING TO TELE NETWORK..."
    private var serverStatusColor = Color(0.95f, 0.82f, 0.3f, 1f)

    // Dynamic UI elements updated across ticks
    private var splashProgressFill: Image? = null
    private var splashStatusLabel: VisLabel? = null
    private var splashLogoImage: Image? = null
    private var liveServerStatusLabel: VisLabel? = null
    private var modeSummaryLabel: VisLabel? = null
    private var soldierPreviewImage: Image? = null

    private val modeCardTables = mutableMapOf<GameMode, VisTable>()
    private val modeSelectButtons = mutableMapOf<GameMode, VisTextButton>()

    // Visual textures & animations
    private var teleLogoTexture: Texture? = null
    private var playerAtlas: TextureAtlas? = null
    private var idleAnimation: Animation<TextureRegion>? = null
    private var animTime = 0f

    private val generatedTextures = mutableListOf<Texture>()

    private fun createPanelDrawable(
        bgR: Float,
        bgG: Float,
        bgB: Float,
        bgA: Float,
        borderR: Float,
        borderG: Float,
        borderB: Float,
        borderA: Float
    ): TextureRegionDrawable {
        val size = 32
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixmap.setColor(bgR, bgG, bgB, bgA)
        pixmap.fill()
        pixmap.setColor(borderR, borderG, borderB, borderA)
        pixmap.drawRectangle(0, 0, size, size)
        pixmap.drawRectangle(1, 1, size - 2, size - 2)
        val tex = Texture(pixmap)
        pixmap.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    private fun createSolidDrawable(r: Float, g: Float, b: Float, a: Float): TextureRegionDrawable {
        val pixmap = Pixmap(4, 4, Pixmap.Format.RGBA8888)
        pixmap.setColor(r, g, b, a)
        pixmap.fill()
        val tex = Texture(pixmap)
        pixmap.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    private fun getTeleLogoDrawable(): TextureRegionDrawable {
        if (teleLogoTexture == null) {
            teleLogoTexture = try {
                Texture(Gdx.files.internal("graphics/tele_logo.png")).apply {
                    setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
            } catch (_: Exception) {
                createProceduralTeleLogo()
            }
        }
        return TextureRegionDrawable(teleLogoTexture!!)
    }

    private fun createProceduralTeleLogo(): Texture {
        val s = 256
        val p = Pixmap(s, s, Pixmap.Format.RGBA8888)
        p.setColor(0.04f, 0.06f, 0.11f, 1f)
        p.fill()
        // Outer glowing shield ring
        p.setColor(0.15f, 0.85f, 1f, 0.95f)
        p.drawCircle(s / 2, s / 2, 96)
        p.drawCircle(s / 2, s / 2, 95)
        p.setColor(0.08f, 0.22f, 0.38f, 0.95f)
        p.fillCircle(s / 2, s / 2, 90)
        // Stylized 'T' + Crosshair inside emblem
        p.setColor(0.25f, 0.95f, 1f, 1f)
        p.fillRectangle(s / 2 - 48, s / 2 - 52, 96, 18)
        p.fillRectangle(s / 2 - 10, s / 2 - 52, 20, 108)
        p.drawCircle(s / 2, s / 2 + 6, 34)
        val tex = Texture(p)
        p.dispose()
        return tex
    }

    private fun ensureSoldierAnimation() {
        if (playerAtlas == null) {
            try {
                val atlas = TextureAtlas(Gdx.files.internal("graphics/PlayerObject.atlas"))
                playerAtlas = atlas
                val regions = atlas.findRegions("player/Idle")
                if (!regions.isEmpty) {
                    val frames = gdxArrayOf<TextureRegion>()
                    regions.forEach { frames.add(it) }
                    idleAnimation = Animation(0.12f, frames, Animation.PlayMode.LOOP)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun show() {
        Gdx.input.inputProcessor = stage
        ensureSoldierAnimation()

        if (loggedInUsername.isBlank()) {
            loggedInUsername = prefs.getString("username", "Operative_${(100..999).random()}")
        }

        // Start on Splash first time, or Mode Select if returning from a match after already logging in
        currentStep = if (!hasCompletedSplash) {
            splashTimer = 0f
            MenuStep.SPLASH
        } else {
            MenuStep.MODE_SELECT
        }

        pingOfficialServer()
        rebuildStage()
    }

    private fun switchStep(step: MenuStep) {
        currentStep = step
        if (step != MenuStep.SPLASH) {
            hasCompletedSplash = true
        }
        rebuildStage()
    }

    private fun rebuildStage() {
        stage.clear()
        splashProgressFill = null
        splashStatusLabel = null
        splashLogoImage = null
        liveServerStatusLabel = null
        modeSummaryLabel = null
        soldierPreviewImage = null
        modeCardTables.clear()
        modeSelectButtons.clear()

        when (currentStep) {
            MenuStep.SPLASH -> buildSplashScreen()
            MenuStep.LOGIN -> buildLoginScreen()
            MenuStep.MODE_SELECT -> buildModeSelectScreen()
        }
    }

    // =========================================================================
    // 1. TELE STUDIOS COMPANY LOGO SPLASH SCREEN
    // =========================================================================
    private fun buildSplashScreen() {
        val root = VisTable(false)
        root.setFillParent(true)
        root.center()

        val card = VisTable(false)
        card.pad(32f)

        val logoImg = Image(getTeleLogoDrawable()).apply {
            setScaling(Scaling.fit)
        }
        splashLogoImage = logoImg
        card.add(logoImg).size(250f, 250f).padBottom(14f).row()

        val companyTitle = VisLabel("TELE STUDIOS").apply {
            setAlignment(Align.center)
            color = Color(0.3f, 0.95f, 1f, 1f)
            setFontScale(2.0f)
        }
        card.add(companyTitle).padBottom(4f).row()

        val divisionSubtitle = VisLabel("TACTICAL INTERACTIVE ENTERTAINMENT").apply {
            setAlignment(Align.center)
            color = Color(0.65f, 0.78f, 0.92f, 0.9f)
            setFontScale(0.95f)
        }
        card.add(divisionSubtitle).padBottom(28f).row()

        // Loading bar container
        val barBg = Table()
        barBg.background = createPanelDrawable(
            0.08f, 0.12f, 0.2f, 0.95f,
            0.2f, 0.75f, 0.95f, 0.75f
        )
        val fill = Image(createSolidDrawable(0.2f, 0.92f, 1f, 1f))
        splashProgressFill = fill
        barBg.add(fill).width(10f).height(10f).left()
        card.add(barBg).width(340f).height(14f).padBottom(10f).row()

        val statusLbl = VisLabel("INITIALIZING COMBAT ENGINE...").apply {
            setAlignment(Align.center)
            color = Color(0.55f, 0.85f, 0.95f, 0.9f)
            setFontScale(0.85f)
        }
        splashStatusLabel = statusLbl
        card.add(statusLbl).padBottom(16f).row()

        val skipHint = VisLabel("Click or press SPACE / ENTER to continue").apply {
            setAlignment(Align.center)
            color = Color(0.45f, 0.52f, 0.65f, 0.75f)
            setFontScale(0.8f)
        }
        card.add(skipHint)

        root.add(card)
        stage.addActor(root)

        // Click anywhere to advance to Login
        root.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                switchStep(MenuStep.LOGIN)
            }
        })
    }

    // =========================================================================
    // 2. TACTICAL OPERATIVE LOGIN MENU (NO IP EXPOSED)
    // =========================================================================
    private fun buildLoginScreen() {
        val root = VisTable(false)
        root.setFillParent(true)
        root.pad(24f)

        val outerCard = VisTable(false)
        outerCard.background = createPanelDrawable(
            0.07f, 0.10f, 0.17f, 0.95f,
            0.18f, 0.75f, 0.95f, 0.85f
        )
        outerCard.pad(26f, 36f, 26f, 36f)

        // Top Branding Row (TELE Logo badge + Game Title)
        val headerRow = VisTable(false)
        val miniLogo = Image(getTeleLogoDrawable()).apply {
            setScaling(Scaling.fit)
        }
        val titleStack = VisTable(false)
        val gameTitle = VisLabel("TELE STRIKE : ARENA").apply {
            color = Color(0.25f, 0.95f, 1f, 1f)
            setFontScale(1.75f)
        }
        val studioTag = VisLabel("POWERED BY TELE STUDIOS • TACTICAL MULTIPLAYER").apply {
            color = Color(0.62f, 0.75f, 0.88f, 0.9f)
            setFontScale(0.85f)
        }
        titleStack.add(gameTitle).left().row()
        titleStack.add(studioTag).left().padTop(2f)

        headerRow.add(miniLogo).size(68f, 68f).padRight(16f)
        headerRow.add(titleStack).left()
        outerCard.add(headerRow).padBottom(20f).row()

        // Two-Column Body: Left = Animated Soldier Preview, Right = Login Form
        val bodyRow = VisTable(false)

        // Left: Soldier Preview Box
        val previewBox = VisTable(false)
        previewBox.background = createPanelDrawable(
            0.05f, 0.07f, 0.12f, 0.95f,
            0.15f, 0.45f, 0.65f, 0.7f
        )
        previewBox.pad(14f, 20f, 14f, 20f)

        val previewHeader = VisLabel("OPERATIVE PREVIEW").apply {
            color = Color(0.45f, 0.85f, 1f, 1f)
            setFontScale(0.85f)
        }
        previewBox.add(previewHeader).padBottom(6f).row()

        val firstFrame = idleAnimation?.getKeyFrame(0f)
        val soldierImg = if (firstFrame != null) {
            Image(TextureRegionDrawable(firstFrame)).apply {
                setScaling(Scaling.fit)
            }
        } else {
            Image(getTeleLogoDrawable()).apply {
                setScaling(Scaling.fit)
            }
        }
        soldierPreviewImage = soldierImg
        previewBox.add(soldierImg).size(150f, 150f).padBottom(6f).row()

        val readyBadge = VisLabel("[ STATUS: COMBAT READY ]").apply {
            color = Color(0.35f, 0.95f, 0.55f, 1f)
            setFontScale(0.8f)
        }
        previewBox.add(readyBadge)

        // Right: Operative Sign-In Form
        val loginForm = VisTable(false)
        loginForm.padLeft(24f)

        val signInTitle = VisLabel("OPERATIVE AUTHENTICATION").apply {
            color = Color(1f, 0.86f, 0.32f, 1f)
            setFontScale(1.1f)
        }
        loginForm.add(signInTitle).left().padBottom(6f).row()

        val signInHint = VisLabel("Enter your tactical callsign to access online matchmaking.").apply {
            color = Color(0.68f, 0.76f, 0.86f, 1f)
            setFontScale(0.85f)
        }
        loginForm.add(signInHint).left().padBottom(16f).row()

        val callsignLabel = VisLabel("OPERATIVE CALLSIGN (USERNAME):").apply {
            color = Color(0.85f, 0.92f, 1f, 1f)
            setFontScale(0.9f)
        }
        loginForm.add(callsignLabel).left().padBottom(6f).row()

        val inputRow = VisTable(false)
        val usernameField = VisTextField(loggedInUsername).apply {
            messageText = "Enter Callsign..."
            maxLength = 18
        }
        val randomBtn = VisTextButton("RANDOM").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    val prefixes = listOf("Ghost", "Viper", "Shadow", "Falcon", "Titan", "Nova", "Striker", "Reaper")
                    usernameField.text = "${prefixes.random()}_${(10..99).random()}"
                }
            })
        }
        inputRow.add(usernameField).width(240f).height(42f).padRight(8f)
        inputRow.add(randomBtn).width(86f).height(42f)
        loginForm.add(inputRow).left().padBottom(14f).row()

        // Network status pill (NO IP ADDRESS SHOWN)
        val netRow = VisTable(false)
        val statusLabel = VisLabel(serverStatusText).apply {
            color = serverStatusColor
            setFontScale(0.88f)
        }
        liveServerStatusLabel = statusLabel
        val refreshBtn = VisTextButton("CHECK").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    pingOfficialServer()
                }
            })
        }
        netRow.add(statusLabel).left().padRight(12f)
        netRow.add(refreshBtn).width(68f).height(28f)
        loginForm.add(netRow).left().padBottom(18f).row()

        val loginBtn = VisTextButton("LOGIN & ENTER LOBBY").apply {
            color = Color(0.25f, 0.95f, 0.55f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    completeLogin(usernameField.text)
                }
            })
        }
        loginForm.add(loginBtn).width(334f).height(50f).left()

        usernameField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER) {
                    audioService.play(SoundType.CLICK)
                    completeLogin(usernameField.text)
                    return true
                }
                return false
            }
        })

        bodyRow.add(previewBox).top()
        bodyRow.add(loginForm).top()
        outerCard.add(bodyRow).padBottom(16f).row()

        val footer = VisLabel("© 2026 TELE STUDIOS • OFFICIAL DEDICATED MULTIPLAYER SERVERS").apply {
            setAlignment(Align.center)
            color = Color(0.45f, 0.55f, 0.68f, 0.85f)
            setFontScale(0.78f)
        }
        outerCard.add(footer)

        root.add(outerCard)
        stage.addActor(root)
        stage.keyboardFocus = usernameField
    }

    private fun completeLogin(rawUsername: String) {
        val clean = rawUsername.trim().ifEmpty { "Operative_${(100..999).random()}" }
        loggedInUsername = clean
        prefs.putString("username", clean)
        prefs.flush()
        switchStep(MenuStep.MODE_SELECT)
    }

    private var joinRoomCodeField: VisTextField? = null
    private var roomPanelHintLabel: VisLabel? = null
    private var generatedLobbyCode: String = GameConfig.generateRoomCode()
    private var generatedCodeLabel: VisLabel? = null

    // =========================================================================
    // 3. MATCH MODE SELECTION & GAME ROOM CODE LOBBY (NO IP EXPOSED)
    // =========================================================================
    private fun buildModeSelectScreen() {
        val root = VisTable(false)
        root.setFillParent(true)
        root.pad(14f)

        val lobbyCard = VisTable(false)
        lobbyCard.background = createPanelDrawable(
            0.06f, 0.09f, 0.16f, 0.96f,
            0.2f, 0.78f, 0.98f, 0.85f
        )
        lobbyCard.pad(16f, 24f, 16f, 24f)

        // --- TOP BAR: TELE Badge + Lobby Title + Server Status + Operative Profile ---
        val topBar = VisTable(false)

        val leftBrand = VisTable(false)
        val badgeImg = Image(getTeleLogoDrawable()).apply {
            setScaling(Scaling.fit)
        }
        val brandTitles = VisTable(false)
        val lobbyTitle = VisLabel("TELE STRIKE // COMMAND LOBBY").apply {
            color = Color(0.25f, 0.95f, 1f, 1f)
            setFontScale(1.25f)
        }
        val statusLbl = VisLabel(serverStatusText).apply {
            color = serverStatusColor
            setFontScale(0.8f)
        }
        liveServerStatusLabel = statusLbl
        brandTitles.add(lobbyTitle).left().row()
        brandTitles.add(statusLbl).left().padTop(2f)

        leftBrand.add(badgeImg).size(44f, 44f).padRight(12f)
        leftBrand.add(brandTitles).left()

        val rightProfile = VisTable(false)
        val opLabel = VisLabel("OPERATIVE: $loggedInUsername").apply {
            color = Color(0.4f, 1f, 0.6f, 1f)
            setFontScale(0.92f)
        }
        val logoutBtn = VisTextButton("SWITCH USER").apply {
            color = Color(0.85f, 0.75f, 0.4f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    switchStep(MenuStep.LOGIN)
                }
            })
        }
        rightProfile.add(opLabel).padRight(12f)
        rightProfile.add(logoutBtn).width(115f).height(32f)

        topBar.add(leftBrand).expandX().left()
        topBar.add(rightProfile).right()
        lobbyCard.add(topBar).growX().padBottom(10f).row()

        // --- SECTION HEADER ---
        val selectHeader = VisLabel("1. SELECT COMBAT OPERATION MODE (DUO 2P / SQUAD 4P)").apply {
            color = Color(1f, 0.86f, 0.32f, 1f)
            setFontScale(0.98f)
        }
        lobbyCard.add(selectHeader).left().padBottom(8f).row()

        // --- 3 MATCH MODE CARDS SIDE BY SIDE ---
        val modesRow = VisTable(false)
        modesRow.add(
            createModeCard(
                mode = GameMode.DUO,
                tag = "[ 1v1 / 2-PLAYER ROOM ]",
                title = "DUO ROOM (2P)",
                capacity = "CAPACITY: 2 PLAYERS • ROOM CODE",
                desc = "Create or join a 2-player Duo combat room using a unique Room Code."
            )
        ).width(245f).height(168f).padRight(12f)

        modesRow.add(
            createModeCard(
                mode = GameMode.SQUAD,
                tag = "[ 4-PLAYER SQUAD ROOM ]",
                title = "SQUAD ROOM (4P)",
                capacity = "CAPACITY: 4 PLAYERS • ROOM CODE",
                desc = "Create or join a 4-player Squad battle room with your friends."
            )
        ).width(245f).height(168f).padRight(12f)

        modesRow.add(
            createModeCard(
                mode = GameMode.OFFLINE,
                tag = "[ SOLO AI TRAINING ]",
                title = "TRAINING GROUNDS",
                capacity = "CAPACITY: 1 PLAYER • OFFLINE",
                desc = "Sharpen your aim, weapon handling, and movement vs AI bots."
            )
        ).width(245f).height(168f)

        lobbyCard.add(modesRow).padBottom(10f).row()

        // --- GAME ROOM CREATION & ROOM CODE JOIN PANEL ---
        val roomBox = VisTable(false)
        roomBox.background = createPanelDrawable(
            0.05f, 0.08f, 0.14f, 0.96f,
            0.22f, 0.82f, 1f, 0.8f
        )
        roomBox.pad(10f, 14f, 10f, 14f)

        val roomHeaderRow = VisTable(false)
        val roomHeaderTitle = VisLabel("2. MULTIPLAYER GAME ROOM • CREATE OR JOIN WITH ROOM CODE").apply {
            color = Color(0.35f, 0.95f, 1f, 1f)
            setFontScale(0.92f)
        }
        val roomHint = VisLabel("Share the 6-digit Room Code with your Duo/Squad").apply {
            color = Color(0.68f, 0.8f, 0.92f, 0.9f)
            setFontScale(0.8f)
        }
        roomPanelHintLabel = roomHint
        roomHeaderRow.add(roomHeaderTitle).expandX().left()
        roomHeaderRow.add(roomHint).right()
        roomBox.add(roomHeaderRow).growX().padBottom(8f).row()

        val roomActionsRow = VisTable(false)

        // Left sub-box: Create Room with Generated Server Code
        val createSubBox = VisTable(false)
        val codeBadgeLbl = VisLabel("NEW ROOM CODE: $generatedLobbyCode").apply {
            color = Color(1f, 0.88f, 0.32f, 1f)
            setFontScale(0.9f)
        }
        generatedCodeLabel = codeBadgeLbl

        val copyLobbyCodeBtn = VisTextButton("COPY").apply {
            color = Color(0.4f, 0.9f, 1f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    Gdx.app.clipboard.contents = generatedLobbyCode
                    roomPanelHintLabel?.setText("✓ Copied Room Code $generatedLobbyCode to clipboard!")
                    roomPanelHintLabel?.color = Color(0.35f, 1f, 0.55f, 1f)
                }
            })
        }

        val regenCodeBtn = VisTextButton("NEW").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    generatedLobbyCode = GameConfig.generateRoomCode()
                    generatedCodeLabel?.setText("NEW ROOM CODE: $generatedLobbyCode")
                }
            })
        }

        val createRoomBtn = VisTextButton("CREATE ROOM").apply {
            color = Color(0.25f, 0.95f, 0.55f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    if (selectedMode == GameMode.OFFLINE) {
                        selectedMode = GameMode.DUO
                    }
                    Gdx.app.clipboard.contents = generatedLobbyCode
                    launchMatch(roomCode = generatedLobbyCode, createRoom = true)
                }
            })
        }

        createSubBox.add(codeBadgeLbl).padRight(8f)
        createSubBox.add(copyLobbyCodeBtn).width(58f).height(34f).padRight(6f)
        createSubBox.add(regenCodeBtn).width(54f).height(34f).padRight(8f)
        createSubBox.add(createRoomBtn).width(132f).height(36f)

        // Right sub-box: Join Existing Room by Code
        val joinSubBox = VisTable(false)
        val codeInput = VisTextField("").apply {
            messageText = "Enter Room Code..."
            maxLength = 8
        }
        joinRoomCodeField = codeInput

        val pasteBtn = VisTextButton("PASTE").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                    val clip = Gdx.app.clipboard.contents?.trim()?.uppercase() ?: ""
                    val clean = clip.filter { it.isLetterOrDigit() }.take(8)
                    if (clean.isNotEmpty()) {
                        codeInput.text = clean
                    }
                }
            })
        }

        val joinCodeBtn = VisTextButton("JOIN BY CODE").apply {
            color = Color(0.3f, 0.9f, 1f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    val entered = codeInput.text.trim().uppercase().filter { it.isLetterOrDigit() }
                    if (entered.length < 4) {
                        roomPanelHintLabel?.setText("⚠ Enter a valid 4–6 character Room Code to join!")
                        roomPanelHintLabel?.color = Color(1f, 0.45f, 0.35f, 1f)
                        return
                    }
                    if (selectedMode == GameMode.OFFLINE) {
                        selectedMode = GameMode.DUO
                    }
                    launchMatch(roomCode = entered, createRoom = false)
                }
            })
        }

        joinSubBox.add(codeInput).width(142f).height(36f).padRight(6f)
        joinSubBox.add(pasteBtn).width(62f).height(36f).padRight(6f)
        joinSubBox.add(joinCodeBtn).width(126f).height(36f)

        roomActionsRow.add(createSubBox).expandX().left()
        roomActionsRow.add(joinSubBox).right()
        roomBox.add(roomActionsRow).growX()

        lobbyCard.add(roomBox).growX().padBottom(10f).row()

        // --- LOADOUT & OPERATIVE STRIP ---
        val loadoutBar = VisTable(false)
        loadoutBar.background = createPanelDrawable(
            0.04f, 0.06f, 0.11f, 0.92f,
            0.14f, 0.38f, 0.58f, 0.65f
        )
        loadoutBar.pad(8f, 14f, 8f, 14f)

        val firstFrame = idleAnimation?.getKeyFrame(0f)
        val previewImg = if (firstFrame != null) {
            Image(TextureRegionDrawable(firstFrame)).apply { setScaling(Scaling.fit) }
        } else {
            Image(getTeleLogoDrawable()).apply { setScaling(Scaling.fit) }
        }
        soldierPreviewImage = previewImg

        val loadoutInfo = VisTable(false)
        val loadoutTitle = VisLabel("ACTIVE COMBAT LOADOUT & CONTROLS:").apply {
            color = Color(0.4f, 0.9f, 1f, 1f)
            setFontScale(0.84f)
        }
        val weaponsText = VisLabel("1: PISTOL [12/60]  •  2: SHOTGUN [6/30]  •  3: MACHINE GUN [30/120]  •  G: FRAG [x3]  |  A/D Move • W Jump • SPACE Fire • R Reload").apply {
            color = Color(0.85f, 0.9f, 0.98f, 1f)
            setFontScale(0.78f)
        }
        loadoutInfo.add(loadoutTitle).left().row()
        loadoutInfo.add(weaponsText).left().padTop(2f)

        loadoutBar.add(previewImg).size(44f, 44f).padRight(12f)
        loadoutBar.add(loadoutInfo).expandX().left()
        lobbyCard.add(loadoutBar).growX().padBottom(12f).row()

        // --- BOTTOM DEPLOY BAR ---
        val bottomBar = VisTable(false)
        val summaryLbl = VisLabel("").apply {
            color = Color(0.7f, 0.92f, 0.8f, 1f)
            setFontScale(0.88f)
        }
        modeSummaryLabel = summaryLbl

        val deployBtn = VisTextButton("QUICK DEPLOY").apply {
            color = Color(0.25f, 1f, 0.5f, 1f)
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

        bottomBar.add(summaryLbl).expandX().left()
        bottomBar.add(deployBtn).width(230f).height(46f).right()
        lobbyCard.add(bottomBar).growX()

        root.add(lobbyCard)
        stage.addActor(root)

        refreshModeCardStyles()
    }

    private fun createModeCard(
        mode: GameMode,
        tag: String,
        title: String,
        capacity: String,
        desc: String
    ): VisTable {
        val card = VisTable(false)
        card.pad(12f)

        val tagLabel = VisLabel(tag).apply {
            color = Color(0.4f, 0.88f, 1f, 1f)
            setFontScale(0.74f)
        }
        val titleLabel = VisLabel(title).apply {
            color = Color.WHITE
            setFontScale(1.1f)
        }
        val capLabel = VisLabel(capacity).apply {
            color = Color(1f, 0.84f, 0.32f, 1f)
            setFontScale(0.74f)
        }
        val descLabel = VisLabel(desc).apply {
            setWrap(true)
            color = Color(0.72f, 0.8f, 0.9f, 0.95f)
            setFontScale(0.78f)
        }

        val selectBtn = VisTextButton(if (selectedMode == mode) "SELECTED" else "SELECT MODE")
        modeSelectButtons[mode] = selectBtn

        card.add(tagLabel).left().padBottom(2f).row()
        card.add(titleLabel).left().padBottom(2f).row()
        card.add(capLabel).left().padBottom(6f).row()
        card.add(descLabel).width(215f).left().expandY().top().padBottom(6f).row()
        card.add(selectBtn).width(215f).height(30f)

        val clickListener = object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                selectedMode = mode
                refreshModeCardStyles()
            }
        }
        card.addListener(clickListener)
        modeCardTables[mode] = card
        return card
    }

    private fun refreshModeCardStyles() {
        val activeDrawable = createPanelDrawable(
            0.10f, 0.18f, 0.28f, 0.98f,
            0.25f, 0.95f, 1f, 1f
        )
        val inactiveDrawable = createPanelDrawable(
            0.05f, 0.08f, 0.14f, 0.92f,
            0.16f, 0.26f, 0.40f, 0.75f
        )

        modeCardTables.forEach { (mode, table) ->
            table.background = if (mode == selectedMode) activeDrawable else inactiveDrawable
        }
        modeSelectButtons.forEach { (mode, btn) ->
            if (mode == selectedMode) {
                btn.setText("✓ SELECTED")
                btn.color = Color(0.25f, 0.95f, 1f, 1f)
            } else {
                btn.setText("SELECT MODE")
                btn.color = Color(0.62f, 0.68f, 0.76f, 1f)
            }
        }

        modeSummaryLabel?.setText(
            "MODE: ${selectedMode.displayName.uppercase()} (${selectedMode.maxPlayers}P MAX)  •  CREATE ROOM, JOIN CODE, OR QUICK DEPLOY"
        )
    }

    private fun pingOfficialServer() {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        serverStatusText = "● CONNECTING TO TELE OFFICIAL SERVERS..."
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
                serverOnline = reachable
                serverPingMs = ping
                if (reachable) {
                    serverStatusText = "● TELE OFFICIAL SERVERS ONLINE  •  LATENCY ${ping}ms"
                    serverStatusColor = Color(0.32f, 0.96f, 0.52f, 1f)
                } else {
                    serverStatusText = "○ TELE SERVERS OFFLINE  •  SOLO AI TRAINING READY"
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
        val config = GameConfig(
            mode = selectedMode,
            playerName = loggedInUsername,
            serverHost = host,
            roomCode = roomCode.trim().uppercase(),
            createRoom = createRoom
        )
        game.startGame(config)
    }

    override fun render(delta: Float) {
        ScreenUtils.clear(0.043f, 0.063f, 0.106f, 1f) // #0b101b tactical dark navy
        animTime += delta

        // Animate soldier preview in Login & Mode Select screens
        val anim = idleAnimation
        val preview = soldierPreviewImage
        if (anim != null && preview != null) {
            preview.drawable = TextureRegionDrawable(anim.getKeyFrame(animTime, true))
        }

        // Update Splash screen progress & auto-advance
        if (currentStep == MenuStep.SPLASH) {
            splashTimer += delta
            val progress = (splashTimer / splashDuration).coerceIn(0f, 1f)
            splashProgressFill?.setSize(336f * progress, 10f)
            splashLogoImage?.color?.a = (progress * 1.8f).coerceIn(0.15f, 1f)

            when {
                progress < 0.45f -> splashStatusLabel?.setText("INITIALIZING TELE TACTICAL ENGINE...")
                progress < 0.85f -> splashStatusLabel?.setText("CONNECTING TO TELE ONLINE SERVICES...")
                else -> splashStatusLabel?.setText("COMBAT SYSTEMS READY")
            }

            if (splashTimer >= splashDuration ||
                Gdx.input.isKeyJustPressed(Input.Keys.SPACE) ||
                Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
            ) {
                switchStep(MenuStep.LOGIN)
            }
        }

        stage.act(delta)
        stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        stage.viewport.update(width, height, true)
    }

    override fun dispose() {
        stage.disposeSafely()
        audioService.dispose()
        teleLogoTexture?.disposeSafely()
        playerAtlas?.disposeSafely()
        generatedTextures.forEach { it.disposeSafely() }
        generatedTextures.clear()
    }
}
