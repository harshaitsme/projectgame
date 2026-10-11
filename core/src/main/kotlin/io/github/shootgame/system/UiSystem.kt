package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Qualifier
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.WeaponComponent
import kotlin.math.max

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val audioService: AudioService
) : IntervalSystem() {

    var touchLeft = false
    var touchRight = false
    var touchShoot = false
    var touchReload = false
    var touchJump = false
    var touchGrenade = false
    var touchSwitchWeapon = false

    fun consumeWeaponSwitch(): Boolean {
        val res = touchSwitchWeapon
        touchSwitchWeapon = false
        return res
    }

    private val disposables = mutableListOf<Texture>()

    // Color Palette inspired by stylized 3D mobile battlegrounds
    private val colBgDark = Color(0.06f, 0.09f, 0.16f, 0.85f)
    private val colCardBg = Color(0.10f, 0.15f, 0.25f, 0.90f)
    private val colBorderSoft = Color(0.20f, 0.32f, 0.50f, 0.70f)
    private val colCyanGlow = Color(0.22f, 0.74f, 0.96f, 1f)
    private val colHealthGreen = Color(0.18f, 0.82f, 0.44f, 1f)
    private val colHealthDanger = Color(0.92f, 0.25f, 0.30f, 1f)
    private val colAmber = Color(0.98f, 0.72f, 0.18f, 1f)
    private val colButtonNormal = Color(0.12f, 0.19f, 0.32f, 0.85f)
    private val colButtonPressed = Color(0.24f, 0.40f, 0.65f, 0.95f)
    private val colActionPrimary = Color(0.15f, 0.42f, 0.85f, 0.90f)

    // HUD Elements
    private lateinit var healthBarFill: Image
    private lateinit var healthBarFillCell: Cell<Image>
    private lateinit var healthTextLabel: Label
    private lateinit var weaponNameLabel: Label
    private lateinit var ammoCountLabel: Label
    private lateinit var fragCountLabel: Label
    private lateinit var scoreValueLabel: Label
    private var sfxButtonLabel: Label? = null
    private var bgmButtonLabel: Label? = null

    private val maxHealthBarWidth = 190f

    init {
        setupHud()
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
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

        // Background or border pass
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
        // 4 corner circles
        pixmap.fillCircle(offsetX + rad, offsetY + rad, rad)
        pixmap.fillCircle(offsetX + w - rad - 1, offsetY + rad, rad)
        pixmap.fillCircle(offsetX + rad, offsetY + h - rad - 1, rad)
        pixmap.fillCircle(offsetX + w - rad - 1, offsetY + h - rad - 1, rad)
        // Cross rectangles
        pixmap.fillRectangle(offsetX + rad, offsetY, w - rad * 2, h)
        pixmap.fillRectangle(offsetX, offsetY + rad, w, h - rad * 2)
    }

    private fun setupHud() {
        // --- Top Bar Container (Left to Right) ---
        val rootTable = Table()
        rootTable.setFillParent(true)
        rootTable.top().pad(16f)

        // 1. Player Status Card (Health + Weapon)
        val statusCard = Table()
        statusCard.background = createRoundedDrawable(280, 110, 16, colCardBg, colBorderSoft, 2)
        statusCard.pad(10f, 14f, 10f, 14f)

        // Health Section
        val hpTitle = Label("VITAL SIGNS", Label.LabelStyle(BitmapFont(), colCyanGlow))
        healthTextLabel = Label("100 / 100", Label.LabelStyle(BitmapFont(), Color.WHITE))

        val healthTrack = Table()
        healthTrack.background = createRoundedDrawable(200, 18, 9, colBgDark, colBorderSoft, 1)

        val fillDrawable = createRoundedDrawable(200, 14, 7, colHealthGreen)
        healthBarFill = Image(fillDrawable)
        healthTrack.left()
        healthBarFillCell = healthTrack.add(healthBarFill).size(maxHealthBarWidth, 14f).pad(2f)

        statusCard.add(hpTitle).left().padBottom(3f)
        statusCard.add(healthTextLabel).right().padBottom(3f).row()
        statusCard.add(healthTrack).colspan(2).fillX().padBottom(8f).row()

        // Weapon & Ammo Section
        val weaponRow = Table()
        weaponNameLabel = Label("Pistol", Label.LabelStyle(BitmapFont(), colCyanGlow))
        ammoCountLabel = Label("12 / 12", Label.LabelStyle(BitmapFont(), Color.WHITE))
        weaponRow.add(weaponNameLabel).left().expandX()
        weaponRow.add(ammoCountLabel).right()
        statusCard.add(weaponRow).colspan(2).fillX()

        rootTable.add(statusCard).left().top()

        // 2. Score & Frag Badge Pills (Center / Top-Middle)
        val middlePills = Table()
        middlePills.padLeft(18f)

        // Score Pill
        val scorePill = Table()
        scorePill.background = createRoundedDrawable(140, 44, 22, colCardBg, colBorderSoft, 2)
        val scoreTag = Label("SCORE", Label.LabelStyle(BitmapFont(), colAmber))
        scoreValueLabel = Label("0", Label.LabelStyle(BitmapFont(), Color.WHITE))
        scorePill.add(scoreTag).padRight(8f)
        scorePill.add(scoreValueLabel)

        // Frag Pill
        val fragPill = Table()
        fragPill.background = createRoundedDrawable(130, 44, 22, colCardBg, colBorderSoft, 2)
        val fragTag = Label("FRAG", Label.LabelStyle(BitmapFont(), colHealthDanger))
        fragCountLabel = Label("0", Label.LabelStyle(BitmapFont(), Color.WHITE))
        fragPill.add(fragTag).padRight(8f)
        fragPill.add(fragCountLabel)

        middlePills.add(scorePill).padRight(10f)
        middlePills.add(fragPill)
        rootTable.add(middlePills).expandX().left().top()

        // 3. Audio Quick-Toggles (Top-Right)
        val audioTable = Table()
        val sfxBtn = createModernButton("SFX", 60f, 44f) {
            val enabled = audioService.toggleSound()
            sfxButtonLabel?.setText(if (enabled) "SFX" else "MUTED")
        }
        val bgmBtn = createModernButton("BGM", 60f, 44f) {
            val enabled = audioService.toggleMusic()
            bgmButtonLabel?.setText(if (enabled) "BGM" else "MUTED")
        }
        audioTable.add(sfxBtn).padRight(8f)
        audioTable.add(bgmBtn)
        rootTable.add(audioTable).right().top()

        uiStage.addActor(rootTable)
    }

    private fun setupTouchControls() {
        // Left side movement d-pad
        val leftTable = Table()
        leftTable.setFillParent(true)
        leftTable.bottom().left().pad(24f)

        val btnLeft = createActionButton("<", 92f, 92f, isPrimary = false) { touchLeft = it }
        val btnRight = createActionButton(">", 92f, 92f, isPrimary = false) { touchRight = it }

        leftTable.add(btnLeft).padRight(20f)
        leftTable.add(btnRight)
        uiStage.addActor(leftTable)

        // Right side Action Cluster
        val rightTable = Table()
        rightTable.setFillParent(true)
        rightTable.bottom().right().pad(24f)

        val btnJump = createActionButton("JUMP", 80f, 80f, isPrimary = false) { touchJump = it }
        val btnReload = createActionButton("RLD", 80f, 80f, isPrimary = false) { touchReload = it }
        val btnWeapon = createActionButton("SWAP", 80f, 80f, isPrimary = false) { if (it) touchSwitchWeapon = true }
        val btnGrenade = createActionButton("BOMB", 80f, 80f, isPrimary = false) { touchGrenade = it }
        val btnShoot = createActionButton("FIRE", 112f, 112f, isPrimary = true) { touchShoot = it }

        val secCluster = Table()
        secCluster.add(btnWeapon).pad(6f)
        secCluster.add(btnJump).pad(6f).row()
        secCluster.add(btnGrenade).pad(6f)
        secCluster.add(btnReload).pad(6f)

        rightTable.add(secCluster).padRight(16f)
        rightTable.add(btnShoot)
        uiStage.addActor(rightTable)
    }

    private fun createModernButton(
        text: String,
        width: Float,
        height: Float,
        onClick: () -> Unit
    ): Stack {
        val stack = Stack()
        val normalDrawable = createRoundedDrawable(width.toInt(), height.toInt(), 12, colButtonNormal, colBorderSoft, 2)
        val pressedDrawable = createRoundedDrawable(width.toInt(), height.toInt(), 12, colButtonPressed, colCyanGlow, 2)

        val bgImage = Image(normalDrawable)
        val label = Label(text, Label.LabelStyle(BitmapFont(), Color.WHITE))
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
        onStateChange: (Boolean) -> Unit
    ): Stack {
        val stack = Stack()
        val normalBg = if (isPrimary) colActionPrimary else colButtonNormal
        val border = if (isPrimary) colCyanGlow else colBorderSoft

        val normalDrawable = createRoundedDrawable(width.toInt(), height.toInt(), (height / 2).toInt(), normalBg, border, 2)
        val pressedDrawable = createRoundedDrawable(width.toInt(), height.toInt(), (height / 2).toInt(), colButtonPressed, colCyanGlow, 3)

        val bgImage = Image(normalDrawable)
        val label = Label(text, Label.LabelStyle(BitmapFont(), Color.WHITE))
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
        world.family(
            allOf = arrayOf(PlayerComponent::class, HealthComponent::class)
        ).forEach { player ->
            val health = healthCmps[player]
            val hpRatio = (health.currentHealth / health.maxHealth).coerceIn(0f, 1f)
            val fillW = max(2f, maxHealthBarWidth * hpRatio)
            healthBarFillCell.width(fillW)
            healthTextLabel.setText("${health.currentHealth.toInt()} / ${health.maxHealth.toInt()} HP")

            if (hpRatio < 0.3f) {
                healthTextLabel.color = colHealthDanger
            } else {
                healthTextLabel.color = Color.WHITE
            }

            val attackCmp = attackCmps.getOrNull(player) ?: return@forEach
            val weaponCmp = weaponCmps.getOrNull(player)
            val currentWeapon = weaponCmp?.currentWeapon

            if (weaponCmp != null && currentWeapon != null) {
                val currentAmmo = weaponCmp.ammoMap[currentWeapon] ?: currentWeapon.maxAmmo
                weaponNameLabel.setText(currentWeapon.displayName)
                ammoCountLabel.setText("$currentAmmo / ${currentWeapon.maxAmmo}${if (attackCmp.isReloading) " [RLD]" else ""}")
            } else {
                weaponNameLabel.setText("Standard")
                ammoCountLabel.setText("${attackCmp.ammo} / ${attackCmp.maxAmmo}${if (attackCmp.isReloading) " [RLD]" else ""}")
            }

            fragCountLabel.setText("${attackCmp.fragAmmo}")
            val playerCmp = playerCmps.getOrNull(player)
            scoreValueLabel.setText("${playerCmp?.score ?: 0}")
        }
    }

    override fun onDispose() {
        disposables.forEach { it.dispose() }
        disposables.clear()
    }
}
