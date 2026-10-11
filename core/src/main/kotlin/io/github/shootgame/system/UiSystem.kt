package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Stack
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Qualifier
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent
import io.github.shootgame.component.WeaponComponent
import io.github.shootgame.component.WeaponType
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameMode
import kotlin.math.max

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val audioService: AudioService,
    private val config: GameConfig
) : IntervalSystem() {

    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }

    var touchLeft = false
    var touchRight = false
    var touchShoot = false
    var touchReload = false
    var touchJump = false
    var touchGrenade = false
    var touchSwitchWeapon = false
    var requestedWeaponSelect: WeaponType? = null

    fun consumeWeaponSwitch(): Boolean {
        val res = touchSwitchWeapon
        touchSwitchWeapon = false
        return res
    }

    private val disposables = mutableListOf<Texture>()
    private var fontGenerator: FreeTypeFontGenerator? = null
    private val generatedFonts = mutableListOf<BitmapFont>()
    private lateinit var fontTiny: BitmapFont
    private lateinit var fontSmall: BitmapFont
    private lateinit var fontMedium: BitmapFont
    private lateinit var fontLarge: BitmapFont
    private lateinit var fontXLarge: BitmapFont

    // Futuristic Sci-Fi Cyberpunk Palette (Matched to Reference Image)
    private val colCyberOrange = Color(1.0f, 0.58f, 0.02f, 1f)      // Neon cyber-orange border
    private val colCyberYellow = Color(1.0f, 0.90f, 0.04f, 1f)      // Cyber neon yellow fill
    private val colCyberDanger = Color(0.96f, 0.22f, 0.26f, 1f)      // Alert red fill
    private val colCyberCyan = Color(0.25f, 0.92f, 1.0f, 1f)         // Tactical cyan accent
    private val colCyberGrey = Color(0.38f, 0.40f, 0.44f, 1f)        // Tech hazard grey
    private val colDarkChamber = Color(0.09f, 0.10f, 0.13f, 0.94f)   // Chassis dark background
    private val colDarkTrack = Color(0.18f, 0.19f, 0.22f, 1f)        // Unfilled recess track

    // Button states
    private val colButtonBg = Color(0.12f, 0.14f, 0.18f, 0.92f)
    private val colButtonPressed = Color(0.28f, 0.18f, 0.06f, 0.95f)

    // Fill Drawables
    private lateinit var fillYellowDrawable: TextureRegionDrawable
    private lateinit var fillDangerDrawable: TextureRegionDrawable
    private lateinit var fillAmberDrawable: TextureRegionDrawable

    // HUD Elements
    private lateinit var modeStatusLabel: Label
    private var roomCodeLabel: Label? = null
    private lateinit var sbTitleLabel: Label
    private lateinit var scoreboardRowsTable: Table
    private lateinit var combatFeedLabel: Label
    private lateinit var healthBarFill: Image
    private lateinit var healthBarFillCell: Cell<Image>
    private lateinit var ammoBarFill: Image
    private lateinit var ammoBarFillCell: Cell<Image>
    private lateinit var healthTextLabel: Label
    private lateinit var weaponNameLabel: Label
    private lateinit var ammoCountLabel: Label
    private lateinit var fragCountLabel: Label
    private lateinit var scoreValueLabel: Label
    private var sfxButtonLabel: Label? = null
    private var bgmButtonLabel: Label? = null

    private var maxHealthBarWidth = 432f
    private var maxAmmoBarWidth = 432f

    init {
        initFonts()
        setupHud()
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
    }

    private fun initFonts() {
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
                        this.shadowColor = Color(0f, 0f, 0f, 0.95f)
                        this.minFilter = Texture.TextureFilter.Linear
                        this.magFilter = Texture.TextureFilter.Linear
                    }
                    generator.generateFont(param).also { generatedFonts.add(it) }
                }

                fontTiny = makeFont(16, 1.4f, 1f)
                fontSmall = makeFont(22, 1.8f, 2f)
                fontMedium = makeFont(28, 2.2f, 2f)
                fontLarge = makeFont(34, 2.6f, 3f)
                fontXLarge = makeFont(44, 3.2f, 3f)
                return
            } catch (e: Exception) {
                Gdx.app.error("UiSystem", "Failed to load KnightWarrior font, fallback to default", e)
            }
        }

        val defaultFont = BitmapFont()
        generatedFonts.add(defaultFont)
        fontTiny = defaultFont
        fontSmall = defaultFont
        fontMedium = defaultFont
        fontLarge = defaultFont
        fontXLarge = defaultFont
    }

    private fun createFillDrawable(fillColor: Color): TextureRegionDrawable {
        val pixmap = Pixmap(16, 16, Pixmap.Format.RGBA8888)
        pixmap.setColor(fillColor)
        pixmap.fill()
        val texture = Texture(pixmap)
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        disposables.add(texture)
        pixmap.dispose()
        return TextureRegionDrawable(texture)
    }

    /**
     * Procedurally creates the futuristic sci-fi progress bar frame matching the reference image:
     * - Chamfered outer cyber-orange border
     * - Asymmetrical tech tab with diagonal hazard warning chevrons (///)
     * - Segmented LED status pips (■ ■ ■ ■)
     * - Dark inner recess for the fill bar
     */
    private fun createSciFiBarBackground(
        w: Int,
        h: Int,
        tabOnTop: Boolean = true
    ): TextureRegionDrawable {
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        pixmap.setColor(0f, 0f, 0f, 0f)
        pixmap.fill()
        pixmap.blending = Pixmap.Blending.SourceOver

        val chamfer = 12
        val tabH = 6
        val borderWidth = 3

        fun fillChamferedBox(ox: Int, oy: Int, bw: Int, bh: Int, ch: Int, col: Color) {
            pixmap.setColor(col)
            pixmap.fillRectangle(ox + ch, oy, bw - ch * 2, bh)
            pixmap.fillRectangle(ox, oy + ch, ch, bh - ch * 2)
            pixmap.fillRectangle(ox + bw - ch, oy + ch, ch, bh - ch * 2)
            pixmap.fillTriangle(ox, oy + ch, ox + ch, oy + ch, ox + ch, oy)
            pixmap.fillTriangle(ox + bw - ch, oy, ox + bw - ch, oy + ch, ox + bw - 1, oy + ch)
            pixmap.fillTriangle(ox, oy + bh - ch, ox + ch, oy + bh - ch, ox + ch, oy + bh - 1)
            pixmap.fillTriangle(ox + bw - ch, oy + bh - 1, ox + bw - ch, oy + bh - ch, ox + bw - 1, oy + bh - ch)
        }

        val baseY = if (tabOnTop) tabH else 0
        val baseH = h - tabH
        fillChamferedBox(0, baseY, w, baseH, chamfer, colCyberOrange)

        // Asymmetrical tech tab
        val tabStart = if (tabOnTop) (w * 0.44f).toInt() else (w * 0.12f).toInt()
        val tabW = (w * 0.34f).toInt()
        if (tabOnTop) {
            pixmap.setColor(colCyberOrange)
            pixmap.fillRectangle(tabStart, 0, tabW, tabH + 1)
            pixmap.fillTriangle(tabStart - tabH, tabH, tabStart, tabH, tabStart, 0)
            pixmap.fillTriangle(tabStart + tabW, 0, tabStart + tabW, tabH, tabStart + tabW + tabH, tabH)
        } else {
            pixmap.setColor(colCyberOrange)
            pixmap.fillRectangle(tabStart, h - tabH - 1, tabW, tabH + 1)
            pixmap.fillTriangle(tabStart - tabH, h - tabH, tabStart, h - tabH, tabStart, h - 1)
            pixmap.fillTriangle(tabStart + tabW, h - 1, tabStart + tabW, h - tabH, tabStart + tabW + tabH, h - tabH)
        }

        // Inner dark chamber
        fillChamferedBox(
            borderWidth,
            baseY + borderWidth,
            w - borderWidth * 2,
            baseH - borderWidth * 2,
            max(2, chamfer - borderWidth),
            colDarkChamber
        )

        // Recessed track cavity for progress bar
        val trackX = 14
        val trackY = baseY + 10
        val trackW = w - 28
        val trackH = baseH - 20
        pixmap.setColor(colDarkTrack)
        pixmap.fillRectangle(trackX, trackY, trackW, trackH)

        // Track border
        pixmap.setColor(Color(0.28f, 0.30f, 0.35f, 1f))
        pixmap.drawRectangle(trackX - 1, trackY - 1, trackW + 2, trackH + 2)

        // Hazard Chevrons (///)
        val chevronsStart = tabStart + 8
        val chevronsY = if (tabOnTop) 1 else (h - tabH + 1)
        for (i in 0 until 5) {
            val sx = chevronsStart + i * 11
            pixmap.setColor(if (i < 3) colCyberYellow else colCyberGrey)
            pixmap.drawLine(sx, chevronsY + 4, sx + 4, chevronsY)
            pixmap.drawLine(sx + 1, chevronsY + 4, sx + 5, chevronsY)
        }

        // Segmented Status Pips (■ ■ ■ ■)
        val pipsStart = if (tabOnTop) (w * 0.10f).toInt() else (w * 0.55f).toInt()
        val pipsY = if (tabOnTop) (h - 4) else 1
        for (i in 0 until 5) {
            val px = pipsStart + i * 10
            pixmap.setColor(if (i < 3) colCyberYellow else colCyberGrey)
            pixmap.fillRectangle(px, pipsY, 6, 2)
        }

        val texture = Texture(pixmap)
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        disposables.add(texture)
        pixmap.dispose()
        return TextureRegionDrawable(texture)
    }

    private fun createSciFiBadgeBackground(
        w: Int,
        h: Int,
        accentColor: Color = colCyberOrange
    ): TextureRegionDrawable {
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        pixmap.setColor(0f, 0f, 0f, 0f)
        pixmap.fill()
        pixmap.blending = Pixmap.Blending.SourceOver

        val ch = 10
        val bw = 3
        // Outer border
        pixmap.setColor(accentColor)
        pixmap.fillRectangle(ch, 0, w - ch * 2, h)
        pixmap.fillRectangle(0, ch, ch, h - ch * 2)
        pixmap.fillRectangle(w - ch, ch, ch, h - ch * 2)
        pixmap.fillTriangle(0, ch, ch, ch, ch, 0)
        pixmap.fillTriangle(w - ch, 0, w - ch, ch, w - 1, ch)
        pixmap.fillTriangle(0, h - ch, ch, h - ch, ch, h - 1)
        pixmap.fillTriangle(w - ch, h - 1, w - ch, h - ch, w - 1, h - ch)

        // Inner dark body
        pixmap.setColor(colDarkChamber)
        pixmap.fillRectangle(ch, bw, w - ch * 2, h - bw * 2)
        pixmap.fillRectangle(bw, ch, ch, h - ch * 2)
        pixmap.fillRectangle(w - ch, ch, ch - bw, h - ch * 2)
        pixmap.fillTriangle(bw, ch, ch, ch, ch, bw)
        pixmap.fillTriangle(w - ch, bw, w - ch, ch, w - 1 - bw, ch)
        pixmap.fillTriangle(bw, h - ch, ch, h - ch, ch, h - 1 - bw)
        pixmap.fillTriangle(w - ch, h - 1 - bw, w - ch, h - ch, w - 1 - bw, h - ch)

        // Tech chevrons
        for (i in 0 until 3) {
            pixmap.setColor(if (i == 0) colCyberYellow else colCyberGrey)
            val sx = w - 30 + i * 8
            pixmap.drawLine(sx, 8, sx + 4, 3)
            pixmap.drawLine(sx + 1, 8, sx + 5, 3)
        }

        val texture = Texture(pixmap)
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        disposables.add(texture)
        pixmap.dispose()
        return TextureRegionDrawable(texture)
    }

    private fun createRoundedDrawable(
        w: Int,
        h: Int,
        radius: Int,
        fillColor: Color,
        borderColor: Color? = null,
        borderWidth: Int = 1
    ): TextureRegionDrawable {
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None

        if (borderColor != null && borderWidth > 0) {
            pixmap.setColor(borderColor)
            fillRounded(pixmap, w, h, radius)
            pixmap.setColor(fillColor)
            fillRounded(
                pixmap,
                w - borderWidth * 2,
                h - borderWidth * 2,
                max(1, radius - borderWidth),
                borderWidth,
                borderWidth
            )
        } else {
            pixmap.setColor(fillColor)
            fillRounded(pixmap, w, h, radius)
        }

        val texture = Texture(pixmap)
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        disposables.add(texture)
        pixmap.dispose()
        return TextureRegionDrawable(texture)
    }

    private fun fillRounded(
        pixmap: Pixmap,
        w: Int,
        h: Int,
        r: Int,
        offsetX: Int = 0,
        offsetY: Int = 0
    ) {
        val rad = r.coerceAtMost(w / 2).coerceAtMost(h / 2)
        pixmap.fillCircle(offsetX + rad, offsetY + rad, rad)
        pixmap.fillCircle(offsetX + w - rad - 1, offsetY + rad, rad)
        pixmap.fillCircle(offsetX + rad, offsetY + h - rad - 1, rad)
        pixmap.fillCircle(offsetX + w - rad - 1, offsetY + h - rad - 1, rad)
        pixmap.fillRectangle(offsetX + rad, offsetY, w - rad * 2, h)
        pixmap.fillRectangle(offsetX, offsetY + rad, w, h - rad * 2)
    }

    private fun setupHud() {
        val rootTable = Table()
        rootTable.setFillParent(true)
        rootTable.top().pad(16f)

        fillYellowDrawable = createFillDrawable(colCyberYellow)
        fillDangerDrawable = createFillDrawable(colCyberDanger)
        fillAmberDrawable = createFillDrawable(colCyberOrange)

        // 1. Status Section: Health & Weapon Sci-Fi Progress Bars (Prominent & Large)
        val statusContainer = Table()
        val barWidth = 460f
        val barHeight = 58f
        val trackWidth = barWidth - 28f
        val fillHeight = 28f
        maxHealthBarWidth = trackWidth
        maxAmmoBarWidth = trackWidth

        // --- HEALTH SECTION ---
        val hpHeaderRow = Table()
        val hpTitle = Label("VITAL SIGNS", Label.LabelStyle(fontSmall, colCyberOrange))
        modeStatusLabel = Label(
            "[${config.mode.displayName.uppercase()}] ${config.playerName.uppercase()}",
            Label.LabelStyle(fontTiny, colCyberCyan)
        )
        hpHeaderRow.add(hpTitle).left().expandX()
        hpHeaderRow.add(modeStatusLabel).right()
        statusContainer.add(hpHeaderRow).fillX().padBottom(5f).row()

        val healthFrameTable = Table()
        healthFrameTable.background = createSciFiBarBackground(barWidth.toInt(), barHeight.toInt(), tabOnTop = true)
        healthFrameTable.left().padLeft(14f).padRight(14f).padTop(16f).padBottom(6f)
        healthBarFill = Image(fillYellowDrawable)
        healthBarFillCell = healthFrameTable.add(healthBarFill).size(maxHealthBarWidth, fillHeight).left()

        // Health Stack: Embeds the number/text DIRECTLY INSIDE the progress bar
        val healthStack = Stack()
        healthStack.add(healthFrameTable)

        val healthOverlay = Table()
        healthOverlay.setFillParent(true)
        healthTextLabel = Label("100 / 100 HP", Label.LabelStyle(fontMedium, Color.WHITE))
        healthTextLabel.setAlignment(Align.center)
        healthOverlay.add(healthTextLabel).expand().center().padTop(6f)
        healthStack.add(healthOverlay)

        statusContainer.add(healthStack).size(barWidth, barHeight).padBottom(12f).row()

        // --- WEAPON & RELOAD PROGRESS SECTION ---
        val weaponHeaderRow = Table()
        weaponNameLabel = Label("PISTOL", Label.LabelStyle(fontSmall, colCyberOrange))
        weaponHeaderRow.add(weaponNameLabel).left().expandX()

        // Quick-select weapon buttons [1-3]
        val weaponSwitchRow = Table()
        WeaponType.values().forEachIndexed { idx, wType ->
            val shortTag = when (wType) {
                WeaponType.PISTOL -> "1:PISTOL"
                WeaponType.SHOTGUN -> "2:SHOTGUN"
                WeaponType.MACHINE_GUN -> "3:MG"
            }
            val wBtn = createModernButton(shortTag, 84f, 28f, fontTiny) {
                requestedWeaponSelect = wType
            }
            weaponSwitchRow.add(wBtn).padLeft(if (idx > 0) 6f else 0f)
        }
        weaponHeaderRow.add(weaponSwitchRow).right()
        statusContainer.add(weaponHeaderRow).fillX().padBottom(5f).row()

        val ammoFrameTable = Table()
        ammoFrameTable.background = createSciFiBarBackground(barWidth.toInt(), barHeight.toInt(), tabOnTop = false)
        ammoFrameTable.left().padLeft(14f).padRight(14f).padTop(6f).padBottom(16f)
        ammoBarFill = Image(fillYellowDrawable)
        ammoBarFillCell = ammoFrameTable.add(ammoBarFill).size(maxAmmoBarWidth, fillHeight).left()

        // Ammo Stack: Embeds the ammo / reload % DIRECTLY INSIDE the progress bar
        val ammoStack = Stack()
        ammoStack.add(ammoFrameTable)

        val ammoOverlay = Table()
        ammoOverlay.setFillParent(true)
        ammoCountLabel = Label("12 / 12", Label.LabelStyle(fontMedium, Color.WHITE))
        ammoCountLabel.setAlignment(Align.center)
        ammoOverlay.add(ammoCountLabel).expand().center().padBottom(6f)
        ammoStack.add(ammoOverlay)

        statusContainer.add(ammoStack).size(barWidth, barHeight).row()

        // Room Code row for Duo / Squad online modes
        if (config.mode != GameMode.OFFLINE) {
            val roomRow = Table()
            roomRow.background = createRoundedDrawable(barWidth.toInt(), 36, 8, colDarkChamber, colCyberCyan, 1)
            roomRow.pad(4f, 12f, 4f, 10f)
            val rLbl = Label(
                "ROOM CODE: ${config.roomCode.ifEmpty { "ASSIGNING..." }}",
                Label.LabelStyle(fontTiny, colCyberYellow)
            )
            roomCodeLabel = rLbl
            val copyBtn = createModernButton("COPY", 76f, 26f, fontTiny) {
                val code = networkSystem.activeRoomCode.ifEmpty { config.roomCode }
                if (code.isNotEmpty()) {
                    Gdx.app.clipboard.contents = code
                    networkSystem.postFeed("✓ Room Code [$code] copied! Share with your ${config.mode.displayName} team!")
                }
            }
            roomRow.add(rLbl).left().expandX()
            roomRow.add(copyBtn).right()
            statusContainer.add(roomRow).width(barWidth).height(36f).padTop(8f).left().row()
        }

        rootTable.add(statusContainer).left().top()

        // 2. Middle Sci-Fi Badges: Score & Frag + Combat Feed
        val middleCol = Table()
        middleCol.padLeft(26f)

        val middlePills = Table()
        val scorePill = Table()
        scorePill.background = createSciFiBadgeBackground(200, 64, colCyberOrange)
        val scoreTag = Label("SCORE", Label.LabelStyle(fontSmall, colCyberYellow))
        scoreValueLabel = Label("0", Label.LabelStyle(fontLarge, Color.WHITE))
        scorePill.add(scoreTag).padRight(12f)
        scorePill.add(scoreValueLabel)

        val fragPill = Table()
        fragPill.background = createSciFiBadgeBackground(200, 64, colCyberOrange)
        val fragTag = Label("FRAG", Label.LabelStyle(fontSmall, colCyberDanger))
        fragCountLabel = Label("0", Label.LabelStyle(fontLarge, Color.WHITE))
        fragPill.add(fragTag).padRight(12f)
        fragPill.add(fragCountLabel)

        middlePills.add(scorePill).padRight(14f)
        middlePills.add(fragPill)
        middleCol.add(middlePills).left().top().row()

        combatFeedLabel = Label("", Label.LabelStyle(fontTiny, colCyberYellow)).apply {
            setAlignment(Align.left)
        }
        middleCol.add(combatFeedLabel).left().padTop(8f)

        rootTable.add(middleCol).expandX().left().top()

        // 3. Top-Right Column: Audio Toggles + MENU Exit + Live Multiplayer Scoreboard
        val topRightCol = Table()
        val audioTable = Table()
        val sfxBtn = createModernButton("SFX", 82f, 52f) {
            val enabled = audioService.toggleSound()
            sfxButtonLabel?.setText(if (enabled) "SFX" else "MUTED")
        }
        val bgmBtn = createModernButton("BGM", 82f, 52f) {
            val enabled = audioService.toggleMusic()
            bgmButtonLabel?.setText(if (enabled) "BGM" else "MUTED")
        }
        val menuBtn = createModernButton("MENU", 88f, 52f) {
            networkSystem.disconnect()
            config.onExitToMenu()
        }
        audioTable.add(sfxBtn).padRight(8f)
        audioTable.add(bgmBtn).padRight(8f)
        audioTable.add(menuBtn)
        topRightCol.add(audioTable).right().top().row()

        // Live Scoreboard Card (shows per-player HP, K/D, Score, Roof Respawn status)
        val sbCard = Table()
        sbCard.background = createRoundedDrawable(276, 130, 10, colDarkChamber, colCyberOrange, 2)
        sbCard.pad(8f, 12f, 8f, 12f)
        val initialSbTitle = if (config.mode != GameMode.OFFLINE) {
            "ROOM [${config.roomCode.ifEmpty { "---" }}] • ${config.mode.name}"
        } else {
            "SOLO PRACTICE SCOREBOARD"
        }
        sbTitleLabel = Label(initialSbTitle, Label.LabelStyle(fontTiny, colCyberYellow))
        sbCard.add(sbTitleLabel).left().padBottom(4f).row()

        scoreboardRowsTable = Table()
        sbCard.add(scoreboardRowsTable).left().fillX()
        topRightCol.add(sbCard).right().padTop(8f).width(276f)

        rootTable.add(topRightCol).right().top()

        uiStage.addActor(rootTable)
    }

    private fun setupTouchControls() {
        // Left side movement d-pad (Larger for comfortable thumb control)
        val leftTable = Table()
        leftTable.setFillParent(true)
        leftTable.bottom().left().pad(28f)

        val btnLeft = createActionButton("<", 124f, 124f, isPrimary = false, fontOverride = fontXLarge) { touchLeft = it }
        val btnRight = createActionButton(">", 124f, 124f, isPrimary = false, fontOverride = fontXLarge) { touchRight = it }

        leftTable.add(btnLeft).padRight(24f)
        leftTable.add(btnRight)
        uiStage.addActor(leftTable)

        // Right side Action Cluster (Larger sizes with big centered text)
        val rightTable = Table()
        rightTable.setFillParent(true)
        rightTable.bottom().right().pad(28f)

        val btnJump = createActionButton("JUMP", 112f, 112f, isPrimary = false) { touchJump = it }
        val btnReload = createActionButton("RLD", 112f, 112f, isPrimary = false) { touchReload = it }
        val btnWeapon = createActionButton("SWAP", 112f, 112f, isPrimary = false) { if (it) touchSwitchWeapon = true }
        val btnGrenade = createActionButton("BOMB", 112f, 112f, isPrimary = false) { touchGrenade = it }
        val btnShoot = createActionButton("FIRE", 158f, 158f, isPrimary = true, fontOverride = fontXLarge) { touchShoot = it }

        val secCluster = Table()
        secCluster.add(btnWeapon).pad(8f)
        secCluster.add(btnJump).pad(8f).row()
        secCluster.add(btnGrenade).pad(8f)
        secCluster.add(btnReload).pad(8f)

        rightTable.add(secCluster).padRight(20f)
        rightTable.add(btnShoot)
        uiStage.addActor(rightTable)
    }

    private fun createModernButton(
        text: String,
        width: Float,
        height: Float,
        fontOverride: BitmapFont = fontSmall,
        onClick: () -> Unit
    ): Stack {
        val stack = Stack()
        val normalDrawable = createRoundedDrawable(width.toInt(), height.toInt(), 10, colButtonBg, colCyberOrange, 2)
        val pressedDrawable = createRoundedDrawable(width.toInt(), height.toInt(), 10, colButtonPressed, colCyberYellow, 2)

        val bgImage = Image(normalDrawable)
        val label = Label(text, Label.LabelStyle(fontOverride, Color.WHITE))
        label.setAlignment(Align.center)

        if (text == "SFX") sfxButtonLabel = label
        if (text == "BGM") bgmButtonLabel = label

        stack.add(bgImage)
        stack.add(label)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                bgImage.drawable = pressedDrawable
                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                bgImage.drawable = normalDrawable
                onClick()
            }
        })
        return stack
    }

    private fun createActionButton(
        text: String,
        width: Float,
        height: Float,
        isPrimary: Boolean,
        fontOverride: BitmapFont = fontLarge,
        onStateChange: (Boolean) -> Unit
    ): Stack {
        val stack = Stack()
        val normalBg = if (isPrimary) colButtonPressed else colButtonBg
        val border = if (isPrimary) colCyberYellow else colCyberOrange

        val normalDrawable = createRoundedDrawable(width.toInt(), height.toInt(), (height / 2).toInt(), normalBg, border, 3)
        val pressedDrawable = createRoundedDrawable(width.toInt(), height.toInt(), (height / 2).toInt(), colDarkChamber, colCyberYellow, 4)

        val bgImage = Image(normalDrawable)
        val label = Label(text, Label.LabelStyle(fontOverride, Color.WHITE))
        label.setAlignment(Align.center)

        stack.add(bgImage)
        stack.add(label)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.4f)
                bgImage.drawable = pressedDrawable
                onStateChange(true)
                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                bgImage.drawable = normalDrawable
                onStateChange(false)
            }
        })
        return stack
    }

    override fun onTick() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            networkSystem.disconnect()
            config.onExitToMenu()
            return
        }

        // Update local player HUD (filter out RemotePlayerComponent!)
        world.family(
            allOf = arrayOf(PlayerComponent::class, HealthComponent::class)
        ).forEach { player ->
            if (player in remotePlayerCmps) return@forEach

            val health = healthCmps[player]
            val hpRatio = (health.currentHealth / health.maxHealth).coerceIn(0f, 1f)
            val fillW = max(2f, maxHealthBarWidth * hpRatio)
            healthBarFillCell.width(fillW)
            healthFrameTableInvalidate(healthBarFill)
            healthTextLabel.setText("${health.currentHealth.toInt()} / ${health.maxHealth.toInt()} HP")

            if (hpRatio < 0.3f) {
                healthBarFill.drawable = fillDangerDrawable
                healthTextLabel.color = colCyberDanger
            } else {
                healthBarFill.drawable = fillYellowDrawable
                healthTextLabel.color = Color.WHITE
            }

            val attackCmp = attackCmps.getOrNull(player) ?: return@forEach
            val weaponCmp = weaponCmps.getOrNull(player)

            // Apply weapon click from HUD if requested
            val reqWeapon = requestedWeaponSelect
            if (reqWeapon != null && weaponCmp != null) {
                requestedWeaponSelect = null
                if (weaponCmp.currentWeapon != reqWeapon) {
                    weaponCmp.selectWeapon(reqWeapon)
                    attackCmp.isReloading = false
                    attackCmp.stateTime = 0.15f
                }
            }

            val currentWeapon = weaponCmp?.currentWeapon

            val maxAmmo = currentWeapon?.maxAmmo ?: attackCmp.maxAmmo
            val currentAmmo = if (weaponCmp != null && currentWeapon != null) {
                weaponCmp.ammoMap[currentWeapon] ?: currentWeapon.maxAmmo
            } else {
                attackCmp.ammo
            }

            val weaponName = currentWeapon?.displayName ?: "STANDARD"
            weaponNameLabel.setText("${weaponName.uppercase()} [∞ INF]")

            if (attackCmp.isReloading) {
                val reloadDuration = currentWeapon?.reloadTime ?: attackCmp.reloadTime
                val reloadRatio = (attackCmp.stateTime / reloadDuration).coerceIn(0f, 1f)
                val reloadW = max(2f, maxAmmoBarWidth * reloadRatio)
                ammoBarFill.drawable = fillAmberDrawable
                ammoBarFillCell.width(reloadW)
                healthFrameTableInvalidate(ammoBarFill)
                ammoCountLabel.setText("RELOADING ${(reloadRatio * 100).toInt()}%")
                ammoCountLabel.color = colCyberOrange
            } else {
                val ammoRatio = (currentAmmo.toFloat() / maxAmmo).coerceIn(0f, 1f)
                val ammoW = max(2f, maxAmmoBarWidth * ammoRatio)
                ammoBarFill.drawable = fillYellowDrawable
                ammoBarFillCell.width(ammoW)
                healthFrameTableInvalidate(ammoBarFill)
                ammoCountLabel.setText("$currentAmmo / $maxAmmo")
                ammoCountLabel.color = Color.WHITE
            }

            if (config.mode != GameMode.OFFLINE) {
                val connStatus = if (networkSystem.isConnected) "ONLINE" else "CONNECTING..."
                val roomCode = networkSystem.activeRoomCode.ifEmpty { config.roomCode.ifEmpty { "---" } }
                modeStatusLabel.setText(
                    "[${config.mode.displayName.uppercase()}] ${config.playerName.uppercase()} • $connStatus (${networkSystem.activePlayerCount}/${config.mode.maxPlayers})"
                )
                modeStatusLabel.color = if (networkSystem.isConnected) colCyberCyan else colCyberYellow
                roomCodeLabel?.setText("ROOM CODE: $roomCode")
                sbTitleLabel.setText("ROOM [$roomCode] • ${config.mode.name} (${networkSystem.activePlayerCount}/${config.mode.maxPlayers})")
            } else {
                modeStatusLabel.setText("[SOLO] ${config.playerName.uppercase()}")
                sbTitleLabel.setText("SOLO PRACTICE SCOREBOARD")
            }

            fragCountLabel.setText("${attackCmp.fragAmmo}")
            val playerCmp = playerCmps.getOrNull(player)
            scoreValueLabel.setText("${playerCmp?.score ?: 0}")
        }

        // Update combat feed banner
        combatFeedLabel.setText(networkSystem.combatFeedMessage)

        // Rebuild live scoreboard rows (shows each player's distinct health, K/D, and roof respawn status)
        scoreboardRowsTable.clearChildren()
        val entries = networkSystem.getScoreboardEntries()
        if (entries.isEmpty()) {
            val row = Table()
            val nameLbl = Label("${config.playerName} (YOU)".take(14), Label.LabelStyle(fontTiny, colCyberCyan))
            val hpLbl = Label("100HP", Label.LabelStyle(fontTiny, colCyberYellow))
            val kdLbl = Label("0K/0D", Label.LabelStyle(fontTiny, Color.LIGHT_GRAY))
            row.add(nameLbl).width(120f).left()
            row.add(hpLbl).width(64f).left()
            row.add(kdLbl).width(64f).right()
            scoreboardRowsTable.add(row).left().padBottom(2f).row()
        } else {
            entries.take(6).forEach { e ->
                val row = Table()
                val isDead = e.health <= 0f
                val statusText = if (isDead) "ROOF" else "${e.health.toInt()}HP"
                val rowColor = when {
                    isDead -> colCyberDanger
                    e.isLocal -> colCyberCyan
                    else -> Color.WHITE
                }
                val displayName = if (e.isLocal) "${e.name} (YOU)" else e.name
                val nameLbl = Label(displayName.take(14), Label.LabelStyle(fontTiny, rowColor))
                val hpLbl = Label(statusText, Label.LabelStyle(fontTiny, if (isDead) colCyberDanger else colCyberYellow))
                val kdLbl = Label("${e.kills}K/${e.deaths}D", Label.LabelStyle(fontTiny, Color.LIGHT_GRAY))

                row.add(nameLbl).width(120f).left()
                row.add(hpLbl).width(64f).left()
                row.add(kdLbl).width(64f).right()
                scoreboardRowsTable.add(row).left().padBottom(2f).row()
            }
        }
    }

    private fun healthFrameTableInvalidate(img: Image) {
        (img.parent as? com.badlogic.gdx.scenes.scene2d.utils.Layout)?.invalidateHierarchy()
    }

    override fun onDispose() {
        disposables.forEach { it.dispose() }
        disposables.clear()
        generatedFonts.forEach { it.dispose() }
        generatedFonts.clear()
        fontGenerator?.dispose()
        fontGenerator = null
    }
}
