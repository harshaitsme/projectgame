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
import com.badlogic.gdx.scenes.scene2d.Touchable
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
import io.github.shootgame.profile.FirebaseProfileService
import io.github.shootgame.profile.PlayerProfile
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
    private var customRounds: Int = 2
    private var activeSession: AuthSessionRecord? = null
    private var authBusy = false

    // Live connectivity state
    @Volatile private var internetAvailable = true
    @Volatile private var serverOnline = false
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
    private var roomCodeField: TextField? = null
    private var networkBadgeLabel: Label? = null

    // Waiting Room UI references
    private var waitingCodeValueLabel: Label? = null
    private var waitingStatusBannerLabel: Label? = null
    private var waitingCountLabel: Label? = null
    private var waitingSlotsContainer: Table? = null
    private var waitingAutoStartBtnLabel: Label? = null
    private var waitingStartBtnLabel: Label? = null

    // Player Profile (Score & XP from Firebase)
    private var activeProfile: PlayerProfile? = null

    // Modal Popup overlay reference
    private var activePopupOverlay: Table? = null

    // Textures & Fonts
    private var bgFrameTexture: Texture? = null
    private var avatarTexture: Texture? = null
    private val generatedTextures = mutableListOf<Texture>()

    private var fontGenerator: FreeTypeFontGenerator? = null
    private val generatedFonts = mutableListOf<BitmapFont>()
    private lateinit var fontTiny: BitmapFont
    private lateinit var fontSmall: BitmapFont
    private lateinit var fontMedium: BitmapFont
    private lateinit var fontLarge: BitmapFont
    private lateinit var fontXLarge: BitmapFont

    // Sci-Fi Cyber-Yellow & Orange Palette (Matched to Gameplay HUD)
    private val colCyberOrange = Color(1.0f, 0.58f, 0.02f, 1f)
    private val colCyberOrangeDark = Color(0.75f, 0.36f, 0.01f, 1f)
    private val colCyberYellow = Color(1.0f, 0.90f, 0.04f, 1f)
    private val colCyberCyan = Color(0.18f, 0.88f, 1.0f, 1f)
    private val colCyberDanger = Color(0.96f, 0.22f, 0.26f, 1f)
    private val colCyberGreen = Color(0.24f, 0.96f, 0.48f, 1f)
    private val colCyberGrey = Color(0.38f, 0.40f, 0.44f, 1f)
    private val colDarkChamber = Color(0.08f, 0.09f, 0.12f, 0.94f)
    private val colDarkPanel = Color(0.12f, 0.14f, 0.18f, 0.92f)
    private val colDarkTrack = Color(0.16f, 0.18f, 0.22f, 0.98f)
    private val colButtonBg = Color(0.13f, 0.15f, 0.20f, 0.94f)
    private val colButtonPressed = Color(0.34f, 0.22f, 0.04f, 0.96f)
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
            activeProfile = FirebaseProfileService.getCachedProfile(cached.username)
            FirebaseProfileService.fetchProfile(cached.username) { profile ->
                activeProfile = profile
                if (currentStep == MenuStep.LOBBY) {
                    rebuildUi()
                }
            }
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

                fontTiny = makeFont(13, 1.2f, 1f)
                fontSmall = makeFont(16, 1.5f, 2f)
                fontMedium = makeFont(21, 1.8f, 2f)
                fontLarge = makeFont(28, 2.2f, 3f)
                fontXLarge = makeFont(36, 2.6f, 3f)
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
    }

    private fun loadMenuAssets() {
        bgFrameTexture = loadTexSafely("graphics/sp_menu_frame_bg.png")
        avatarTexture = loadTexSafely("graphics/sp_avatar.png")
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

        val tabH = if (withTechTab && height >= 48) 5 else 0
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

        // Diagonal hazard chevrons (///)
        if (width >= 90 && height >= 36) {
            val chevStart = width - ch - 36
            val chevY = baseY + 4
            for (i in 0 until 3) {
                val sx = chevStart + i * 8
                pm.setColor(if (i < 2) accentPipsColor else colCyberGrey)
                pm.drawLine(sx, chevY + 4, sx + 4, chevY)
                pm.drawLine(sx + 1, chevY + 4, sx + 5, chevY)
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
            h = 38,
            bgColor = colDarkTrack,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false
        ).apply {
            leftWidth = 12f
            rightWidth = 12f
            topHeight = 4f
            bottomHeight = 4f
        }

        val focusedBg = createSciFiPanelDrawable(
            w = 240,
            h = 38,
            bgColor = Color(0.20f, 0.17f, 0.07f, 0.98f),
            borderColor = colCyberYellow,
            borderWidth = 2,
            chamfer = 6,
            withTechTab = false
        ).apply {
            leftWidth = 12f
            rightWidth = 12f
            topHeight = 4f
            bottomHeight = 4f
        }

        return TextField.TextFieldStyle().apply {
            font = fontSmall
            fontColor = colCyberYellow
            focusedFontColor = Color.WHITE
            messageFont = fontSmall
            messageFontColor = colCyberGrey
            background = bg
            focusedBackground = focusedBg
            cursor = makeSolidDrawable(1f, 0.90f, 0.04f, 1f).apply { minWidth = 2f }
            selection = makeSolidDrawable(1f, 0.58f, 0.02f, 0.45f)
        }
    }

    private fun rebuildUi() {
        stage.clear()
        activePopupOverlay = null

        val rootStack = Stack()
        rootStack.setFillParent(true)

        if (bgFrameTexture != null) {
            val bgImg = Image(TextureRegionDrawable(bgFrameTexture!!)).apply {
                setScaling(Scaling.stretch)
            }
            rootStack.add(bgImg)
            rootStack.add(Image(makeSolidDrawable(0.04f, 0.05f, 0.08f, 0.58f)))
        } else {
            val fallbackBg = Table().apply {
                background = makeSolidDrawable(0.05f, 0.06f, 0.09f, 1f)
            }
            rootStack.add(fallbackBg)
        }

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
    // CLEAN MODAL POPUP MENU (Network Errors, Room Code Alerts, Auth Errors)
    // =========================================================================
    private fun showPopupModal(
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

        val borderCol = if (isDanger) colCyberDanger else colCyberOrange

        val backdrop = Table()
        backdrop.setFillParent(true)
        backdrop.background = makeSolidDrawable(0.02f, 0.02f, 0.04f, 0.82f)
        backdrop.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {}
        })

        val modalW = 500f
        val modalH = 230f
        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = modalW.toInt(),
            h = modalH.toInt(),
            bgColor = Color(0.08f, 0.09f, 0.12f, 0.98f),
            borderColor = borderCol,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true,
            accentPipsColor = if (isDanger) colCyberDanger else colCyberYellow
        )
        card.pad(20f, 24f, 18f, 24f)

        val titleLbl = Label(title, Label.LabelStyle(fontLarge, colCyberYellow))
        card.add(titleLbl).left().padBottom(8f).row()

        val div = Image(makeSolidDrawable(borderCol.r, borderCol.g, borderCol.b, 0.65f))
        card.add(div).height(2f).fillX().padBottom(12f).row()

        val msgLbl = Label(message, Label.LabelStyle(fontSmall, Color.WHITE)).apply {
            wrap = true
            setAlignment(Align.topLeft)
        }
        card.add(msgLbl).width(modalW - 52f).expandY().top().left().padBottom(14f).row()

        val btnRow = Table()
        val pBtn = createCyberButton(
            text = primaryText,
            width = 142f,
            height = 40f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontSmall
        ) {
            dismissPopupModal()
            onPrimary()
        }
        btnRow.add(pBtn).width(142f).height(40f)

        if (secondaryText != null && onSecondary != null) {
            val sBtn = createCyberButton(
                text = secondaryText,
                width = 148f,
                height = 40f,
                fillColor = colButtonBg,
                borderColor = colCyberOrange,
                textColor = Color.WHITE,
                fontOverride = fontSmall
            ) {
                dismissPopupModal()
                onSecondary()
            }
            btnRow.add(sBtn).width(148f).height(40f).padLeft(10f)
        }

        if (tertiaryText != null && onTertiary != null) {
            val tBtn = createCyberButton(
                text = tertiaryText,
                width = 110f,
                height = 40f,
                fillColor = Color(0.16f, 0.10f, 0.12f, 0.95f),
                borderColor = colCyberDanger,
                textColor = colTextDim,
                fontOverride = fontSmall
            ) {
                dismissPopupModal()
                onTertiary()
            }
            btnRow.add(tBtn).width(110f).height(40f).padLeft(10f)
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

    private fun showPlayerProfileModal(profile: PlayerProfile) {
        activePopupOverlay?.remove()

        val modalW = 540f
        val modalH = 340f

        val backdrop = Table()
        backdrop.setFillParent(true)
        backdrop.background = makeSolidDrawable(0.02f, 0.02f, 0.04f, 0.85f)
        backdrop.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {}
        })

        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = modalW.toInt(),
            h = modalH.toInt(),
            bgColor = Color(0.06f, 0.07f, 0.10f, 0.98f),
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true,
            accentPipsColor = colCyberYellow
        )
        card.pad(18f, 24f, 18f, 24f)

        // Header: Title & Close
        val header = Table()
        val titleLbl = Label("OPERATIVE DOSSIER // PROFILE", Label.LabelStyle(fontLarge, colCyberYellow))
        val closeBtn = createCyberButton("✕", 36f, 32f, colButtonBg, colCyberOrange, Color.WHITE, fontSmall) {
            dismissPopupModal()
        }
        header.add(titleLbl).left().expandX()
        header.add(closeBtn).right()
        card.add(header).fillX().padBottom(8f).row()

        val div = Image(makeSolidDrawable(colCyberOrange.r, colCyberOrange.g, colCyberOrange.b, 0.65f))
        card.add(div).height(2f).fillX().padBottom(12f).row()

        // Body: Avatar + Details
        val body = Table()
        if (avatarTexture != null) {
            val av = Image(TextureRegionDrawable(avatarTexture!!)).apply { setScaling(Scaling.fit) }
            val avFrame = Table()
            avFrame.background = createSciFiPanelDrawable(68, 68, colDarkTrack, colCyberYellow, 2, 8, false)
            avFrame.add(av).size(52f, 52f)
            body.add(avFrame).size(68f, 68f).padRight(14f).top()
        }

        val infoCol = Table()
        val nameLbl = Label(profile.username.uppercase(), Label.LabelStyle(fontLarge, colCyberYellow))
        val rankBadge = Label("LVL ${profile.level} • ${profile.rankTitle}", Label.LabelStyle(fontSmall, colCyberCyan))
        infoCol.add(nameLbl).left().row()
        infoCol.add(rankBadge).left().padBottom(6f).row()

        // XP bar
        val xpBarTable = Table()
        val xpTrackW = 360f
        val xpTrackH = 8f
        xpBarTable.background = makeSolidDrawable(0.12f, 0.14f, 0.18f, 0.9f)
        val fillW = (xpTrackW * profile.progressRatio).coerceAtLeast(2f)
        val fillImg = Image(makeSolidDrawable(colCyberCyan.r, colCyberCyan.g, colCyberCyan.b, 0.95f))
        xpBarTable.add(fillImg).width(fillW).height(xpTrackH).left().expandX()
        infoCol.add(xpBarTable).width(xpTrackW).height(xpTrackH).left().padBottom(4f).row()

        val xpText = Label("XP: ${profile.xp}  [${profile.currentLevelXp} / 1000 TO NEXT LEVEL]", Label.LabelStyle(fontTiny, Color.LIGHT_GRAY))
        infoCol.add(xpText).left().padBottom(6f).row()

        body.add(infoCol).left().expandX().top().row()
        card.add(body).fillX().padBottom(10f).row()

        // Stats Grid: Score | Kills | Deaths | K/D | Matches | Win Rate
        val statsGrid = Table()
        statsGrid.background = createSciFiPanelDrawable(modalW.toInt() - 48, 62, colDarkTrack, colCyberOrange, 1, 8, false)
        statsGrid.pad(6f, 12f, 6f, 12f)

        val statBox = { label: String, value: String, col: Color ->
            val t = Table()
            t.add(Label(value, Label.LabelStyle(fontMedium, col))).center().row()
            t.add(Label(label, Label.LabelStyle(fontTiny, colTextDim))).center()
            t
        }

        statsGrid.add(statBox("SCORE", "${profile.score}", colCyberYellow)).expandX()
        statsGrid.add(statBox("KILLS", "${profile.kills}", colCyberGreen)).expandX()
        statsGrid.add(statBox("DEATHS", "${profile.deaths}", colCyberDanger)).expandX()
        statsGrid.add(statBox("K/D", "%.2f".format(profile.kdRatio), Color.WHITE)).expandX()
        statsGrid.add(statBox("MATCHES", "${profile.matchesPlayed}", colCyberCyan)).expandX()
        statsGrid.add(statBox("WINS", "${profile.matchesWon}", colCyberYellow)).expandX()
        card.add(statsGrid).fillX().padBottom(10f).row()

        // Footer: Cloud Status Badge
        val footer = Table()
        val cloudDot = Label("●", Label.LabelStyle(fontTiny, colCyberGreen))
        val cloudLbl = Label(" FIREBASE REALTIME DB SYNC ACTIVE", Label.LabelStyle(fontTiny, colCyberGreen))
        footer.add(cloudDot).padRight(4f)
        footer.add(cloudLbl).left().expandX()
        card.add(footer).fillX()

        backdrop.add(card).width(modalW).height(modalH).center()
        activePopupOverlay = backdrop
        stage.addActor(backdrop)
    }

    private fun showNetworkUnavailablePopup(onRetry: () -> Unit) {
        val statusMsg = if (!internetAvailable) {
            "No internet connection detected.\nConnect to the internet to play ${selectedMode.displayName}, or play Solo offline."
        } else {
            "Multiplayer server is unreachable.\nCheck your connection to play ${selectedMode.displayName}, or play Solo offline."
        }
        showPopupModal(
            title = "CONNECTION ERROR",
            message = statusMsg,
            isDanger = true,
            primaryText = "RETRY",
            onPrimary = { probeServerAsync(forceCallback = onRetry) },
            secondaryText = "PLAY SOLO",
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
    // STEP 1: SIMPLE LOGIN / REGISTER SCREEN
    // =========================================================================
    private fun buildAuthOverlay(parent: Table) {
        val wrapper = Table()
        wrapper.setFillParent(true)

        val cardW = 440f
        val cardH = 300f
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
        card.pad(20f, 26f, 20f, 26f)

        val title = Label("SHOOT POWER", Label.LabelStyle(fontXLarge, colCyberYellow))
        card.add(title).center().padBottom(14f).row()

        val tfStyle = createCyberTextFieldStyle()

        val usernameField = TextField("", tfStyle).apply {
            messageText = "USERNAME"
            maxLength = 20
        }
        card.add(usernameField).width(384f).height(40f).padBottom(12f).row()

        val passwordField = TextField("", tfStyle).apply {
            messageText = "PASSWORD"
            isPasswordMode = true
            setPasswordCharacter('*')
            maxLength = 32
        }
        card.add(passwordField).width(384f).height(40f).padBottom(10f).row()

        val feedback = Label("", Label.LabelStyle(fontTiny, colCyberYellow))
        authFeedbackLabel = feedback
        card.add(feedback).center().height(18f).padBottom(10f).row()

        val btnRow = Table()
        val loginBtn = createCyberButton(
            text = "LOGIN",
            width = 184f,
            height = 44f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontMedium
        ) {
            handleAuthSubmit(usernameField.text, passwordField.text, isRegister = false)
        }

        val registerBtn = createCyberButton(
            text = "REGISTER",
            width = 184f,
            height = 44f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = Color.WHITE,
            fontOverride = fontMedium
        ) {
            handleAuthSubmit(usernameField.text, passwordField.text, isRegister = true)
        }

        btnRow.add(loginBtn).width(184f).height(44f).padRight(16f)
        btnRow.add(registerBtn).width(184f).height(44f)
        card.add(btnRow).center()

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
                title = "INVALID USERNAME",
                message = "Username must be at least 3 characters.",
                isDanger = true,
                primaryText = "OK"
            )
            return
        }
        if (password.length < 4) {
            showPopupModal(
                title = "INVALID PASSWORD",
                message = "Password must be at least 4 characters.",
                isDanger = true,
                primaryText = "OK"
            )
            return
        }

        authBusy = true
        authFeedbackLabel?.setText(if (isRegister) "CREATING ACCOUNT..." else "SIGNING IN...")
        authFeedbackLabel?.color = colCyberYellow

        val callback: (Result<AuthSessionRecord>) -> Unit = { result ->
            authBusy = false
            result.onSuccess { session ->
                activeSession = session
                activeProfile = FirebaseProfileService.getCachedProfile(session.username)
                FirebaseProfileService.fetchProfile(session.username) { profile ->
                    activeProfile = profile
                    if (currentStep == MenuStep.LOBBY) {
                        rebuildUi()
                    }
                }
                currentStep = MenuStep.LOBBY
                rebuildUi()
            }.onFailure { err ->
                val errMsg = err.message ?: "Authentication failed."
                authFeedbackLabel?.setText("")
                showPopupModal(
                    title = if (isRegister) "REGISTER FAILED" else "LOGIN FAILED",
                    message = errMsg,
                    isDanger = true,
                    primaryText = "TRY AGAIN"
                )
            }
        }

        if (isRegister) {
            FirebaseAuthService.register(username, password, callback)
        } else {
            FirebaseAuthService.login(username, password, callback)
        }
    }

    // =========================================================================
    // STEP 2: CLEAN, MINIMAL MAIN MENU LOBBY
    // =========================================================================
    private fun buildMainMenuLobby(parent: Table) {
        val username = activeSession?.username ?: "PLAYER"

        val root = Table()
        root.setFillParent(true)
        root.pad(14f, 20f, 14f, 20f)

        // 1. TOP BAR: Player Profile Card | SHOOT POWER | SFX / BGM / LOGOUT
        val topBar = Table()
        topBar.background = createSciFiPanelDrawable(
            w = 984,
            h = 68,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 10,
            withTechTab = false
        )
        topBar.pad(4f, 12f, 4f, 12f)

        // Left: Operative Profile Badge (Avatar + Level + Score & XP + Mini Bar)
        val profile = activeProfile ?: FirebaseProfileService.getCachedProfile(username)
        val playerBox = Table()
        playerBox.background = createSciFiPanelDrawable(
            w = 340,
            h = 58,
            bgColor = colDarkTrack,
            borderColor = colCyberYellow,
            borderWidth = 2,
            chamfer = 8,
            withTechTab = false
        )
        playerBox.pad(4f, 8f, 4f, 8f)

        if (avatarTexture != null) {
            val avImg = Image(TextureRegionDrawable(avatarTexture!!)).apply {
                setScaling(Scaling.fit)
            }
            val avFrame = Table()
            avFrame.background = createSciFiPanelDrawable(44, 44, colDarkChamber, colCyberOrange, 1, 4, false)
            avFrame.add(avImg).size(36f, 36f)
            playerBox.add(avFrame).size(44f, 44f).padRight(8f)
        }

        val infoCol = Table()
        val nameRow = Table()
        val pNameLbl = Label(username.uppercase(), Label.LabelStyle(fontMedium, colCyberYellow))
        val lvlBadge = Label(" LVL ${profile.level}", Label.LabelStyle(fontTiny, colCyberCyan))
        nameRow.add(pNameLbl).left()
        nameRow.add(lvlBadge).left().expandX()
        infoCol.add(nameRow).fillX().row()

        val scoreXpLbl = Label("★ SCORE: ${profile.score}  |  XP: ${profile.xp}", Label.LabelStyle(fontTiny, Color.WHITE))
        infoCol.add(scoreXpLbl).left().row()

        val miniXpRow = Table()
        val miniBar = Table()
        val miniBarW = 160f
        val miniBarH = 4f
        miniBar.background = makeSolidDrawable(0.10f, 0.12f, 0.16f, 0.9f)
        val fillW = (miniBarW * profile.progressRatio).coerceAtLeast(2f)
        val fillImg = Image(makeSolidDrawable(colCyberCyan.r, colCyberCyan.g, colCyberCyan.b, 0.95f))
        miniBar.add(fillImg).width(fillW).height(miniBarH).left().expandX()
        miniXpRow.add(miniBar).width(miniBarW).height(miniBarH).left().padRight(6f)

        val xpSub = Label("${profile.currentLevelXp}/1000", Label.LabelStyle(fontTiny, colTextDim))
        miniXpRow.add(xpSub).left()
        infoCol.add(miniXpRow).left().padTop(2f)

        playerBox.add(infoCol).left().expandX()
        playerBox.touchable = Touchable.enabled
        playerBox.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.6f)
                val current = activeProfile ?: FirebaseProfileService.getCachedProfile(username)
                showPlayerProfileModal(current)
            }
        })
        topBar.add(playerBox).left().width(340f)

        // Center: Game Title + Server Status
        val titleStack = Table()
        val titleLbl = Label("SHOOT POWER", Label.LabelStyle(fontLarge, colCyberYellow))
        val netText = when {
            checkingNetwork -> "● SERVER CONNECTING..."
            serverOnline -> "● SERVER ONLINE"
            else -> "● SERVER OFFLINE"
        }
        val netCol = if (serverOnline) colCyberGreen else colCyberDanger
        val netLbl = Label(netText, Label.LabelStyle(fontTiny, netCol))
        networkBadgeLabel = netLbl
        titleStack.add(titleLbl).center().row()
        titleStack.add(netLbl).center()
        topBar.add(titleStack).expandX().center()

        // Right: Quick Audio & Logout Buttons
        val rightBtns = Table()
        val sfxBtn = createCyberButton(
            text = if (audioService.isSoundEnabled) "SFX" else "MUTE",
            width = 68f,
            height = 36f,
            fillColor = colButtonBg,
            borderColor = colCyberOrange,
            textColor = Color.WHITE,
            fontOverride = fontTiny
        ) {
            audioService.toggleSound()
            rebuildUi()
        }
        val bgmBtn = createCyberButton(
            text = if (audioService.isMusicEnabled) "BGM" else "MUTE",
            width = 68f,
            height = 36f,
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
            width = 84f,
            height = 36f,
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
        rightBtns.add(sfxBtn).width(68f).height(36f).padRight(8f)
        rightBtns.add(bgmBtn).width(68f).height(36f).padRight(8f)
        rightBtns.add(logoutBtn).width(84f).height(36f)
        topBar.add(rightBtns).right().width(240f)

        root.add(topBar).fillX().height(68f).padBottom(16f).row()

        // 2. CENTER: 3 BIG, CLEAN MODE CARDS (SOLO | DUO | SQUAD)
        val modesRow = Table()
        modesRow.add(
            buildSimpleModeCard(
                mode = GameMode.OFFLINE,
                title = "SOLO",
                subtitle = "1 PLAYER • PRACTICE"
            )
        ).width(310f).height(210f).padRight(22f)

        modesRow.add(
            buildSimpleModeCard(
                mode = GameMode.DUO,
                title = "DUO",
                subtitle = "2 PLAYERS • ${if (selectedMode == GameMode.DUO) customRounds else 2} ROUNDS"
            )
        ).width(310f).height(210f).padRight(22f)

        modesRow.add(
            buildSimpleModeCard(
                mode = GameMode.SQUAD,
                title = "SQUAD",
                subtitle = "4 PLAYERS • ${if (selectedMode == GameMode.SQUAD) customRounds else 4} ROUNDS"
            )
        ).width(310f).height(210f)

        root.add(modesRow).expandY().center().padBottom(18f).row()

        // 3. BOTTOM ACTION BAR: Simple Play / Room Controls
        val bottomBar = Table()
        bottomBar.background = createSciFiPanelDrawable(
            w = 984,
            h = 88,
            bgColor = colDarkChamber,
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = false
        )
        bottomBar.pad(12f, 20f, 12f, 20f)

        if (selectedMode == GameMode.OFFLINE) {
            val playSoloBtn = createCyberButton(
                text = "PLAY SOLO",
                width = 360f,
                height = 56f,
                fillColor = colButtonPressed,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontLarge
            ) {
                launchMatch(roomCode = "", createRoom = false)
            }
            bottomBar.add(playSoloBtn).width(360f).height(56f).center()
        } else {
            val createRoomBtn = createCyberButton(
                text = "CREATE ROOM",
                width = 230f,
                height = 54f,
                fillColor = colButtonPressed,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontMedium
            ) {
                val newCode = GameConfig.generateRoomCode()
                tryEnterWaitingRoom(roomCode = newCode, createRoom = true)
            }

            val tf = TextField("", createCyberTextFieldStyle()).apply {
                messageText = "ROOM CODE"
                maxLength = 8
            }
            roomCodeField = tf

            val joinRoomBtn = createCyberButton(
                text = "JOIN ROOM",
                width = 180f,
                height = 54f,
                fillColor = colButtonBg,
                borderColor = colCyberYellow,
                textColor = colCyberYellow,
                fontOverride = fontMedium
            ) {
                val entered = tf.text.trim().uppercase()
                if (entered.length < 4) {
                    showPopupModal(
                        title = "ENTER ROOM CODE",
                        message = "Enter a valid Room Code to join, or click CREATE ROOM.",
                        isDanger = true,
                        primaryText = "CREATE ROOM",
                        onPrimary = {
                            val newCode = GameConfig.generateRoomCode()
                            tryEnterWaitingRoom(roomCode = newCode, createRoom = true)
                        },
                        secondaryText = "OK",
                        onSecondary = {}
                    )
                } else {
                    tryEnterWaitingRoom(roomCode = entered, createRoom = false)
                }
            }

            val quickPlayBtn = createCyberButton(
                text = "QUICK PLAY",
                width = 210f,
                height = 54f,
                fillColor = colButtonBg,
                borderColor = colCyberOrange,
                textColor = Color.WHITE,
                fontOverride = fontMedium
            ) {
                verifyMultiplayerThen {
                    launchMatch(roomCode = "", createRoom = true)
                }
            }

            bottomBar.add(createRoomBtn).width(230f).height(54f).padRight(18f)
            bottomBar.add(tf).width(190f).height(46f).padRight(10f)
            bottomBar.add(joinRoomBtn).width(180f).height(54f).padRight(18f)
            bottomBar.add(quickPlayBtn).width(210f).height(54f)
        }

        root.add(bottomBar).fillX().height(88f)
        parent.add(root).expand().fill()
    }

    private fun buildSimpleModeCard(
        mode: GameMode,
        title: String,
        subtitle: String
    ): Table {
        val selected = (selectedMode == mode)
        val borderCol = if (selected) colCyberYellow else colCyberOrangeDark
        val bgCol = if (selected) Color(0.24f, 0.16f, 0.03f, 0.96f) else colDarkPanel

        val card = Table()
        card.background = createSciFiPanelDrawable(
            w = 310,
            h = 210,
            bgColor = bgCol,
            borderColor = borderCol,
            borderWidth = if (selected) 3 else 2,
            chamfer = 14,
            withTechTab = selected,
            accentPipsColor = if (selected) colCyberYellow else colCyberOrange
        )
        card.pad(18f, 16f, 18f, 16f)

        val titleLbl = Label(
            title,
            Label.LabelStyle(fontXLarge, if (selected) colCyberYellow else Color.WHITE)
        )
        card.add(titleLbl).center().padBottom(8f).row()

        val subLbl = Label(
            subtitle,
            Label.LabelStyle(fontSmall, if (selected) Color.WHITE else colTextDim)
        )
        card.add(subLbl).center().padBottom(24f).row()

        val statusLbl = Label(
            if (selected) "SELECTED" else "SELECT",
            Label.LabelStyle(fontMedium, if (selected) colCyberGreen else colCyberOrange)
        )
        card.add(statusLbl).center().row()

        if (selected && mode != GameMode.OFFLINE) {
            val roundsRow = Table()
            roundsRow.padTop(10f)
            val minusBtn = createCyberButton("-", 34f, 30f, colButtonBg, colCyberOrange, colCyberYellow, fontSmall) {
                customRounds = max(1, customRounds - 1)
                rebuildUi()
            }
            val roundsLbl = Label("$customRounds ROUNDS", Label.LabelStyle(fontSmall, colCyberYellow))
            val plusBtn = createCyberButton("+", 34f, 30f, colButtonBg, colCyberOrange, colCyberYellow, fontSmall) {
                customRounds = (customRounds + 1).coerceAtMost(10)
                rebuildUi()
            }
            roundsRow.add(minusBtn).padRight(6f)
            roundsRow.add(roundsLbl).padRight(6f)
            roundsRow.add(plusBtn)
            card.add(roundsRow).center()
        }

        card.addListener(object : ClickListener() {
            override fun clicked(event: InputEvent?, x: Float, y: Float) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                if (selectedMode != mode) {
                    selectedMode = mode
                    customRounds = if (mode == GameMode.SQUAD) 4 else 2
                }
                rebuildUi()
            }
        })

        return card
    }

    // =========================================================================
    // STEP 3: SIMPLE "WAITING FOR PLAYERS" ROOM LOBBY MENU
    // =========================================================================
    private fun tryEnterWaitingRoom(roomCode: String, createRoom: Boolean) {
        verifyMultiplayerThen {
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

        val username = activeSession?.username ?: "PLAYER"
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
        root.pad(14f, 20f, 14f, 20f)

        // 1. Top Bar: Mode Title | ROOM CODE + COPY | LEAVE
        val topBar = Table()
        topBar.background = createSciFiPanelDrawable(
            w = 984,
            h = 60,
            bgColor = colDarkChamber,
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 12,
            withTechTab = false
        )
        topBar.pad(8f, 18f, 8f, 18f)

        val modeTitle = Label("${selectedMode.name} • $customRounds ROUNDS", Label.LabelStyle(fontLarge, colCyberYellow))
        topBar.add(modeTitle).left().expandX()

        val cLbl = Label("CODE: $waitingRoomCode", Label.LabelStyle(fontLarge, Color.WHITE))
        waitingCodeValueLabel = cLbl
        topBar.add(cLbl).padRight(14f)

        val copyBtn = createCyberButton(
            text = "COPY",
            width = 96f,
            height = 40f,
            fillColor = colButtonPressed,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontSmall
        ) {
            Gdx.app.clipboard.contents = waitingRoomCode
            waitingStatusBannerLabel?.setText("COPIED CODE [$waitingRoomCode]!")
            waitingStatusBannerLabel?.color = colCyberGreen
        }
        topBar.add(copyBtn).width(96f).height(40f).padRight(10f)

        val leaveBtn = createCyberButton(
            text = "LEAVE",
            width = 96f,
            height = 40f,
            fillColor = Color(0.26f, 0.10f, 0.12f, 0.95f),
            borderColor = colCyberDanger,
            textColor = Color.WHITE,
            fontOverride = fontSmall
        ) {
            stopWaitingRoomClient()
            currentStep = MenuStep.LOBBY
            rebuildUi()
        }
        topBar.add(leaveBtn).width(96f).height(40f)

        root.add(topBar).fillX().height(60f).padBottom(14f).row()

        // 2. Center: Player Slots
        val bodyCard = Table()
        bodyCard.background = createSciFiPanelDrawable(
            w = 984,
            h = 270,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 3,
            chamfer = 14,
            withTechTab = true
        )
        bodyCard.pad(14f, 18f, 14f, 18f)

        val statusHeader = Table()
        val countLbl = Label(
            "PLAYERS: ${waitingMembers.size} / ${selectedMode.maxPlayers}",
            Label.LabelStyle(fontMedium, Color.WHITE)
        )
        waitingCountLabel = countLbl

        val statusBanner = Label(
            "WAITING FOR PLAYERS...",
            Label.LabelStyle(fontMedium, colCyberYellow)
        )
        waitingStatusBannerLabel = statusBanner

        statusHeader.add(countLbl).left().expandX()
        statusHeader.add(statusBanner).right()
        bodyCard.add(statusHeader).fillX().padBottom(14f).row()

        val slotsGrid = Table()
        waitingSlotsContainer = slotsGrid
        refreshWaitingSlotsGrid()
        bodyCard.add(slotsGrid).expand().fill().row()

        root.add(bodyCard).fillX().height(270f).padBottom(14f).row()

        // 3. Bottom Bar: Auto-Start Toggle + Start Match Button
        val bottomBar = Table()
        bottomBar.background = createSciFiPanelDrawable(
            w = 984,
            h = 68,
            bgColor = colDarkChamber,
            borderColor = colCyberOrange,
            borderWidth = 2,
            chamfer = 10,
            withTechTab = false
        )
        bottomBar.pad(8f, 18f, 8f, 18f)

        val autoStartBtn = createCyberButton(
            text = if (waitingAutoStart) "AUTO-START: ON" else "AUTO-START: OFF",
            width = 210f,
            height = 46f,
            fillColor = colButtonBg,
            borderColor = if (waitingAutoStart) colCyberGreen else colCyberGrey,
            textColor = if (waitingAutoStart) colCyberGreen else colTextDim,
            fontOverride = fontSmall,
            labelHook = { waitingAutoStartBtnLabel = it }
        ) {
            waitingAutoStart = !waitingAutoStart
            waitingAutoStartBtnLabel?.setText(
                if (waitingAutoStart) "AUTO-START: ON" else "AUTO-START: OFF"
            )
            waitingAutoStartBtnLabel?.color = if (waitingAutoStart) colCyberGreen else colTextDim
        }
        val roundsBtn = createCyberButton(
            text = "ROUNDS: $customRounds",
            width = 170f,
            height = 46f,
            fillColor = colButtonBg,
            borderColor = colCyberYellow,
            textColor = colCyberYellow,
            fontOverride = fontSmall
        ) {
            val nextRounds = when (customRounds) {
                2 -> 3
                3 -> 4
                4 -> 5
                5 -> 6
                else -> 2
            }
            customRounds = nextRounds
            rebuildUi()
        }
        bottomBar.add(roundsBtn).width(170f).height(46f).padRight(16f)

        val startNowBtn = createCyberButton(
            text = if (waitingMembers.size >= selectedMode.maxPlayers) "START MATCH" else "WAITING (${waitingMembers.size}/${selectedMode.maxPlayers})",
            width = 260f,
            height = 48f,
            fillColor = if (waitingMembers.size >= selectedMode.maxPlayers) colButtonPressed else colButtonBg,
            borderColor = if (waitingMembers.size >= selectedMode.maxPlayers) colCyberYellow else colCyberGrey,
            textColor = if (waitingMembers.size >= selectedMode.maxPlayers) colCyberYellow else colTextDim,
            fontOverride = fontLarge,
            labelHook = { waitingStartBtnLabel = it }
        ) {
            val currentCount = waitingMembers.size
            val maxPlayers = selectedMode.maxPlayers
            if (currentCount < maxPlayers) {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                val missing = maxPlayers - currentCount
                showPopupModal(
                    title = "WAITING FOR PLAYERS",
                    message = "Cannot start ${selectedMode.displayName} yet!\nWaiting for $missing more player${if (missing > 1) "s" else ""} to join.\nShare Room Code: $waitingRoomCode",
                    isDanger = false,
                    primaryText = "OK"
                )
            } else {
                deployFromWaitingRoom()
            }
        }
        bottomBar.add(startNowBtn).width(260f).height(48f).right()

        root.add(bottomBar).fillX().height(68f)
        parent.add(root).expand().fill()
    }

    private fun refreshWaitingSlotsGrid() {
        val grid = waitingSlotsContainer ?: return
        grid.clearChildren()

        val maxSlots = selectedMode.maxPlayers.coerceAtLeast(2)
        val memberList = waitingMembers.values.toList()
        val slotW = if (maxSlots <= 2) 456f else 224f
        val slotH = 185f

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
                    withTechTab = false
                )
                slotCard.pad(12f)

                if (avatarTexture != null) {
                    val img = Image(TextureRegionDrawable(avatarTexture!!)).apply {
                        setScaling(Scaling.fit)
                    }
                    slotCard.add(img).size(58f, 58f).padBottom(8f).row()
                }

                val youSuffix = if (member.isLocal) " (YOU)" else ""
                val pName = Label("${member.name.uppercase()}$youSuffix", Label.LabelStyle(fontMedium, Color.WHITE))
                slotCard.add(pName).center().padBottom(6f).row()

                val readyBadge = Label("READY", Label.LabelStyle(fontSmall, colCyberGreen))
                slotCard.add(readyBadge).center()
            } else {
                val dots = ".".repeat(waitingDotCount)
                slotCard.background = createSciFiPanelDrawable(
                    w = slotW.toInt(),
                    h = slotH.toInt(),
                    bgColor = Color(0.09f, 0.10f, 0.14f, 0.88f),
                    borderColor = colCyberGrey,
                    borderWidth = 2,
                    chamfer = 10,
                    withTechTab = false
                )
                slotCard.pad(12f)

                val waitLbl = Label("WAITING$dots", Label.LabelStyle(fontMedium, colCyberYellow))
                slotCard.add(waitLbl).center().padBottom(6f).row()

                val codeHint = Label("CODE: $waitingRoomCode", Label.LabelStyle(fontTiny, colTextDim))
                slotCard.add(codeHint).center()
            }

            grid.add(slotCard).width(slotW).height(slotH)
            if (slotIdx < maxSlots - 1) {
                grid.add().width(12f)
            }
        }
    }

    private fun updateWaitingRoomTick(delta: Float) {
        val client = lobbyClient ?: return

        waitingDotTimer += delta
        if (waitingDotTimer >= 0.45f) {
            waitingDotTimer = 0f
            waitingDotCount = (waitingDotCount % 3) + 1
            refreshWaitingSlotsGrid()
        }

        waitingConnectElapsed += delta
        if (client.connectionFailed || (!client.isConnected && waitingConnectElapsed > 4.5f)) {
            stopWaitingRoomClient()
            currentStep = MenuStep.LOBBY
            rebuildUi()
            showNetworkUnavailablePopup {
                tryEnterWaitingRoom(waitingRoomCode, waitingCreateRoom)
            }
            return
        }

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

        var slotsChanged = false
        while (client.packetQueue.isNotEmpty()) {
            when (val pkt = client.packetQueue.poll()) {
                is LoginResponsePacket -> {
                    savedLoginResponse = pkt
                    if (pkt.roomCode.isNotBlank()) {
                        waitingRoomCode = pkt.roomCode.trim().uppercase()
                        waitingCodeValueLabel?.setText("CODE: $waitingRoomCode")
                    }
                    val localEntry = waitingMembers.remove(0) ?: WaitingRoomMember(
                        id = pkt.assignedId,
                        name = activeSession?.username ?: "PLAYER",
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
                        val pName = pkt.playerName.ifBlank { existing?.name ?: "PLAYER #${pkt.id}" }
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
                        val updatedName = pkt.playerName.ifBlank { existing?.name ?: "PLAYER #${pkt.id}" }
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
        waitingCountLabel?.setText("PLAYERS: $currentCount / $maxPlayers")

        if (currentCount >= maxPlayers) {
            waitingStartBtnLabel?.setText("START MATCH")
            waitingStartBtnLabel?.color = colCyberYellow
            if (waitingAutoStart) {
                if (waitingCountdown < 0f) {
                    waitingCountdown = 3.0f
                    audioService.play(SoundType.RELOAD, volumeModifier = 0.7f)
                }
                waitingCountdown -= delta
                val secLeft = max(1, waitingCountdown.toInt() + 1)
                waitingStatusBannerLabel?.setText("STARTING IN ${secLeft}s...")
                waitingStatusBannerLabel?.color = colCyberGreen
                if (waitingCountdown <= 0f) {
                    deployFromWaitingRoom()
                    return
                }
            } else {
                waitingStatusBannerLabel?.setText("ROOM FULL! CLICK START MATCH")
                waitingStatusBannerLabel?.color = colCyberGreen
            }
        } else {
            waitingCountdown = -1f
            waitingStartBtnLabel?.setText("WAITING ($currentCount/$maxPlayers)")
            waitingStartBtnLabel?.color = colTextDim
            val dots = ".".repeat(waitingDotCount)
            waitingStatusBannerLabel?.setText("WAITING FOR PLAYERS$dots ($currentCount/$maxPlayers)")
            waitingStatusBannerLabel?.color = colCyberYellow
        }

        if (slotsChanged) {
            refreshWaitingSlotsGrid()
        }
    }

    private fun deployFromWaitingRoom() {
        val client = lobbyClient
        if (client != null && client.isConnected) {
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

        lobbyClient = null
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val session = activeSession
        val config = GameConfig(
            mode = selectedMode,
            playerName = session?.username ?: "PLAYER",
            serverHost = host,
            roomCode = waitingRoomCode,
            createRoom = waitingCreateRoom,
            jwtToken = session?.jwtToken ?: "",
            sessionToken = session?.sessionToken ?: "",
            preConnectedClient = client,
            totalRounds = customRounds
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
            withTechTab = false
        )
        val pressedBg = createSciFiPanelDrawable(
            w = width.toInt(),
            h = height.toInt(),
            bgColor = Color(0.38f, 0.26f, 0.04f, 0.98f),
            borderColor = colCyberYellow,
            borderWidth = 3,
            chamfer = 8,
            withTechTab = false
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

    private fun verifyMultiplayerThen(onVerified: () -> Unit) {
        if (selectedMode == GameMode.OFFLINE) {
            onVerified()
            return
        }

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
                internetAvailable = netOk
                serverOnline = serverOk
                updateNetworkHeaderLabels()

                if (serverOk) {
                    onVerified()
                } else {
                    showNetworkUnavailablePopup(
                        onRetry = { verifyMultiplayerThen(onVerified) }
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
                checkingNetwork = false
                internetAvailable = netOk
                serverOnline = serverOk
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
                    lbl.setText("CHECKING...")
                    lbl.color = colCyberYellow
                }
                serverOnline -> {
                    lbl.setText("ONLINE")
                    lbl.color = colCyberGreen
                }
                else -> {
                    lbl.setText("OFFLINE")
                    lbl.color = colCyberDanger
                }
            }
        }
    }

    private fun launchMatch(roomCode: String = "", createRoom: Boolean = false) {
        val host = System.getProperty("shootgame.host", NetworkConfig.serverHost)
        val session = activeSession
        val config = GameConfig(
            mode = selectedMode,
            playerName = session?.username ?: "PLAYER",
            serverHost = host,
            roomCode = roomCode.trim().uppercase(),
            createRoom = createRoom,
            jwtToken = session?.jwtToken ?: "",
            sessionToken = session?.sessionToken ?: "",
            totalRounds = customRounds
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
        generatedTextures.forEach { it.disposeSafely() }
        generatedTextures.clear()
        generatedFonts.forEach { it.disposeSafely() }
        generatedFonts.clear()
        fontGenerator?.disposeSafely()
        fontGenerator = null
    }
}
