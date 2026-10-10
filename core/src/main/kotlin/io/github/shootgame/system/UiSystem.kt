package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
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
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameMode

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
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

    fun consumeWeaponSwitch(): Boolean {
        val res = touchSwitchWeapon
        touchSwitchWeapon = false
        return res
    }

    private lateinit var healthLabel: Label
    private lateinit var weaponLabel: Label
    private lateinit var ammoLabel: Label
    private lateinit var fragLabel: Label
    private lateinit var scoreLabel: Label
    private lateinit var modeLabel: Label
    private var sfxButtonLabel: Label? = null
    private var bgmButtonLabel: Label? = null

    private var touchTexture: Texture? = null
    private var pressedTexture: Texture? = null

    private fun getTouchTexture(pressed: Boolean = false): Texture {
        if (pressed) {
            if (pressedTexture == null) {
                val pixmap = Pixmap(100, 100, Pixmap.Format.RGBA8888)
                pixmap.setColor(1f, 1f, 1f, 0.5f) // Brighter for pressed
                pixmap.fillCircle(50, 50, 48)
                pixmap.setColor(1f, 1f, 1f, 0.8f)
                pixmap.drawCircle(50, 50, 48)
                pressedTexture = Texture(pixmap)
                pixmap.dispose()
            }
            return pressedTexture!!
        } else {
            if (touchTexture == null) {
                val pixmap = Pixmap(100, 100, Pixmap.Format.RGBA8888)
                pixmap.setColor(1f, 1f, 1f, 0.2f) // Fainter for normal
                pixmap.fillCircle(50, 50, 48)
                pixmap.setColor(1f, 1f, 1f, 0.5f)
                pixmap.drawCircle(50, 50, 48)
                touchTexture = Texture(pixmap)
                pixmap.dispose()
            }
            return touchTexture!!
        }
    }

    init {
        setupHud()
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
    }

    private fun setupHud() {
        val hudTable = Table()
        hudTable.setFillParent(true)
        hudTable.top().left()

        modeLabel = Label("Mode: ${config.mode.displayName}", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        healthLabel = Label("Health: 100 / 100", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        weaponLabel = Label("Weapon: Pistol [1-4 / Q,E]", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        ammoLabel = Label("Ammo: 0/0", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        fragLabel = Label("Frag: 0", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        scoreLabel = Label("Score: 0", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        hudTable.add(modeLabel).padLeft(20f).padTop(15f).padBottom(3f).row()
        hudTable.add(healthLabel).padLeft(20f).padBottom(3f).row()
        hudTable.add(weaponLabel).padLeft(20f).padBottom(3f).row()
        hudTable.add(ammoLabel).padLeft(20f).padBottom(3f).row()
        hudTable.add(fragLabel).padLeft(20f).padBottom(3f).row()
        hudTable.add(scoreLabel).padLeft(20f)
        uiStage.addActor(hudTable)

        // HUD - Top Right Audio Toggles
        val audioTable = Table()
        audioTable.setFillParent(true)
        audioTable.top().right()

        val sfxBtn = createToggleButton("SFX: ON") {
            val enabled = audioService.toggleSound()
            sfxButtonLabel?.setText(if (enabled) "SFX: ON" else "SFX: OFF")
        }
        val bgmBtn = createToggleButton("BGM: ON") {
            val enabled = audioService.toggleMusic()
            bgmButtonLabel?.setText(if (enabled) "BGM: ON" else "BGM: OFF")
        }

        audioTable.add(sfxBtn).size(110f, 50f).padTop(20f).padRight(15f)
        audioTable.add(bgmBtn).size(110f, 50f).padTop(20f).padRight(20f)
        uiStage.addActor(audioTable)
    }

    private fun setupTouchControls() {
        // Left side movement buttons (Left/Right only)
        val leftTable = Table()
        leftTable.setFillParent(true)
        leftTable.bottom().left()

        val btnSize = 120f
        val btnLeft = createButton("L") { touchLeft = it }
        val btnRight = createButton("R") { touchRight = it }

        val moveTable = Table()
        moveTable.add(btnLeft).size(btnSize).padRight(30f)
        moveTable.add(btnRight).size(btnSize)

        leftTable.add(moveTable).pad(60f)
        uiStage.addActor(leftTable)

        // Right side Shoot button
        val rightTable = Table()
        rightTable.setFillParent(true)
        rightTable.bottom().right()

        val btnShoot = createButton("SHOOT") { touchShoot = it }
        val btnReload = createButton("RELOAD") { touchReload = it }
        val btnWeapon = createButton("GUN") { if (it) touchSwitchWeapon = true }
        val btnJump = createButton("JUMP") { touchJump = it }
        val btnGrenade = createButton("GRENADE") { touchGrenade = it }

        rightTable.add(btnWeapon).size(btnSize).padBottom(20f).row()
        rightTable.add(btnReload).size(btnSize).padBottom(20f).row()
        rightTable.add(btnJump).size(btnSize).padBottom(20f).row()
        rightTable.add(btnGrenade).size(btnSize).padBottom(20f).row()
        rightTable.add(btnShoot).size(btnSize * 1.6f).pad(60f)
        uiStage.addActor(rightTable)
    }

    private fun createButton(text: String, action: (Boolean) -> Unit): Stack {
        val stack = Stack()
        val image = Image(getTouchTexture())

        val label = Label(text, Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        label.setAlignment(Align.center)

        stack.add(image)
        stack.add(label)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                action(true)
                image.drawable = TextureRegionDrawable(getTouchTexture(true))
                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                action(false)
                image.drawable = TextureRegionDrawable(getTouchTexture(false))
            }
        })
        return stack
    }

    private fun createToggleButton(initialText: String, onToggle: () -> Unit): Stack {
        val stack = Stack()
        val image = Image(getTouchTexture())

        val label = Label(initialText, Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont()
        })
        label.setAlignment(Align.center)

        if (initialText.startsWith("SFX")) {
            sfxButtonLabel = label
        } else if (initialText.startsWith("BGM")) {
            bgmButtonLabel = label
        }

        stack.add(image)
        stack.add(label)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                image.drawable = TextureRegionDrawable(getTouchTexture(true))
                return true
            }

            override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                image.drawable = TextureRegionDrawable(getTouchTexture(false))
                onToggle()
            }
        })
        return stack
    }

    override fun onTick() {
        world.family(
            allOf = arrayOf(PlayerComponent::class, HealthComponent::class)
        ).forEach { player ->
            val health = healthCmps[player]
            healthLabel.setText("Health: ${health.currentHealth.toInt()} / ${health.maxHealth.toInt()}")

            val attackCmp = attackCmps.getOrNull(player) ?: return@forEach
            val weaponCmp = weaponCmps.getOrNull(player)
            val currentWeapon = weaponCmp?.currentWeapon

            if (weaponCmp != null && currentWeapon != null) {
                val currentAmmo = weaponCmp.ammoMap[currentWeapon] ?: currentWeapon.maxAmmo
                weaponLabel.setText("Weapon: ${currentWeapon.displayName} [1-4 / Q,E]")
                ammoLabel.setText("Ammo: $currentAmmo / ${currentWeapon.maxAmmo}${if (attackCmp.isReloading) " (Reloading...)" else ""}")
            } else {
                ammoLabel.setText("Ammo: ${attackCmp.ammo} / ${attackCmp.maxAmmo}${if (attackCmp.isReloading) " (Reloading...)" else ""}")
            }
            fragLabel.setText("Frag: ${attackCmp.fragAmmo}")
            val playerCmp = playerCmps.getOrNull(player)
            scoreLabel.setText("Score: ${playerCmp?.score ?: 0}")

            if (config.mode != io.github.shootgame.screen.GameMode.OFFLINE) {
                val connStatus = if (networkSystem.isConnected) "Online" else "Connecting..."
                modeLabel.setText("[${config.mode.displayName}] ${config.playerName} | Players: ${networkSystem.activePlayerCount}/${config.mode.maxPlayers} ($connStatus)")
            } else {
                modeLabel.setText("[Solo Practice] ${config.playerName}")
            }
        }
    }

    override fun onDispose() {
        touchTexture?.dispose()
        pressedTexture?.dispose()
    }
}
