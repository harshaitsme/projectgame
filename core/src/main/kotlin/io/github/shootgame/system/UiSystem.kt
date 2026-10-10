package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
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
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisTextButton
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameMode

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

    private lateinit var modeLabel: VisLabel
    private lateinit var healthLabel: VisLabel
    private lateinit var healthBarFill: Image
    private lateinit var weaponLabel: VisLabel
    private lateinit var ammoLabel: VisLabel
    private lateinit var statsLabel: VisLabel
    private lateinit var feedLabel: VisLabel
    private lateinit var scoreboardLabel: VisLabel

    private val weaponButtons = mutableMapOf<WeaponType, VisTextButton>()

    private var sfxBtn: VisTextButton? = null
    private var bgmBtn: VisTextButton? = null

    private var panelBgTexture: Texture? = null
    private var barBgTexture: Texture? = null
    private var barFillTexture: Texture? = null
    private var touchTexture: Texture? = null
    private var pressedTexture: Texture? = null

    private fun makeSolidTexture(r: Float, g: Float, b: Float, a: Float): Texture {
        val pixmap = Pixmap(4, 4, Pixmap.Format.RGBA8888)
        pixmap.setColor(r, g, b, a)
        pixmap.fill()
        val tex = Texture(pixmap)
        pixmap.dispose()
        return tex
    }

    private fun getTouchTexture(pressed: Boolean = false): Texture {
        if (pressed) {
            if (pressedTexture == null) {
                val pixmap = Pixmap(100, 100, Pixmap.Format.RGBA8888)
                pixmap.setColor(0.2f, 0.85f, 1f, 0.55f)
                pixmap.fillCircle(50, 50, 48)
                pixmap.setColor(1f, 1f, 1f, 0.9f)
                pixmap.drawCircle(50, 50, 48)
                pressedTexture = Texture(pixmap)
                pixmap.dispose()
            }
            return pressedTexture!!
        } else {
            if (touchTexture == null) {
                val pixmap = Pixmap(100, 100, Pixmap.Format.RGBA8888)
                pixmap.setColor(0.1f, 0.15f, 0.22f, 0.45f)
                pixmap.fillCircle(50, 50, 48)
                pixmap.setColor(0.4f, 0.85f, 1f, 0.65f)
                pixmap.drawCircle(50, 50, 48)
                touchTexture = Texture(pixmap)
                pixmap.dispose()
            }
            return touchTexture!!
        }
    }

    init {
        panelBgTexture = makeSolidTexture(0.05f, 0.08f, 0.14f, 0.78f)
        barBgTexture = makeSolidTexture(0.22f, 0.08f, 0.08f, 0.9f)
        barFillTexture = makeSolidTexture(1f, 1f, 1f, 1f)

        setupHud()
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
    }

    private fun setupHud() {
        val panelDrawable = TextureRegionDrawable(panelBgTexture!!)

        // --- 1. TOP-LEFT PLAYER HUD CARD ---
        val topLeftAnchor = Table()
        topLeftAnchor.setFillParent(true)
        topLeftAnchor.top().left()

        val hudCard = VisTable(false)
        hudCard.background = panelDrawable
        hudCard.pad(12f, 16f, 12f, 16f)

        modeLabel = VisLabel("[${config.mode.displayName}] ${config.playerName}").apply {
            color = Color(0.3f, 0.9f, 1f, 1f)
        }
        hudCard.add(modeLabel).left().padBottom(6f).row()

        // Health bar + text row
        val healthRow = VisTable(false)
        healthLabel = VisLabel("HP: 100 / 100").apply {
            color = Color(0.35f, 1f, 0.5f, 1f)
        }
        val barContainer = Table()
        barContainer.background = TextureRegionDrawable(barBgTexture!!)
        healthBarFill = Image(TextureRegionDrawable(barFillTexture!!)).apply {
            color = Color(0.25f, 0.95f, 0.4f, 1f)
        }
        barContainer.add(healthBarFill).width(160f).height(14f).left()

        healthRow.add(healthLabel).width(115f).left().padRight(10f)
        healthRow.add(barContainer).width(160f).height(14f).left()
        hudCard.add(healthRow).left().padBottom(6f).row()

        weaponLabel = VisLabel("Weapon: Pistol").apply {
            color = Color(1f, 0.9f, 0.4f, 1f)
        }
        hudCard.add(weaponLabel).left().padBottom(4f).row()

        ammoLabel = VisLabel("Ammo: 12 / 12   |   Frag: 3").apply {
            color = Color.WHITE
        }
        hudCard.add(ammoLabel).left().padBottom(4f).row()

        statsLabel = VisLabel("Kills: 0   |   Deaths: 0   |   Score: 0").apply {
            color = Color(0.75f, 0.85f, 1f, 1f)
        }
        hudCard.add(statsLabel).left().padBottom(8f).row()

        // Weapon quick-select buttons [1-4]
        val weaponBar = VisTable(false)
        WeaponType.values().forEachIndexed { idx, wType ->
            val shortName = when (wType) {
                WeaponType.PISTOL -> "1:Pistol"
                WeaponType.SHOTGUN -> "2:Shotgun"
                WeaponType.MACHINE_GUN -> "3:MG"
                WeaponType.SNIPER -> "4:Sniper"
            }
            val btn = VisTextButton(shortName).apply {
                addListener(object : ClickListener() {
                    override fun clicked(event: InputEvent?, x: Float, y: Float) {
                        audioService.play(SoundType.CLICK, volumeModifier = 0.5f)
                        requestedWeaponSelect = wType
                    }
                })
            }
            weaponButtons[wType] = btn
            weaponBar.add(btn).height(28f).padRight(if (idx < 3) 6f else 0f)
        }
        hudCard.add(weaponBar).left()

        topLeftAnchor.add(hudCard).padTop(12f).padLeft(12f)
        uiStage.addActor(topLeftAnchor)

        // --- 2. TOP-CENTER COMBAT FEED BANNER ---
        val topCenterAnchor = Table()
        topCenterAnchor.setFillParent(true)
        topCenterAnchor.top()

        feedLabel = VisLabel("").apply {
            setAlignment(Align.center)
            color = Color(1f, 0.85f, 0.25f, 1f)
            setFontScale(1.1f)
        }
        topCenterAnchor.add(feedLabel).padTop(16f)
        uiStage.addActor(topCenterAnchor)

        // --- 3. TOP-RIGHT CONTROLS & LIVE SCOREBOARD ---
        val topRightAnchor = Table()
        topRightAnchor.setFillParent(true)
        topRightAnchor.top().right()

        val rightColumn = VisTable(false)

        val btnRow = VisTable(false)
        sfxBtn = VisTextButton("SFX: ON").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    val enabled = audioService.toggleSound()
                    setText(if (enabled) "SFX: ON" else "SFX: OFF")
                }
            })
        }
        bgmBtn = VisTextButton("BGM: ON").apply {
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    val enabled = audioService.toggleMusic()
                    setText(if (enabled) "BGM: ON" else "BGM: OFF")
                }
            })
        }
        val menuBtn = VisTextButton("MENU").apply {
            color = Color(1f, 0.45f, 0.45f, 1f)
            addListener(object : ClickListener() {
                override fun clicked(event: InputEvent?, x: Float, y: Float) {
                    audioService.play(SoundType.CLICK)
                    networkSystem.disconnect()
                    config.onExitToMenu()
                }
            })
        }

        btnRow.add(sfxBtn).width(85f).height(34f).padRight(6f)
        btnRow.add(bgmBtn).width(85f).height(34f).padRight(6f)
        btnRow.add(menuBtn).width(80f).height(34f)
        rightColumn.add(btnRow).right().padBottom(8f).row()

        // Scoreboard card
        val scoreboardCard = VisTable(false)
        scoreboardCard.background = panelDrawable
        scoreboardCard.pad(10f, 14f, 10f, 14f)

        val sbTitle = VisLabel("MATCH SCOREBOARD").apply {
            color = Color(0.3f, 0.9f, 1f, 1f)
        }
        scoreboardCard.add(sbTitle).left().padBottom(4f).row()

        scoreboardLabel = VisLabel("Waiting for players...").apply {
            color = Color.WHITE
        }
        scoreboardCard.add(scoreboardLabel).left()

        rightColumn.add(scoreboardCard).right()
        topRightAnchor.add(rightColumn).padTop(12f).padRight(12f)
        uiStage.addActor(topRightAnchor)
    }

    private fun setupTouchControls() {
        val leftTable = Table()
        leftTable.setFillParent(true)
        leftTable.bottom().left()

        val btnSize = 115f
        val btnLeft = createTouchButton("LEFT") { touchLeft = it }
        val btnRight = createTouchButton("RIGHT") { touchRight = it }

        val moveTable = Table()
        moveTable.add(btnLeft).size(btnSize).padRight(28f)
        moveTable.add(btnRight).size(btnSize)

        leftTable.add(moveTable).pad(45f)
        uiStage.addActor(leftTable)

        val rightTable = Table()
        rightTable.setFillParent(true)
        rightTable.bottom().right()

        val btnShoot = createTouchButton("FIRE") { touchShoot = it }
        val btnReload = createTouchButton("RELOAD") { touchReload = it }
        val btnWeapon = createTouchButton("GUN") { if (it) touchSwitchWeapon = true }
        val btnJump = createTouchButton("JUMP") { touchJump = it }
        val btnGrenade = createTouchButton("FRAG") { touchGrenade = it }

        val actionGrid = Table()
        actionGrid.add(btnWeapon).size(95f).pad(8f)
        actionGrid.add(btnReload).size(95f).pad(8f).row()
        actionGrid.add(btnGrenade).size(95f).pad(8f)
        actionGrid.add(btnJump).size(105f).pad(8f)

        rightTable.add(actionGrid).padRight(20f).padBottom(35f)
        rightTable.add(btnShoot).size(150f).padRight(45f).padBottom(45f)
        uiStage.addActor(rightTable)
    }

    private fun createTouchButton(text: String, action: (Boolean) -> Unit): Stack {
        val stack = Stack()
        val image = Image(getTouchTexture(false))

        val label = VisLabel(text).apply {
            setAlignment(Align.center)
        }

        stack.add(image)
        stack.add(label)

        stack.addListener(object : ClickListener() {
            override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                audioService.play(SoundType.CLICK, volumeModifier = 0.4f)
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

    override fun onTick() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            networkSystem.disconnect()
            config.onExitToMenu()
            return
        }

        // Update combat feed banner
        feedLabel.setText(networkSystem.combatFeedMessage)

        // Update local player HUD (filter out RemotePlayerComponent!)
        world.family(
            allOf = arrayOf(PlayerComponent::class, HealthComponent::class)
        ).forEach { player ->
            if (player in remotePlayerCmps) return@forEach

            val health = healthCmps[player]
            val hpInt = health.currentHealth.toInt().coerceAtLeast(0)
            val maxHpInt = health.maxHealth.toInt().coerceAtLeast(1)
            val ratio = (health.currentHealth / health.maxHealth).coerceIn(0f, 1f)

            if (player in deadCmps) {
                healthLabel.setText("RESPAWNING...")
                healthLabel.color = Color(1f, 0.35f, 0.35f, 1f)
            } else {
                healthLabel.setText("HP: $hpInt / $maxHpInt")
                healthLabel.color = when {
                    ratio > 0.5f -> Color(0.35f, 1f, 0.5f, 1f)
                    ratio > 0.25f -> Color(1f, 0.8f, 0.2f, 1f)
                    else -> Color(1f, 0.3f, 0.3f, 1f)
                }
            }

            healthBarFill.setSize(160f * ratio, 14f)
            healthBarFill.color = when {
                ratio > 0.5f -> Color(0.25f, 0.95f, 0.4f, 1f)
                ratio > 0.25f -> Color(1f, 0.75f, 0.2f, 1f)
                else -> Color(1f, 0.25f, 0.25f, 1f)
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

            if (weaponCmp != null && currentWeapon != null) {
                val currentAmmo = weaponCmp.ammoMap[currentWeapon] ?: currentWeapon.maxAmmo
                weaponLabel.setText("Weapon: ${currentWeapon.displayName}  [1-4 / Q,E]")
                val reloadTag = if (attackCmp.isReloading) " (RELOADING...)" else ""
                ammoLabel.setText("Ammo: $currentAmmo / ${currentWeapon.maxAmmo}$reloadTag   |   Frag: ${attackCmp.fragAmmo}")

                weaponButtons.forEach { (wType, btn) ->
                    btn.color = if (wType == currentWeapon) {
                        Color(0.25f, 0.95f, 1f, 1f)
                    } else {
                        Color(0.65f, 0.7f, 0.75f, 1f)
                    }
                }
            } else {
                ammoLabel.setText("Ammo: ${attackCmp.ammo} / ${attackCmp.maxAmmo}   |   Frag: ${attackCmp.fragAmmo}")
            }

            val playerCmp = playerCmps[player]
            statsLabel.setText("Kills: ${playerCmp.kills}   |   Deaths: ${playerCmp.deaths}   |   Score: ${playerCmp.score}")

            if (config.mode != GameMode.OFFLINE) {
                val connStatus = if (networkSystem.isConnected) "ONLINE" else "CONNECTING..."
                modeLabel.setText("[${config.mode.displayName}] ${config.playerName} • $connStatus (${networkSystem.activePlayerCount}/${config.mode.maxPlayers})")
                modeLabel.color = if (networkSystem.isConnected) Color(0.3f, 0.95f, 1f, 1f) else Color(1f, 0.8f, 0.3f, 1f)
            } else {
                modeLabel.setText("[Solo Practice] ${config.playerName}")
            }
        }

        // Update Live Scoreboard
        val entries = networkSystem.getScoreboardEntries()
        if (entries.isEmpty()) {
            scoreboardLabel.setText("${config.playerName} — 100 HP | 0K / 0D")
        } else {
            val sb = StringBuilder()
            entries.forEachIndexed { idx, e ->
                val youTag = if (e.isLocal) " (YOU)" else ""
                val hpStr = if (e.health <= 0f) "DEAD" else "${e.health.toInt()} HP"
                sb.append("${idx + 1}. ${e.name}$youTag  —  $hpStr  |  ${e.kills}K / ${e.deaths}D")
                if (idx < entries.lastIndex) sb.append("\n")
            }
            scoreboardLabel.setText(sb.toString())
        }
    }

    override fun onDispose() {
        panelBgTexture?.dispose()
        barBgTexture?.dispose()
        barFillTexture?.dispose()
        touchTexture?.dispose()
        pressedTexture?.dispose()
    }
}
