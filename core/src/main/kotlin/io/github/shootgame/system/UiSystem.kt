package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Qualifier
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.PlayerComponent

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage,
    private val attackCmps: ComponentMapper<AttackComponent>
) : IntervalSystem() {

    var touchUp = false
    var touchDown = false
    var touchLeft = false
    var touchRight = false
    var touchShoot = false
    var touchReload = false

    private lateinit var ammoLabel: Label

    private var touchTexture: Texture? = null

    private fun getTouchTexture(): Texture {
        if (touchTexture == null) {
            val pixmap = Pixmap(64, 64, Pixmap.Format.RGBA8888)
            pixmap.setColor(1f, 1f, 1f, 0.3f)
            pixmap.fillCircle(32, 32, 30)
            touchTexture = Texture(pixmap)
            pixmap.dispose()
        }
        return touchTexture!!
    }

    init {
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
    }

    private fun setupTouchControls() {
        // Left side D-pad
        val leftTable = Table()
        leftTable.setFillParent(true)
        leftTable.bottom().left()

        val btnSize = 100f
        val btnUp = createButton { touchUp = it }
        val btnDown = createButton { touchDown = it }
        val btnLeft = createButton { touchLeft = it }
        val btnRight = createButton { touchRight = it }

        val dpadTable = Table()
        dpadTable.add().size(btnSize)
        dpadTable.add(btnUp).size(btnSize)
        dpadTable.add().size(btnSize)
        dpadTable.row()
        dpadTable.add(btnLeft).size(btnSize)
        dpadTable.add().size(btnSize)
        dpadTable.add(btnRight).size(btnSize)
        dpadTable.row()
        dpadTable.add().size(btnSize)
        dpadTable.add(btnDown).size(btnSize)
        dpadTable.add().size(btnSize)

        leftTable.add(dpadTable).pad(20f)
        uiStage.addActor(leftTable)

        // Right side Shoot buttonHeads-Up Display
        val rightTable = Table()
        rightTable.setFillParent(true)
        rightTable.bottom().right()

        val btnShoot = createButton { touchShoot = it }
        val btnReload = createButton { touchReload = it }

        rightTable.add(btnReload).size(btnSize).padBottom(20f).row()
        rightTable.add(btnShoot).size(btnSize * 1.5f).pad(40f)
        uiStage.addActor(rightTable)

        // HUD - Top Left Ammo Counter
        val hudTable = Table()
        hudTable.setFillParent(true)
        hudTable.top().left()

        ammoLabel = Label("Ammo: 0/0", Label.LabelStyle().apply {
            font = com.badlogic.gdx.graphics.g2d.BitmapFont() // Using default for now
        })
        hudTable.add(ammoLabel).pad(20f)
        uiStage.addActor(hudTable)
    }

    private fun createButton(action: (Boolean) -> Unit): Image {
        return Image(getTouchTexture()).apply {
            addListener(object : ClickListener() {
                override fun touchDown(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    action(true)
                    return true
                }

                override fun touchUp(event: InputEvent?, x: Float, y: Float, pointer: Int, button: Int) {
                    action(false)
                }
            })
        }
    }

    override fun onTick() {
        world.family(allOf = arrayOf(PlayerComponent::class, AttackComponent::class)).forEach { player ->
            val attackCmp = attackCmps[player]
            ammoLabel.setText("Ammo: ${attackCmp.ammo} / ${attackCmp.maxAmmo}${if (attackCmp.isReloading) " (Reloading...)" else ""}")
        }
    }

    override fun onDispose() {
        touchTexture?.dispose()
    }
}
