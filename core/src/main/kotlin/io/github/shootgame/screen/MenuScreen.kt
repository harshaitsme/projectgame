package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.badlogic.gdx.utils.ScreenUtils
import com.badlogic.gdx.utils.viewport.StretchViewport
import io.github.shootgame.Main
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.auth.AuthSessionRecord
import io.github.shootgame.auth.FirebaseAuthService
import io.github.shootgame.auth.SqliteSessionStore
import io.github.shootgame.network.GameClient
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
    LOBBY,
    WAITING_ROOM
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

    // Live connectivity state
    @Volatile private var internetAvailable = true
    @Volatile private var serverOnline = false
    @Volatile private var serverPingMs = 0L
    @Volatile private var checkingNetwork = false

    // Waiting Room state
    private var lobbyClient: GameClient? = null
    private var waitingRoomCode: String = ""
    private var waitingCreateRoom: Boolean = true
    private var waitingAutoStart: Boolean = true
    private var waitingConnectElapsed: Float = 0f
    private var waitingHeartbeatTimer: Float = 0f
    private var waitingDotTimer: Float = 0f
    private var waitingDotCount: Int = 1
    private var waitingCountdown: Float = -1f
    private var savedLoginResponse: LoginResponsePacket? = null
    private val waitingMembers = linkedMapOf<Int, WaitingRoomMember>()

    // Dynamic UI references
    private var authFeedbackLabel: Label? = null
    private var lobbyStatusLabel: Label? = null
    private var roomCodeField: TextField? = null
    private var networkBadgeLabel: Label? = null
    private var sessionSubLabel: Label? = null

    // Waiting Room UI references
    private var waitingCodeValueLabel: Label? = null
    private var waitingStatusBannerLabel: Label? = null
    private var waitingCountLabel: Label? = null
    private var waitingSlotsContainer: Table? = null
    private var waitingAutoStartBtnLabel: Label? = null

    // Modal Popup overlay reference
    private var activePopupOverlay: Table? = null

    // Textures & Fonts
    private var bgFrameTexture: Texture? = null
    private var avatarTexture: Texture? = null
    private var rankChevronTexture: Texture? = null
    private var rankShieldTexture: Texture? = null
    private val generatedTextures = mutableListOf<Texture>()

    private var fontGenerator: FreeTypeFontGenerator? = null
    private val generatedFonts = mutableListOf<BitmapFont>()
    private lateinit var fontTiny: BitmapFont
    private lateinit var fontSmall: BitmapFont
    private lateinit var fontMedium: BitmapFont
    private lateinit var fontLarge: BitmapFont
    private lateinit var fontXLarge: BitmapFont
    private lateinit var fontHero: BitmapFont

    // Futuristic Sci-Fi Cyberpunk Yellow/Orange Palette (Matched to Gameplay HUD)
    private val colCyberOrange = Color(1.0f, 0.58f, 0.02f, 1f)      // Neon cyber-orange border
    private val colCyberOrangeDark = Color(0.78f, 0.38f, 0.01f, 1f) // Deep amber accent
    private val colCyberYellow = Color(1.0f, 0.90f, 0.04f, 1f)      // Cyber neon yellow highlight
    private val colCyberDanger = Color(0.96f, 0.22f, 0.26f, 1f)      // Alert red for errors
    private val colCyberGreen = Color(0.24f, 0.96f, 0.48f, 1f)       // Online / Ready neon green
    private val colCyberCyan = Color(0.25f, 0.92f, 1.0f, 1f)         // Secondary tech accent
    private val colCyberGrey = Color(0.38f, 0.40f, 0.44f, 1f)        // Hazard chevrons / muted
    private val colDarkChamber = Color(0.08f, 0.09f, 0.12f, 0.95f)   // Chassis dark background
    private val colDarkPanel = Color(0.11f, 0.13f, 0.17f, 0.93f)     // Inner card fill
    private val colDarkTrack = Color(0.16f, 0.18f, 0.22f, 0.98f)     // Input field recess
    private val colButtonBg = Color(0.13f, 0.15f, 0.20f, 0.94f)
    private val colButtonPressed = Color(0.32f, 0.20f, 0.04f, 0.96f)
    private val colTextDim = Color(0.72f, 0.76f, 0.82f, 1f)

    override fun show() {
        Gdx.input.inputProcessor = stage
        initFonts()
        loadMenuAssets()
        audioService.playMusic()

        val cached = SqliteSessionStore.loadActiveSession()
        if (cached != null && cached.jwtToken.isNotBlank()) {
            activeSession = cached
            currentStep = MenuStep.LOBBY
        } else {
            currentStep = MenuStep.AUTH
        }

        probeServerAsync()
        rebuildUi()
    }

    private fun initFonts() {
        if (generatedFonts.isNotEmpty()) return
        val candidates = listOf(
            "fonts/KnightWarrior.otf",
            "assets/fonts/KnightWarrior.otf"
        )
        val fontHandle = candidates
            .map { Gdx.files.internal(it) }
            .firstOrNull { it.exists() }

        if (fontHandle != null) {
            try {
                val generator = FreeTypeFontGenerator(fontHandle)
                fontGenerator = generator

                val makeFont = { size: Int, border: Float, shadow: Float ->
                    val param = FreeTypeFontParameter().apply {
                        this.size = size
                        this.color = Color.WHITE
                        this.borderWidth = border
                        this.borderColor = Color(0f, 0f, 0f, 1f)
                        this.shadowOffsetX = shadow.toInt()
                        this.shadowOffsetY = shadow.toInt()
                        this.shadowColor = Color(0f, 0f, 0f, 0.92f)
                        this.minFilter = Texture.TextureFilter.Linear
                        this.magFilter = Texture.TextureFilter.Linear
                    }
                    generator.generateFont(param).also { generatedFonts.add(it) }
                }

                fontTiny = makeFont(12, 1.1f, 1f)
                fontSmall = makeFont(15, 1.4f, 1f)
                fontMedium = makeFont(19, 1.7f, 2f)
                fontLarge = makeFont(24, 2.0f, 2f)
                fontXLarge = makeFont(30, 2.4f, 3f)
                fontHero = makeFont(38, 2.8f, 3f)
                return
            } catch (e: Exception) {
                Gdx.app.error("MenuScreen", "Failed to load KnightWarrior.otf, using default font", e)
            }
        }

        val fallback = BitmapFont()
        generatedFonts.add(fallback)
        fontTiny = fallback
        fontSmall = fallback
        fontMedium = fallback
        fontLarge = fallback
        fontXLarge = fallback
        fontHero = fallback
    }

    private fun loadMenuAssets() {
        bgFrameTexture = loadTexSafely("graphics/sp_menu_frame_bg.png")
        avatarTexture = loadTexSafely("graphics/sp_avatar.png")
        rankChevronTexture = loadTexSafely("graphics/sp_rank_chevron.png")
        rankShieldTexture = loadTexSafely("graphics/sp_rank_shield.png")
    }

    private fun loadTexSafely(path: String): Texture? {
        return try {
            val file = Gdx.files.internal(path)
            if (file.exists()) {
                Texture(file).apply {
                    setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun makeSolidDrawable(r: Float, g: Float, b: Float, a: Float): TextureRegionDrawable {
        val pm = Pixmap(4, 4, Pixmap.Format.RGBA8888)
        pm.setColor(r, g, b, a)
        pm.fill()
        val tex = Texture(pm)
        pm.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    /**
     * Procedurally generates a Sci-Fi Cyber-Yellow/Orange chamfered panel matching UiSystem.kt:
     * - Chamfered outer border in accentColor (Cyber Orange / Yellow / Red)
     * - Optional top asymmetrical tech tab with diagonal hazard chevrons (///)
     * - Segmented status LED pips (■ ■ ■ ■)
     * - Dark tactical chamber interior
     */
    private fun createSciFiPanelDrawable(
        w: Int,
        h: Int,
        bgColor: Color = colDarkChamber,
        borderColor: Color = colCyberOrange,
        borderWidth: Int = 2,
        chamfer: Int = 10,
        withTechTab: Boolean = true,
        accentPipsColor: Color = colCyberYellow
    ): TextureRegionDrawable {
        val width = w.coerceAtLeast(32)
        val height = h.coerceAtLeast(24)
        val pm = Pixmap(width, height, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        pm.setColor(0f, 0f, 0f, 0f)
        pm.fill()
        pm.blending = Pixmap.Blending.SourceOver

        val tabH = if (withTechTab && height >= 44) 5 else 0
        val ch = chamfer.coerceAtMost(width / 4).coerceAtMost((height - tabH) / 3)

        fun fillChamferedBox(ox: Int, oy: Int, bw: Int, bh: Int, c: Int, col: Color) {
            pm.setColor(col)
            pm.fillRectangle(ox + c, oy, bw - c * 2, bh)
            pm.fillRectangle(ox, oy + c, c, bh - c * 2)
            pm.fillRectangle(ox + bw - c, oy + c, c, bh - c * 2)
            pm.fillTriangle(ox, oy + c, ox + c, oy + c, ox + c, oy)
            pm.fillTriangle(ox + bw - c, oy, ox + bw - c, oy + c, ox + bw - 1, oy + c)
            pm.fillTriangle(ox, oy + bh - c, ox + c, oy + bh - c, ox + c, oy + bh - 1)
            pm.fillTriangle(ox + bw - c, oy + bh - 1, ox + bw - c, oy + bh - c, ox + bw - 1, oy + bh - c)
        }

        val baseY = tabH
        val baseH = height - tabH
        fillChamferedBox(0, baseY, width, baseH, ch, borderColor)

        if (tabH > 0) {
            val tabW = (width * 0.28f).toInt().coerceIn(36, 140)
            val tabStart = (width - tabW - ch - 8).coerceAtLeast(ch + 8)
            pm.setColor(borderColor)
            pm.fillRectangle(tabStart, 0, tabW, tabH + 1)
            pm.fillTriangle(tabStart - tabH, tabH, tabStart, tabH, tabStart, 0)
            pm.fillTriangle(tabStart + tabW, 0, tabStart + tabW, tabH, tabStart + tabW + tabH, tabH)
        }

        fillChamferedBox(
            borderWidth,
            baseY + borderWidth,
            width - borderWidth * 2,
            baseH - borderWidth * 2,
            max(2, ch - borderWidth),
            bgColor
        )

        // Diagonal hazard chevrons (///) on top-right
        if (width >= 90 && height >= 32) {
            val chevStart = width - ch - 38
            val chevY = baseY + 4
            for (i in 0 until 4) {
                val sx = chevStart + i * 8
                pm.setColor(if (i < 2) accentPipsColor else colCyberGrey)
                pm.drawLine(sx, chevY + 4, sx + 4, chevY)
                pm.drawLine(sx + 1, chevY + 4, sx + 5, chevY)
            }
        }

        // Segmented bottom status pips (■ ■ ■ ■)
        if (width >= 100 && height >= 44) {
            val pipsStart = ch + 8
            val pipsY = height - borderWidth - 3
            for (i in 0 until 4) {
                val px = pipsStart + i * 9
                pm.setColor(if (i < 3) accentPipsColor else colCyberGrey)
                pm.fillRectangle(px, pipsY, 5, 2)
            }
        }

        val tex = Texture(pm).apply {
            setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        }
        pm.dispose()
        generatedTextures.add(tex)
        return TextureRegionDrawable(tex)
    }

    private fun createCyberTextFieldStyle(): TextField.TextFieldStyle {
        val bg = createSciFiPanelDrawable(
            w = 240,
            h = 34,
            bgColor = colDarkTrack,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false,
            accentPipsColor = colCyberYellow
        )
        bg.leftWidth = 10f
        bg.rightWidth = 10f
        bg.topHeight = 4f
        bg.bottomHeight = 4f

        val focusedBg = createSciFiPanelDrawable(
            w = 240,
            h = 34,
            bgColor = Color(0.18f, 0.16f, 0.08f, 0.98f),
            borderColor = colCyberYellow,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false,
            accentPipsColor = colCyberYellow
        )
        focusedBg.leftWidth = 10f
        focusedBg.rightWidth = 10f
        focusedBg.topHeight = 4f
        focusedBg.bottomHeight = 4f

        val cursor = makeSolidDrawable(1f, 0.90f, 0.04f, 1f).apply { minWidth = 2f }
        val selection = makeSolidDrawable(1f, 0.58f, 0.02f, 0.45f)

        return TextField.TextFieldStyle().apply {
            font = fontSmall
            fontColor = colCyberYellow
            focusedFontColor = Color.WHITE
            messageFont = fontSmall
            messageFontColor = colCyberGrey
            background = bg
            focusedBackground = focusedBg
            this.cursor = cursor
            this.selection = selection
        }
    }

    private fun rebuildUi() {
        stage.clear()
        activePopupOverlay = null

        val rootStack = Stack()
        rootStack.setFillParent(true)

        // 1. Background image + dark cyber overlay so yellow/orange HUD elements pop
        if (bgFrameTexture != null) {
            val bgImg = Image(TextureRegionDrawable(bgFrameTexture!!)).apply {
                setScaling(Scaling.stretch)
            }
            rootStack.add(bgImg)
            val tintOverlay = Image(makeSolidDrawable(0.04f, 0.05f, 0.08f, 0.55f))
            rootStack.add(tintOverlay)
        } else {
            val fallbackBg = Table().apply {
                background = makeSolidDrawable(0.05f, 0.06f, 0.09f, 1f)
            }
            rootStack.add(fallbackBg)
        }

        // 2. Foreground interactive layer
        val fgLayer = Table()
        fgLayer.setFillParent(true)

        when (currentStep) {
            MenuStep.AUTH -> buildAuthOverlay(fgLayer)
            MenuStep.LOBBY -> buildMainMenuLobby(fgLayer)
            MenuStep.WAITING_ROOM -> buildWaitingRoomMenu(fgLayer)
        }

        rootStack.add(fgLayer)
        stage.addActor(rootStack)
    }

    // =========================================================================
    // MODAL POPUP MENU SYSTEM (Network Errors, Validation, Room Alerts)
    // =========================================================================
    private fun showPopupModal(
        headerTag: String,
        title: String,
        message: String,
        isDanger: Boolean = true,
        primaryText: String = "OK",
        onPrimary: () -> Unit = {},
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
        tertiaryText: String? = null,
        onTertiary: (() -> Unit)? = null
    ) {
        activePopupOverlay?.remove()

        val accent = if (isDanger) colCyberDanger else colCyberYellow
        val borderCol = if (isDanger) colCyberDanger else colCyberOrange

        val backdrop = Table()
        backdrop.setFillParent(true)
        backdrop.background = makeSolidDrawable(0.02f, 0.02f, 0.04f, 0.82f)
        // Consume clicks behind the modal
        backdrop.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                // Keep modal open until an action button is clicked
            }
        })

        val modalW = 580f
        val modalH = 290f
        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = modalW.toInt(),
            h = modalH.toInt(),
            bgColor = Color(0.08f, 0.09f, 0.12f, 0.98f),
            borderColor = borderCol,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true,
            accentPipsColor = accent
        )
        card.pad(18f, 24f, 18f, 24f)

        // Top warning bar
        val topTagRow = Table()
        val tagLbl = Label("/// $headerTag ///", Label.LabelStyle(fontTiny, accent))
        val codeTag = Label(if (isDanger) "ERR_NET_LINK" else "SYS_NOTICE", Label.LabelStyle(fontTiny, colCyberYellow))
        topTagRow.add(tagLbl).left().expandX()
        topTagRow.add(codeTag).right()
        card.add(topTagRow).fillX().padBottom(6f).row()

        // Main Title
        val titleLbl = Label(title, Label.LabelStyle(fontLarge, if (isDanger) colCyberYellow else Color.WHITE))
        card.add(titleLbl).left().padBottom(6f).row()

        // Divider bar
        val div = Image(makeSolidDrawable(borderCol.r, borderCol.g, borderCol.b, 0.65f))
        card.add(div).height(2f).fillX().padBottom(10f).row()

        // Message body
        val msgLbl = Label(message, Label.LabelStyle(fontSmall, Color.WHITE)).apply {
            wrap = true
            setAlignment(Align.topLeft)
        }
        card.add(msgLbl).width(modalW - 52f).expandY().top().left().padBottom(14f).row()

        // Action Buttons Row
        val btnRow = Table()
        val pBtn = createCyberButton(
            text = primaryText,
            width = 168f,
            height = 38f,
            fillColor = if (isDanger) Color(0.32f, 0.16f, 0.04f, 0.96f) else colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontSmall
        ) {
            dismissPopupModal()
            onPrimary()
        }
        btnRow.add(pBtn).width(168f).height(38f)

        if (secondaryText != null && onSecondary != null) {
            val sBtn = createCyberButton(
                text = secondaryText,
                width = 176f,
                height = 38f,
                fillColor = colButtonBg,
                borderColor = colCyberOrange,
                textColor = Color.WHITE,
                fontOverride = fontSmall
            ) {
                dismissPopupModal()
                onSecondary()
            }
            btnRow.add(sBtn).width(176f).height(38f).padLeft(10f)
        }

        if (tertiaryText != null && onTertiary != null) {
            val tBtn = createCyberButton(
                text = tertiaryText,
                width = 120f,
                height = 38f,
                fillColor = Color(0.16f, 0.10f, 0.12f, 0.95f),
                borderColor = colCyberDanger,
                textColor = colTextDim,
                fontOverride = fontSmall
            ) {
                dismissPopupModal()
                onTertiary()
            }
            btnRow.add(tBtn).width(120f).height(38f).padLeft(10f)
        }

        card.add(btnRow).right()

        backdrop.add(card).width(modalW).height(modalH).center()
        activePopupOverlay = backdrop
        stage.addActor(backdrop)
    }

    private fun dismissPopupModal() {
        activePopupOverlay?.remove()
        activePopupOverlay = null
    }

    private fun showNetworkUnavailablePopup(attemptedAction: String, onRetry: () -> Unit) {
        val netDetail = when {
            !internetAvailable -> "NO INTERNET CONNECTION DETECTED"
            !serverOnline -> "MULTIPLAYER SERVER UNREACHABLE"
            else -> "CONNECTION TIMEOUT"
        }
        showPopupModal(
            headerTag = "NETWORK CONNECTION ALERT",
            title = "CANNOT PLAY ${selectedMode.displayName.uppercase()} OFFLINE",
            message = "Unable to $attemptedAction because $netDetail.\n\n" +
                "• 2P DUO and 4P SQUAD modes require an active internet and game server connection.\n" +
                "• Check your Wi-Fi / mobile data or switch to SOLO PRACTICE to play offline against AI bots.",
            isDanger = true,
            primaryText = "RETRY NETWORK",
            onPrimary = {
                probeServerAsync(forceCallback = onRetry)
            },
            secondaryText = "PLAY SOLO OFFLINE",
            onSecondary = {
                selectedMode = GameMode.OFFLINE
                rebuildUi()
                launchMatch(roomCode = "", createRoom = false)
            },
            tertiaryText = "CLOSE",
            onTertiary = {}
        )
    }

    // =========================================================================
    // STEP 1: SCI-FI CYBER-YELLOW USERNAME & PASSWORD AUTHENTICATION
    // =========================================================================
    private fun buildAuthOverlay(parent: Table) {
        val wrapper = Table()
        wrapper.setFillParent(true)

        val cardW = 490f
        val cardH = 345f
        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = cardW.toInt(),
            h = cardH.toInt(),
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        card.pad(18f, 26f, 18f, 26f)

        val badgeLbl = Label("/// SHOOT POWER // TACTICAL AUTHENTICATION ///", Label.LabelStyle(fontTiny, colCyberOrange))
        card.add(badgeLbl).left().padBottom(4f).row()

        val title = Label("OPERATIVE SIGN IN", Label.LabelStyle(fontXLarge, colCyberYellow))
        card.add(title).left().padBottom(2f).row()

        val sub = Label(
            "FIREBASE CLOUD ACCOUNT  •  LOCAL SQLITE JWT SESSION",
            Label.LabelStyle(fontTiny, colTextDim)
        )
        card.add(sub).left().padBottom(12f).row()

        val tfStyle = createCyberTextFieldStyle()

        // Username row
        val userLabel = Label("USERNAME", Label.LabelStyle(fontSmall, colCyberOrange))
        card.add(userLabel).left().padBottom(3f).row()

        val usernameField = TextField("", tfStyle).apply {
            messageText = "Enter operative username..."
            maxLength = 20
        }
        card.add(usernameField).width(430f).height(34f).left().padBottom(10f).row()

        // Password row
        val passLabel = Label("PASSWORD", Label.LabelStyle(fontSmall, colCyberOrange))
        card.add(passLabel).left().padBottom(3f).row()

        val passwordField = TextField("", tfStyle).apply {
            messageText = "Enter password (min 4 chars)..."
            isPasswordMode = true
            setPasswordCharacter('*')
            maxLength = 32
        }
        card.add(passwordField).width(430f).height(34f).left().padBottom(8f).row()

        // Status / Feedback label
        val feedback = Label(
            "SIGN IN OR REGISTER NEW ACCOUNT TO SYNC STATS",
            Label.LabelStyle(fontTiny, colCyberYellow)
        )
        authFeedbackLabel = feedback
        card.add(feedback).left().padBottom(12f).row()

        // Buttons row
        val btnRow = Table()
        val loginBtn = createCyberButton(
            text = "LOGIN",
            width = 206f,
            height = 42f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontMedium
        ) {
            handleAuthSubmit(usernameField.text, passwordField.text, isRegister = false)
        }

        val registerBtn = createCyberButton(
            text = "CREATE ACCOUNT",
            width = 206f,
            height = 42f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = Color.WHITE,
            fontOverride = fontMedium
        ) {
            handleAuthSubmit(usernameField.text, passwordField.text, isRegister = true)
        }

        btnRow.add(loginBtn).width(206f).height(42f).padRight(16f)
        btnRow.add(registerBtn).width(206f).height(42f)
        card.add(btnRow).left()

        passwordField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER) {
                    handleAuthSubmit(usernameField.text, passwordField.text, isRegister = false)
                    return true
                }
                return false
            }
        })

        wrapper.add(card).width(cardW).height(cardH).center()
        parent.add(wrapper).expand().fill()
        stage.keyboardFocus = usernameField
    }

    private fun handleAuthSubmit(rawUser: String, rawPass: String, isRegister: Boolean) {
        if (authBusy) return
        val username = rawUser.trim()
        val password = rawPass.trim()

        if (username.length < 3) {
            showPopupModal(
                headerTag = "VALIDATION ERROR",
                title = "INVALID USERNAME",
                message = "Your operative username must be at least 3 characters long.\nPlease enter a valid username to continue.",
                isDanger = true,
                primaryText = "GOT IT"
            )
            return
        }
        if (password.length < 4) {
            showPopupModal(
                headerTag = "VALIDATION ERROR",
                title = "INVALID PASSWORD",
                message = "Your password must be at least 4 characters long.\nPlease enter a valid password to continue.",
                isDanger = true,
                primaryText = "GOT IT"
            )
            return
        }

        authBusy = true
        if (isRegister) {
            authFeedbackLabel?.setText("REGISTERING [$username] IN FIREBASE...")
            authFeedbackLabel?.color = colCyberYellow
            FirebaseAuthService.register(username, password) { result ->
                authBusy = false
                result.onSuccess { session ->
                    activeSession = session
                    currentStep = MenuStep.LOBBY
                    rebuildUi()
                }.onFailure { err ->
                    val errMsg = err.message ?: "Account creation failed."
                    authFeedbackLabel?.setText("ERROR: $errMsg")
                    authFeedbackLabel?.color = colCyberDanger
                    showPopupModal(
                        headerTag = "REGISTRATION ERROR",
                        title = "ACCOUNT CREATION FAILED",
                        message = errMsg,
                        isDanger = true,
                        primaryText = "TRY AGAIN"
                    )
                }
            }
        } else {
            authFeedbackLabel?.setText("AUTHENTICATING [$username]...")
            authFeedbackLabel?.color = colCyberYellow
            FirebaseAuthService.login(username, password) { result ->
                authBusy = false
                result.onSuccess { session ->
                    activeSession = session
                    currentStep = MenuStep.LOBBY
                    rebuildUi()
                }.onFailure { err ->
                    val errMsg = err.message ?: "Sign in failed."
                    authFeedbackLabel?.setText("ERROR: $errMsg")
                    authFeedbackLabel?.color = colCyberDanger
                    showPopupModal(
                        headerTag = "AUTHENTICATION ERROR",
                        title = "SIGN IN FAILED",
                        message = errMsg,
                        isDanger = true,
                        primaryText = "TRY AGAIN"
                    )
                }
            }
        }
    }

    // =========================================================================
    // STEP 2: MAIN MENU LOBBY (SCI-FI CYBER-YELLOW & ORANGE HUD THEME)
    // =========================================================================
    private fun buildMainMenuLobby(parent: Table) {
        val session = activeSession
        val username = session?.username ?: "Operative"

        val root = Table()
        root.setFillParent(true)
        root.pad(10f, 14f, 10f, 14f)

        // ---------------------------------------------------------------------
        // TOP HEADER BAR (Sci-Fi Chamfered Bar with Title, Network Pill, Audio)
        // ---------------------------------------------------------------------
        val topHeader = Table()
        topHeader.background = createSciFiPanelDrawable(
            w = 996,
            h = 52,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 10,
            withTechTab = false,
            accentPipsColor = colCyberYellow
        )
        topHeader.pad(6f, 16f, 6f, 16f)

        // Left: Brand Title
        val titleStack = Table()
        val mainTitle = Label("SHOOT POWER", Label.LabelStyle(fontLarge, colCyberYellow))
        val subTag = Label("TACTICAL ARENA COMMAND", Label.LabelStyle(fontTiny, colCyberOrange))
        titleStack.add(mainTitle).left().padRight(12f)
        titleStack.add(subTag).left()
        topHeader.add(titleStack).left()

        // Center: Live Network Status Badge + Retry button
        val netTable = Table()
        netTable.background = createSciFiPanelDrawable(
            w = 270,
            h = 34,
            bgColor = colDarkPanel,
            borderColor = if (serverOnline) colCyberYellow else colCyberDanger,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false
        )
        netTable.pad(4f, 10f, 4f, 10f)
        val netStatusStr = when {
            checkingNetwork -> "CHECKING NETWORK..."
            serverOnline -> "SERVER ONLINE • ${serverPingMs}ms"
            !internetAvailable -> "OFFLINE • NO INTERNET"
            else -> "SERVER UNREACHABLE"
        }
        val netCol = when {
            checkingNetwork -> colCyberYellow
            serverOnline -> colCyberGreen
            else -> colCyberDanger
        }
        val netLbl = Label(netStatusStr, Label.LabelStyle(fontTiny, netCol))
        networkBadgeLabel = netLbl
        netTable.add(netLbl).left().expandX()

        val pingBtn = createCyberButton(
            text = "PING",
            width = 54f,
            height = 24f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = colCyberYellow,
            fontOverride = fontTiny
        ) {
            probeServerAsync()
        }
        netTable.add(pingBtn).width(54f).height(24f).padLeft(6f)
        topHeader.add(netTable).expandX().center()

        // Right: SFX, BGM, LOGOUT
        val headerBtns = Table()
        val sfxBtn = createCyberButton(
            text = if (audioService.isSoundEnabled) "SFX:ON" else "SFX:OFF",
            width = 74f,
            height = 32f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = Color.WHITE,
            fontOverride = fontTiny
        ) {
            audioService.toggleSound()
            rebuildUi()
        }
        val bgmBtn = createCyberButton(
            text = if (audioService.isMusicEnabled) "BGM:ON" else "BGM:OFF",
            width = 74f,
            height = 32f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = Color.WHITE,
            fontOverride = fontTiny
        ) {
            audioService.toggleMusic()
            rebuildUi()
        }
        val logoutBtn = createCyberButton(
            text = "LOGOUT",
            width = 80f,
            height = 32f,
            fillColor = Color(0.24f, 0.10f, 0.12f, 0.94f),
            borderColor = colCyberDanger,
            textColor = colCyberYellow,
            fontOverride = fontTiny
        ) {
            SqliteSessionStore.clearSession()
            activeSession = null
            currentStep = MenuStep.AUTH
            rebuildUi()
        }
        headerBtns.add(sfxBtn).width(74f).height(32f).padRight(6f)
        headerBtns.add(bgmBtn).width(74f).height(32f).padRight(6f)
        headerBtns.add(logoutBtn).width(80f).height(32f)
        topHeader.add(headerBtns).right()

        root.add(topHeader).fillX().height(52f).padBottom(8f).row()

        // ---------------------------------------------------------------------
        // MAIN 2-COLUMN BODY (Left: Operative Profile + Loadout, Right: Modes + Room Control)
        // ---------------------------------------------------------------------
        val bodyRow = Table()

        // === LEFT COLUMN (Width 370f) ===
        val leftCol = Table()

        // 1. Operative Profile Card
        val profileCard = Table()
        profileCard.background = createSciFiPanelDrawable(
            w = 370,
            h = 188,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        profileCard.pad(12f, 14f, 12f, 14f)

        val profHeader = Label("OPERATIVE DOSSIER", Label.LabelStyle(fontSmall, colCyberOrange))
        profileCard.add(profHeader).left().colspan(2).padBottom(6f).row()

        val avatarBox = Table()
        avatarBox.background = createSciFiPanelDrawable(
            w = 88,
            h = 88,
            bgColor = colDarkPanel,
            borderColor = colCyberYellow,
            borderWidth = 2,
            chamfer = 8,
            withTechTab = false
        )
        if (avatarTexture != null) {
            val avImg = Image(TextureRegionDrawable(avatarTexture!!)).apply {
                setScaling(Scaling.fit)
            }
            avatarBox.add(avImg).size(80f, 80f)
        } else {
            val fallbackInitials = Label(username.take(2).uppercase(), Label.LabelStyle(fontXLarge, colCyberYellow))
            avatarBox.add(fallbackInitials).center()
        }
        profileCard.add(avatarBox).size(88f, 88f).left().padRight(12f)

        val profDetails = Table()
        val nameLbl = Label(username.uppercase(), Label.LabelStyle(fontLarge, colCyberYellow))
        profDetails.add(nameLbl).left().padBottom(2f).row()

        val rankLbl = Label("RANK: CYBER VANGUARD IV", Label.LabelStyle(fontTiny, colCyberOrange))
        profDetails.add(rankLbl).left().padBottom(4f).row()

        // XP Sci-Fi Bar
        val xpTrack = Table()
        xpTrack.background = makeSolidDrawable(0.18f, 0.19f, 0.22f, 1f)
        val xpFill = Image(makeSolidDrawable(1.0f, 0.90f, 0.04f, 1f))
        xpTrack.add(xpFill).width(165f).height(8f).left().expandX()
        profDetails.add(xpTrack).width(220f).height(10f).left().padBottom(3f).row()

        val xpTxt = Label("XP: 2,450 / 3,000  •  JWT VERIFIED", Label.LabelStyle(fontTiny, Color.WHITE))
        profDetails.add(xpTxt).left()

        profileCard.add(profDetails).left().expandX().fillX().row()

        // Bottom stats strip inside profile card
        val statStrip = Table()
        statStrip.padTop(8f)
        statStrip.add(buildMiniStatPill("HP POOL", "100 HP", colCyberYellow)).padRight(8f)
        statStrip.add(buildMiniStatPill("AMMO", "∞ INF", colCyberOrange)).padRight(8f)
        statStrip.add(buildMiniStatPill("RESPAWN", "ROOF DROP", colCyberGreen))
        profileCard.add(statStrip).colspan(2).left().padTop(4f)

        leftCol.add(profileCard).width(370f).height(188f).padBottom(8f).row()

        // 2. Arsenal & Telemetry Card
        val loadoutCard = Table()
        loadoutCard.background = createSciFiPanelDrawable(
            w = 370,
            h = 195,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        loadoutCard.pad(12f, 14f, 12f, 14f)

        val loadTitle = Label("COMBAT ARSENAL & TELEMETRY", Label.LabelStyle(fontSmall, colCyberOrange))
        loadoutCard.add(loadTitle).left().padBottom(6f).row()

        val wRow = Table()
        wRow.add(buildWeaponChip("1: PISTOL", "15 DMG • FAST")).padRight(6f)
        wRow.add(buildWeaponChip("2: SHOTGUN", "18x5 PELLETS")).padRight(6f)
        wRow.add(buildWeaponChip("3: MG", "12 DMG • AUTO"))
        loadoutCard.add(wRow).left().padBottom(8f).row()

        val teleInfo1 = Label("• FIREBASE REALTIME DB SYNC: ACTIVE", Label.LabelStyle(fontTiny, colCyberYellow))
        val teleInfo2 = Label("• CLIENT SQLITE SESSION CACHE: READY", Label.LabelStyle(fontTiny, Color.WHITE))
        val sSub = Label(
            if (serverOnline) "REGION: GLOBAL ARENA • PING ${serverPingMs}ms"
            else "REGION: OFFLINE / SOLO PRACTICE READY",
            Label.LabelStyle(fontTiny, if (serverOnline) colCyberGreen else colCyberDanger)
        )
        sessionSubLabel = sSub

        loadoutCard.add(teleInfo1).left().padBottom(2f).row()
        loadoutCard.add(teleInfo2).left().padBottom(4f).row()
        loadoutCard.add(sSub).left()

        leftCol.add(loadoutCard).width(370f).height(195f)
        bodyRow.add(leftCol).width(370f).top().padRight(12f)

        // === RIGHT COLUMN (Width 614f): Mode Selector + Room Code & Waiting Room Controls ===
        val rightCol = Table()

        // 1. Tactical Mode Selection Card
        val modeCard = Table()
        modeCard.background = createSciFiPanelDrawable(
            w = 614,
            h = 205,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        modeCard.pad(12f, 16f, 12f, 16f)

        val modeHeaderRow = Table()
        val mTitle = Label("SELECT COMBAT MODE", Label.LabelStyle(fontMedium, colCyberYellow))
        val mSub = Label("SOLO OFFLINE  //  2P DUO ROOM  //  4P SQUAD ROOM", Label.LabelStyle(fontTiny, colCyberOrange))
        modeHeaderRow.add(mTitle).left().expandX()
        modeHeaderRow.add(mSub).right()
        modeCard.add(modeHeaderRow).fillX().padBottom(8f).row()

        // 3 Mode Cards side by side
        val modesGrid = Table()
        modesGrid.add(
            buildCyberModeCard(
                mode = GameMode.OFFLINE,
                badge = "OFFLINE READY",
                title = "SOLO PRACTICE",
                playersTag = "1 PLAYER + BOTS",
                desc = "Train offline anytime.\nNo internet required."
            )
        ).width(188f).height(138f).padRight(8f)

        modesGrid.add(
            buildCyberModeCard(
                mode = GameMode.DUO,
                badge = "2P ROOM CODE",
                title = "2P DUO ARENA",
                playersTag = "2 PLAYERS PVP",
                desc = "Create or join 2P room.\nWait for partner lobby."
            )
        ).width(188f).height(138f).padRight(8f)

        modesGrid.add(
            buildCyberModeCard(
                mode = GameMode.SQUAD,
                badge = "4P ROOM CODE",
                title = "4P SQUAD ARENA",
                playersTag = "4 PLAYERS PVP",
                desc = "Create or join 4P room.\nLive squad waiting room."
            )
        ).width(188f).height(138f)

        modeCard.add(modesGrid).left()
        rightCol.add(modeCard).width(614f).height(205f).padBottom(8f).row()

        // 2. Room Code & Match Launch Control Card
        val roomCard = Table()
        roomCard.background = createSciFiPanelDrawable(
            w = 614,
            h = 178,
            bgColor = colDarkChamber,
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = true,
            accentPipsColor = colCyberOrange
        )
        roomCard.pad(12f, 16f, 12f, 16f)

        if (selectedMode == GameMode.OFFLINE) {
            val soloHdr = Label("SOLO PRACTICE DEPLOYMENT // OFFLINE MODE", Label.LabelStyle(fontMedium, colCyberYellow))
            roomCard.add(soloHdr).left().padBottom(6f).row()

            val soloInfo = Label(
                "Deploy immediately into the 2D arena against AI patrols. Unlimited reserve ammo, roof drop respawn, and full weapon switching (1: Pistol, 2: Shotgun, 3: Machine Gun) enabled.",
                Label.LabelStyle(fontSmall, Color.WHITE)
            ).apply { wrap = true }
            roomCard.add(soloInfo).width(575f).left().padBottom(14f).row()

            val deploySoloBtn = createCyberButton(
                text = ">> LAUNCH SOLO ARENA MATCH <<",
                width = 580f,
                height = 50f,
                fillColor = colButtonPressed,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontLarge
            ) {
                launchMatch(roomCode = "", createRoom = false)
            }
            roomCard.add(deploySoloBtn).width(580f).height(50f).left()
        } else {
            val roomHdrRow = Table()
            val rTitle = Label(
                "${selectedMode.displayName.uppercase()} // ROOM LOBBY & MATCHMAKING",
                Label.LabelStyle(fontSmall, colCyberYellow)
            )
            val maxP = Label("CAPACITY: ${selectedMode.maxPlayers} OPERATIVES", Label.LabelStyle(fontTiny, colCyberOrange))
            roomHdrRow.add(rTitle).left().expandX()
            roomHdrRow.add(maxP).right()
            roomCard.add(roomHdrRow).fillX().padBottom(6f).row()

            // Room Code Input + CREATE NEW ROOM (Waiting Lobby) + JOIN BY CODE
            val codeControlRow = Table()
            val codeLabel = Label("ROOM CODE:", Label.LabelStyle(fontSmall, Color.WHITE))
            val tf = TextField("", createCyberTextFieldStyle()).apply {
                messageText = "ENTER CODE (E.G. K7X9M2)"
                maxLength = 8
            }
            roomCodeField = tf

            val joinCodeBtn = createCyberButton(
                text = "JOIN ROOM",
                width = 132f,
                height = 36f,
                fillColor = colButtonBg,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontSmall
            ) {
                val entered = tf.text.trim().uppercase()
                if (entered.length < 4) {
                    showPopupModal(
                        headerTag = "ROOM CODE REQUIRED",
                        title = "INVALID ROOM CODE",
                        message = "Please enter a valid 4–8 character Room Code shared by the room host to join an existing ${selectedMode.displayName} room.\n\nOr click 'CREATE NEW ROOM' to generate a new code and wait for players.",
                        isDanger = true,
                        primaryText = "CREATE NEW ROOM",
                        onPrimary = {
                            val newCode = GameConfig.generateRoomCode()
                            tf.text = newCode
                            tryEnterWaitingRoom(roomCode = newCode, createRoom = true)
                        },
                        secondaryText = "OK",
                        onSecondary = {}
                    )
                } else {
                    tryEnterWaitingRoom(roomCode = entered, createRoom = false)
                }
            }

            val createWaitBtn = createCyberButton(
                text = "+ CREATE NEW ROOM",
                width = 176f,
                height = 36f,
                fillColor = colButtonPressed,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontSmall
            ) {
                val newCode = GameConfig.generateRoomCode()
                tf.text = newCode
                tryEnterWaitingRoom(roomCode = newCode, createRoom = true)
            }

            codeControlRow.add(codeLabel).left().padRight(8f)
            codeControlRow.add(tf).width(172f).height(36f).padRight(8f)
            codeControlRow.add(joinCodeBtn).width(132f).height(36f).padRight(8f)
            codeControlRow.add(createWaitBtn).width(176f).height(36f)
            roomCard.add(codeControlRow).left().padBottom(8f).row()

            // Status hint row
            val statusLbl = Label(
                "CREATE A ROOM TO OPEN THE WAITING FOR PLAYERS LOBBY, OR QUICK DEPLOY NOW",
                Label.LabelStyle(fontTiny, colTextDim)
            )
            lobbyStatusLabel = statusLbl
            roomCard.add(statusLbl).left().padBottom(8f).row()

            // Bottom Action Row: [ OPEN WAITING ROOM LOBBY ] and [ QUICK DEPLOY TO ARENA ]
            val deployRow = Table()
            val waitLobbyBtn = createCyberButton(
                text = "CREATE ROOM & WAIT FOR PLAYERS",
                width = 305f,
                height = 44f,
                fillColor = colButtonPressed,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontMedium
            ) {
                val code = tf.text.trim().uppercase().ifEmpty { GameConfig.generateRoomCode() }
                tf.text = code
                tryEnterWaitingRoom(roomCode = code, createRoom = true)
            }

            val quickDeployBtn = createCyberButton(
                text = "QUICK DEPLOY (${selectedMode.name})",
                width = 263f,
                height = 44f,
                fillColor = colButtonBg,
                borderColor = colCyberOrange,
                textColor = Color.WHITE,
                fontOverride = fontMedium
            ) {
                val entered = tf.text.trim().uppercase()
                verifyMultiplayerThen("start ${selectedMode.displayName}") {
                    launchMatch(roomCode = entered, createRoom = entered.isEmpty())
                }
            }

            deployRow.add(waitLobbyBtn).width(305f).height(44f).padRight(12f)
            deployRow.add(quickDeployBtn).width(263f).height(44f)
            roomCard.add(deployRow).left()
        }

        rightCol.add(roomCard).width(614f).height(178f)
        bodyRow.add(rightCol).width(614f).top()

        root.add(bodyRow).expand().fill()
        parent.add(root).expand().fill()
    }

    private fun buildMiniStatPill(tag: String, value: String, valueCol: Color): Table {
        val pill = Table()
        pill.background = createSciFiPanelDrawable(
            w = 106,
            h = 34,
            bgColor = colDarkPanel,
            borderColor = colCyberOrangeDark,
            borderWidth = 1,
            chamfer = 5,
            withTechTab = false
        )
        pill.pad(3f, 6f, 3f, 6f)
        val tLbl = Label("$tag: ", Label.LabelStyle(fontTiny, colTextDim))
        val vLbl = Label(value, Label.LabelStyle(fontTiny, valueCol))
        pill.add(tLbl).left()
        pill.add(vLbl).left()
        return pill
    }

    private fun buildWeaponChip(title: String, spec: String): Table {
        val chip = Table()
        chip.background = createSciFiPanelDrawable(
            w = 108,
            h = 46,
            bgColor = colDarkPanel,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false
        )
        chip.pad(4f, 6f, 4f, 6f)
        val t = Label(title, Label.LabelStyle(fontTiny, colCyberYellow))
        val s = Label(spec, Label.LabelStyle(fontTiny, Color.WHITE))
        chip.add(t).left().row()
        chip.add(s).left()
        return chip
    }

    private fun buildCyberModeCard(
        mode: GameMode,
        badge: String,
        title: String,
        playersTag: String,
        desc: String
    ): Table {
        val selected = (selectedMode == mode)
        val borderCol = if (selected) colCyberYellow else colCyberOrangeDark
        val bgCol = if (selected) Color(0.24f, 0.16f, 0.03f, 0.96f) else colDarkPanel

        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = 188,
            h = 138,
            bgColor = bgCol,
            borderColor = borderCol,
            borderWidth = if (selected) 3 else 2,
            chamfer = 10,
            withTechTab = selected,
            accentPipsColor = if (selected) colCyberYellow else colCyberOrange
        )
        card.pad(8f, 10f, 8f, 10f)

        val badgeLbl = Label(
            if (selected) ">> $badge <<" else badge,
            Label.LabelStyle(fontTiny, if (selected) colCyberYellow else colCyberOrange)
        )
        card.add(badgeLbl).left().padBottom(3f).row()

        val titleLbl = Label(
            title,
            Label.LabelStyle(fontSmall, if (selected) Color.WHITE else colTextDim)
        )
        card.add(titleLbl).left().padBottom(2f).row()

        val pLbl = Label(playersTag, Label.LabelStyle(fontTiny, colCyberYellow))
        card.add(pLbl).left().padBottom(4f).row()

        val dLbl = Label(desc, Label.LabelStyle(fontTiny, colTextDim)).apply {
            wrap = true
        }
        card.add(dLbl).width(166f).left().expandY().top().row()

        val selStatus = Label(
            if (selected) "[ ACTIVE MODE ]" else "[ CLICK TO SELECT ]",
            Label.LabelStyle(fontTiny, if (selected) colCyberGreen else colCyberGrey)
        )
        card.add(selStatus).left()

        card.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                selectedMode = mode
                rebuildUi()
            }
        })

        return card
    }

    // =========================================================================
    // STEP 3: "WAITING FOR PLAYERS" ROOM LOBBY MENU (DUO 2P / SQUAD 4P)
    // =========================================================================
    private fun tryEnterWaitingRoom(roomCode: String, createRoom: Boolean) {
        verifyMultiplayerThen(if (createRoom) "create Room [$roomCode]" else "join Room [$roomCode]") {
            startWaitingRoomSession(roomCode, createRoom)
        }
    }

    private fun startWaitingRoomSession(roomCode: String, createRoom: Boolean) {
        stopWaitingRoomClient()

        val cleanCode = roomCode.trim().uppercase().ifEmpty { GameConfig.generateRoomCode() }
        waitingRoomCode = cleanCode
        waitingCreateRoom = createRoom
        waitingConnectElapsed = 0f
        waitingHeartbeatTimer = 0f
        waitingCountdown = -1f
        savedLoginResponse = null
        waitingMembers.clear()

        val username = activeSession?.username ?: "Operative"
        // Add local host/player immediately into slot 1
        waitingMembers[0] = WaitingRoomMember(
            id = 0,
            name = username,
            isLocal = true
        )

        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val client = GameClient(host, NetworkConfig.TCP_PORT, NetworkConfig.UDP_PORT)
        lobbyClient = client
        client.connect(
            name = username,
            roomCode = cleanCode,
            gameMode = selectedMode.name,
            createRoom = createRoom,
            initialSessionToken = activeSession?.sessionToken ?: "",
            timeoutMs = 4000
        )

        currentStep = MenuStep.WAITING_ROOM
        rebuildUi()
    }

    private fun stopWaitingRoomClient() {
        try {
            lobbyClient?.close()
        } catch (_: Exception) {
        }
        lobbyClient = null
        savedLoginResponse = null
        waitingMembers.clear()
        waitingCountdown = -1f
    }

    private fun buildWaitingRoomMenu(parent: Table) {
        val root = Table()
        root.setFillParent(true)
        root.pad(12f, 16f, 12f, 16f)

        // 1. Top Header Bar: Room Code + Mode + Copy Button + Leave Button
        val topBar = Table()
        topBar.background = createSciFiPanelDrawable(
            w = 992,
            h = 64,
            bgColor = colDarkChamber,
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = true,
            accentPipsColor = colCyberOrange
        )
        topBar.pad(8f, 18f, 8f, 18f)

        val titleCol = Table()
        val roomTag = Label(
            "/// ${selectedMode.displayName.uppercase()} // WAITING FOR PLAYERS LOBBY ///",
            Label.LabelStyle(fontTiny, colCyberOrange)
        )
        val mainWaitTitle = Label("TACTICAL SQUAD STAGING ROOM", Label.LabelStyle(fontLarge, colCyberYellow))
        titleCol.add(roomTag).left().row()
        titleCol.add(mainWaitTitle).left()
        topBar.add(titleCol).left().expandX()

        // Prominent Room Code Pill + Copy button
        val codeBadge = Table()
        codeBadge.background = createSciFiPanelDrawable(
            w = 260,
            h = 44,
            bgColor = Color(0.22f, 0.15f, 0.03f, 0.96f),
            borderColor = colCyberYellow,
            borderWidth = 2,
            chamfer = 8,
            withTechTab = false
        )
        codeBadge.pad(4f, 12f, 4f, 12f)
        val cLbl = Label("ROOM CODE: $waitingRoomCode", Label.LabelStyle(fontMedium, colCyberYellow))
        waitingCodeValueLabel = cLbl
        codeBadge.add(cLbl).center()
        topBar.add(codeBadge).width(260f).height(44f).padRight(10f)

        val copyBtn = createCyberButton(
            text = "COPY CODE",
            width = 118f,
            height = 42f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontSmall
        ) {
            Gdx.app.clipboard.contents = waitingRoomCode
            waitingStatusBannerLabel?.setText("✓ ROOM CODE [$waitingRoomCode] COPIED TO CLIPBOARD! SHARE WITH TEAMMATES!")
            waitingStatusBannerLabel?.color = colCyberGreen
        }
        topBar.add(copyBtn).width(118f).height(42f).padRight(10f)

        val leaveBtn = createCyberButton(
            text = "LEAVE ROOM",
            width = 122f,
            height = 42f,
            fillColor = Color(0.26f, 0.10f, 0.12f, 0.95f),
            borderColor = colCyberDanger,
            textColor = Color.WHITE,
            fontOverride = fontSmall
        ) {
            stopWaitingRoomClient()
            currentStep = MenuStep.LOBBY
            rebuildUi()
        }
        topBar.add(leaveBtn).width(122f).height(42f)

        root.add(topBar).fillX().height(64f).padBottom(10f).row()

        // 2. Center Body: Player Slots Grid (2 Slots for DUO, 4 Slots for SQUAD)
        val bodyCard = Table()
        bodyCard.background = createSciFiPanelDrawable(
            w = 992,
            h = 285,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        bodyCard.pad(14f, 18f, 14f, 18f)

        val subHeaderRow = Table()
        val countLbl = Label(
            "CONNECTED OPERATIVES: ${waitingMembers.size} / ${selectedMode.maxPlayers}",
            Label.LabelStyle(fontMedium, Color.WHITE)
        )
        waitingCountLabel = countLbl

        val statusBanner = Label(
            "WAITING FOR OTHER PLAYERS TO JOIN ROOM [$waitingRoomCode]...",
            Label.LabelStyle(fontSmall, colCyberYellow)
        )
        waitingStatusBannerLabel = statusBanner

        subHeaderRow.add(countLbl).left().expandX()
        subHeaderRow.add(statusBanner).right()
        bodyCard.add(subHeaderRow).fillX().padBottom(12f).row()

        val slotsGrid = Table()
        waitingSlotsContainer = slotsGrid
        refreshWaitingSlotsGrid()
        bodyCard.add(slotsGrid).expand().fill().row()

        root.add(bodyCard).fillX().height(285f).padBottom(10f).row()

        // 3. Bottom Action Bar: Auto-Start Toggle + Start Match Now
        val bottomBar = Table()
        bottomBar.background = createSciFiPanelDrawable(
            w = 992,
            h = 66,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 10,
            withTechTab = false,
            accentPipsColor = colCyberYellow
        )
        bottomBar.pad(8f, 18f, 8f, 18f)

        val autoStartBtn = createCyberButton(
            text = if (waitingAutoStart) "AUTO-START WHEN FULL: ON" else "AUTO-START WHEN FULL: OFF",
            width = 250f,
            height = 46f,
            fillColor = colButtonBg,
            borderColor = if (waitingAutoStart) colCyberGreen else colCyberGrey,
            textColor = if (waitingAutoStart) colCyberGreen else colTextDim,
            fontOverride = fontSmall,
            labelHook = { waitingAutoStartBtnLabel = it }
        ) {
            waitingAutoStart = !waitingAutoStart
            waitingAutoStartBtnLabel?.setText(
                if (waitingAutoStart) "AUTO-START WHEN FULL: ON" else "AUTO-START WHEN FULL: OFF"
            )
            waitingAutoStartBtnLabel?.color = if (waitingAutoStart) colCyberGreen else colTextDim
        }
        bottomBar.add(autoStartBtn).width(250f).height(46f).left()

        val infoTip = Label(
            "Players can join using code [$waitingRoomCode] now or during the match.",
            Label.LabelStyle(fontTiny, colTextDim)
        )
        bottomBar.add(infoTip).expandX().center()

        val startNowBtn = createCyberButton(
            text = ">> DEPLOY TO ARENA NOW <<",
            width = 310f,
            height = 48f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontMedium
        ) {
            deployFromWaitingRoom()
        }
        bottomBar.add(startNowBtn).width(310f).height(48f).right()

        root.add(bottomBar).fillX().height(66f)
        parent.add(root).expand().fill()
    }

    private fun refreshWaitingSlotsGrid() {
        val grid = waitingSlotsContainer ?: return
        grid.clearChildren()

        val maxSlots = selectedMode.maxPlayers.coerceAtLeast(2)
        val memberList = waitingMembers.values.toList()
        val slotW = if (maxSlots <= 2) 462f else 228f
        val slotH = 205f

        for (slotIdx in 0 until maxSlots) {
            val member = memberList.getOrNull(slotIdx)
            val slotCard = Table()
            if (member != null) {
                val borderCol = if (member.isLocal) colCyberYellow else colCyberGreen
                val fillCol = if (member.isLocal) Color(0.22f, 0.16f, 0.04f, 0.96f) else Color(0.08f, 0.20f, 0.12f, 0.95f)
                slotCard.background = createSciFiPanelDrawable(
                    w = slotW.toInt(),
                    h = slotH.toInt(),
                    bgColor = fillCol,
                    borderColor = borderCol,
                    borderWidth = 3,
                    chamfer = 10,
                    withTechTab = true,
                    accentPipsColor = borderCol
                )
                slotCard.pad(10f, 12f, 10f, 12f)

                val roleTag = if (slotIdx == 0) "SLOT ${slotIdx + 1} // ROOM HOST" else "SLOT ${slotIdx + 1} // SQUAD OPERATIVE"
                val roleLbl = Label(roleTag, Label.LabelStyle(fontTiny, colCyberOrange))
                slotCard.add(roleLbl).left().padBottom(6f).row()

                val avBox = Table()
                avBox.background = createSciFiPanelDrawable(
                    w = 64,
                    h = 64,
                    bgColor = colDarkChamber,
                    borderColor = borderCol,
                    borderWidth = 2,
                    chamfer = 6,
                    withTechTab = false
                )
                if (avatarTexture != null) {
                    val img = Image(TextureRegionDrawable(avatarTexture!!)).apply {
                        setScaling(Scaling.fit)
                    }
                    avBox.add(img).size(56f, 56f)
                } else {
                    avBox.add(Label(member.name.take(2).uppercase(), Label.LabelStyle(fontLarge, colCyberYellow)))
                }
                slotCard.add(avBox).size(64f, 64f).left().padBottom(8f).row()

                val youSuffix = if (member.isLocal) " (YOU)" else ""
                val pName = Label("${member.name.uppercase()}$youSuffix", Label.LabelStyle(fontMedium, Color.WHITE))
                slotCard.add(pName).left().padBottom(4f).row()

                val idStr = if (member.id > 0) "ID #${member.id}" else "CONNECTING..."
                val readyBadge = Label("● READY  •  $idStr", Label.LabelStyle(fontSmall, colCyberGreen))
                slotCard.add(readyBadge).left().expandY().bottom()
            } else {
                val dots = ".".repeat(waitingDotCount)
                slotCard.background = createSciFiPanelDrawable(
                    w = slotW.toInt(),
                    h = slotH.toInt(),
                    bgColor = Color(0.09f, 0.10f, 0.14f, 0.88f),
                    borderColor = colCyberGrey,
                    borderWidth = 2,
                    chamfer = 10,
                    withTechTab = false,
                    accentPipsColor = colCyberGrey
                )
                slotCard.pad(10f, 12f, 10f, 12f)

                val slotTag = Label("SLOT ${slotIdx + 1} // OPEN SLOT", Label.LabelStyle(fontTiny, colCyberGrey))
                slotCard.add(slotTag).left().padBottom(14f).row()

                val waitIcon = Label("[ WAITING$dots ]", Label.LabelStyle(fontMedium, colCyberYellow))
                slotCard.add(waitIcon).center().expandX().padBottom(10f).row()

                val inviteHint = Label(
                    "Share Room Code\n[$waitingRoomCode]\nwith your teammate",
                    Label.LabelStyle(fontTiny, colTextDim)
                ).apply {
                    setAlignment(Align.center)
                }
                slotCard.add(inviteHint).center().expandY().top()
            }

            grid.add(slotCard).width(slotW).height(slotH)
            if (slotIdx < maxSlots - 1) {
                grid.add().width(10f)
            }
        }
    }

    private fun updateWaitingRoomTick(delta: Float) {
        val client = lobbyClient ?: return

        // Animate waiting dots
        waitingDotTimer += delta
        if (waitingDotTimer >= 0.45f) {
            waitingDotTimer = 0f
            waitingDotCount = (waitingDotCount % 3) + 1
            refreshWaitingSlotsGrid()
        }

        // Check if connection failed
        waitingConnectElapsed += delta
        if (client.connectionFailed || (!client.isConnected && waitingConnectElapsed > 4.5f)) {
            val errDetail = client.lastError.ifBlank { "Multiplayer server did not respond in time." }
            stopWaitingRoomClient()
            currentStep = MenuStep.LOBBY
            rebuildUi()
            showPopupModal(
                headerTag = "ROOM CONNECTION ERROR",
                title = "COULD NOT CONNECT TO ROOM [$waitingRoomCode]",
                message = "Connection to the multiplayer game server failed ($errDetail).\n\n" +
                    "Please check your internet connection and verify the server is online, or play Solo Practice mode.",
                isDanger = true,
                primaryText = "RETRY ROOM",
                onPrimary = {
                    tryEnterWaitingRoom(waitingRoomCode, waitingCreateRoom)
                },
                secondaryText = "PLAY SOLO OFFLINE",
                onSecondary = {
                    selectedMode = GameMode.OFFLINE
                    rebuildUi()
                    launchMatch(roomCode = "", createRoom = false)
                },
                tertiaryText = "CLOSE",
                onTertiary = {}
            )
            return
        }

        // Send periodic state heartbeat while in waiting room so all room peers see our playerName
        if (client.isConnected && client.playerId >= 0) {
            waitingHeartbeatTimer += delta
            if (waitingHeartbeatTimer >= 0.8f) {
                waitingHeartbeatTimer = 0f
                client.sendUdpRegister(client.playerId)
                client.sendState(
                    x = 12.47f,
                    y = 5.34f,
                    cos = 0f,
                    sin = 101f,
                    health = 100f
                )
            }
        }

        // Poll packets from lobbyClient
        var slotsChanged = false
        while (client.packetQueue.isNotEmpty()) {
            when (val pkt = client.packetQueue.poll()) {
                is LoginResponsePacket -> {
                    savedLoginResponse = pkt
                    if (pkt.roomCode.isNotBlank()) {
                        waitingRoomCode = pkt.roomCode.trim().uppercase()
                        waitingCodeValueLabel?.setText("ROOM CODE: $waitingRoomCode")
                    }
                    val localEntry = waitingMembers.remove(0) ?: WaitingRoomMember(
                        id = pkt.assignedId,
                        name = activeSession?.username ?: "Operative",
                        isLocal = true,
                        x = pkt.spawnX,
                        y = pkt.spawnY
                    )
                    waitingMembers[pkt.assignedId] = localEntry.copy(
                        id = pkt.assignedId,
                        x = pkt.spawnX,
                        y = pkt.spawnY
                    )
                    slotsChanged = true
                }

                is PlayerConnectedPacket -> {
                    if (pkt.roomCode.isNotBlank() && waitingRoomCode.isNotBlank() &&
                        !pkt.roomCode.equals(waitingRoomCode, ignoreCase = true)
                    ) {
                        continue
                    }
                    if (pkt.id != client.playerId && pkt.id > 0) {
                        val existing = waitingMembers[pkt.id]
                        val pName = pkt.playerName.ifBlank { existing?.name ?: "Operative #${pkt.id}" }
                        waitingMembers[pkt.id] = WaitingRoomMember(
                            id = pkt.id,
                            name = pName,
                            isLocal = false,
                            x = pkt.x,
                            y = pkt.y
                        )
                        audioService.play(SoundType.CLICK, volumeModifier = 0.7f)
                        slotsChanged = true
                    }
                }

                is PlayerDisconnectedPacket -> {
                    if (waitingMembers.remove(pkt.id) != null) {
                        slotsChanged = true
                    }
                }

                is PlayerStatePacket -> {
                    if (pkt.roomCode.isNotBlank() && waitingRoomCode.isNotBlank() &&
                        !pkt.roomCode.equals(waitingRoomCode, ignoreCase = true)
                    ) {
                        continue
                    }
                    if (pkt.id != client.playerId && pkt.id > 0) {
                        val existing = waitingMembers[pkt.id]
                        val updatedName = pkt.playerName.ifBlank { existing?.name ?: "Operative #${pkt.id}" }
                        if (existing == null || existing.name != updatedName) {
                            waitingMembers[pkt.id] = WaitingRoomMember(
                                id = pkt.id,
                                name = updatedName,
                                isLocal = false,
                                x = pkt.x,
                                y = pkt.y
                            )
                            slotsChanged = true
                        }
                    }
                }
            }
        }

        val currentCount = waitingMembers.size
        val maxPlayers = selectedMode.maxPlayers
        waitingCountLabel?.setText("CONNECTED OPERATIVES: $currentCount / $maxPlayers")

        if (currentCount >= maxPlayers) {
            if (waitingAutoStart) {
                if (waitingCountdown < 0f) {
                    waitingCountdown = 3.0f
                    audioService.play(SoundType.RELOAD, volumeModifier = 0.7f)
                }
                waitingCountdown -= delta
                val secLeft = max(1, waitingCountdown.toInt() + 1)
                waitingStatusBannerLabel?.setText("✓ ALL OPERATIVES READY ($currentCount/$maxPlayers)! DEPLOYING IN ${secLeft}s...")
                waitingStatusBannerLabel?.color = colCyberGreen
                if (waitingCountdown <= 0f) {
                    deployFromWaitingRoom()
                    return
                }
            } else {
                waitingStatusBannerLabel?.setText("✓ ROOM FULL ($currentCount/$maxPlayers)! CLICK 'DEPLOY TO ARENA NOW' TO START!")
                waitingStatusBannerLabel?.color = colCyberGreen
            }
        } else {
            waitingCountdown = -1f
            val needed = maxPlayers - currentCount
            val dots = ".".repeat(waitingDotCount)
            waitingStatusBannerLabel?.setText(
                "WAITING FOR $needed MORE ${if (needed == 1) "PLAYER" else "PLAYERS"} TO JOIN [$waitingRoomCode]$dots"
            )
            waitingStatusBannerLabel?.color = colCyberYellow
        }

        if (slotsChanged) {
            refreshWaitingSlotsGrid()
        }
    }

    private fun deployFromWaitingRoom() {
        val client = lobbyClient
        if (client != null && client.isConnected) {
            // Re-queue LoginResponsePacket and PlayerConnectedPackets so NetworkSystem in GameScreen
            // immediately initializes our network ID and spawns all room teammates on frame 1!
            savedLoginResponse?.let { client.packetQueue.add(it) }
            waitingMembers.values.filter { !it.isLocal && it.id > 0 }.forEach { member ->
                val connPkt = PlayerConnectedPacket.newBuilder()
                    .setId(member.id)
                    .setX(member.x)
                    .setY(member.y)
                    .setRoomCode(waitingRoomCode)
                    .setPlayerName(member.name)
                    .build()
                client.packetQueue.add(connPkt)
            }
        }

        lobbyClient = null // Transfer ownership to GameScreen
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val session = activeSession
        val config = GameConfig(
            mode = selectedMode,
            playerName = session?.username ?: "Operative",
            serverHost = host,
            roomCode = waitingRoomCode,
            createRoom = waitingCreateRoom,
            jwtToken = session?.jwtToken ?: "",
            sessionToken = session?.sessionToken ?: "",
            preConnectedClient = client
        )
        game.startGame(config)
    }

    // =========================================================================
    // CYBER-YELLOW BUTTON BUILDER & NETWORK VERIFICATION
    // =========================================================================
    private fun createCyberButton(
        text: String,
        width: Float,
        height: Float,
        fillColor: Color,
        borderColor: Color,
        textColor: Color,
        fontOverride: BitmapFont = fontSmall,
        labelHook: ((Label) -> Unit)? = null,
        onClick: () -> Unit
    ): Stack {
        val stack = Stack()
        val normalBg = createSciFiPanelDrawable(
            w = width.toInt(),
            h = height.toInt(),
            bgColor = fillColor,
            borderColor = borderColor,
            borderWidth = 2,
            chamfer = 8,
            withTechTab = false,
            accentPipsColor = borderColor
        )
        val pressedBg = createSciFiPanelDrawable(
            w = width.toInt(),
            h = height.toInt(),
            bgColor = Color(0.38f, 0.26f, 0.04f, 0.98f),
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 8,
            withTechTab = false,
            accentPipsColor = colCyberYellow
        )

        val bgImage = Image(normalBg)
        val lbl = Label(text, Label.LabelStyle(fontOverride, textColor)).apply {
            setAlignment(Align.center)
        }
        labelHook?.invoke(lbl)

        stack.add(bgImage)
        stack.add(lbl)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.55f)
                bgImage.drawable = pressedBg
                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                bgImage.drawable = normalBg
                onClick()
            }

            override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                if (pointer == -1) {
                    bgImage.drawable = pressedBg
                }
            }

            override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                if (pointer == -1) {
                    bgImage.drawable = normalBg
                }
            }
        })

        return stack
    }

    /**
     * Verifies both general internet connectivity and multiplayer game server reachability
     * before allowing the user to start or join a DUO or SQUAD match.
     * Shows a Sci-Fi Network Error Popup Modal if offline.
     */
    private fun verifyMultiplayerThen(actionDescription: String, onVerified: () -> Unit) {
        if (selectedMode == GameMode.OFFLINE) {
            onVerified()
            return
        }

        lobbyStatusLabel?.setText("VERIFYING MULTIPLAYER NETWORK LINK...")
        lobbyStatusLabel?.color = colCyberYellow

        Thread {
            val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
            val start = System.currentTimeMillis()
            val serverOk = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, NetworkConfig.TCP_PORT), 1500)
                }
                true
            } catch (_: Exception) {
                false
            }
            val ping = System.currentTimeMillis() - start

            val netOk = if (serverOk) {
                true
            } else {
                // Check if internet itself is reachable (DNS / public endpoint)
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
                internetAvailable = netOk
                serverOnline = serverOk
                serverPingMs = ping
                updateNetworkHeaderLabels()

                if (serverOk) {
                    onVerified()
                } else {
                    showNetworkUnavailablePopup(
                        attemptedAction = actionDescription,
                        onRetry = { verifyMultiplayerThen(actionDescription, onVerified) }
                    )
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun probeServerAsync(forceCallback: (() -> Unit)? = null) {
        if (checkingNetwork) return
        checkingNetwork = true
        updateNetworkHeaderLabels()

        Thread {
            val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
            val start = System.currentTimeMillis()
            val serverOk = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, NetworkConfig.TCP_PORT), 1500)
                }
                true
            } catch (_: Exception) {
                false
            }
            val ping = System.currentTimeMillis() - start

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
                checkingNetwork = false
                internetAvailable = netOk
                serverOnline = serverOk
                serverPingMs = ping
                updateNetworkHeaderLabels()
                forceCallback?.invoke()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun updateNetworkHeaderLabels() {
        networkBadgeLabel?.let { lbl ->
            when {
                checkingNetwork -> {
                    lbl.setText("CHECKING NETWORK...")
                    lbl.color = colCyberYellow
                }
                serverOnline -> {
                    lbl.setText("SERVER ONLINE • ${serverPingMs}ms")
                    lbl.color = colCyberGreen
                }
                !internetAvailable -> {
                    lbl.setText("OFFLINE • NO INTERNET")
                    lbl.color = colCyberDanger
                }
                else -> {
                    lbl.setText("SERVER UNREACHABLE")
                    lbl.color = colCyberDanger
                }
            }
        }
        sessionSubLabel?.let { sLbl ->
            if (serverOnline) {
                sLbl.setText("REGION: GLOBAL ARENA • PING ${serverPingMs}ms")
                sLbl.color = colCyberGreen
            } else {
                sLbl.setText("REGION: OFFLINE / SOLO PRACTICE READY")
                sLbl.color = colCyberDanger
            }
        }
    }

    private fun launchMatch(roomCode: String = "", createRoom: Boolean = false) {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val session = activeSession
        val config = GameConfig(
            mode = selectedMode,
            playerName = session?.username ?: "Operative",
            serverHost = host,
            roomCode = roomCode.trim().uppercase(),
            createRoom = createRoom,
            jwtToken = session?.jwtToken ?: "",
            sessionToken = session?.sessionToken ?: ""
        )
        game.startGame(config)
    }

    override fun render(delta: Float) {
        if (currentStep == MenuStep.WAITING_ROOM) {
            updateWaitingRoomTick(delta)
        }
        ScreenUtils.clear(0.04f, 0.05f, 0.08f, 1f)
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
