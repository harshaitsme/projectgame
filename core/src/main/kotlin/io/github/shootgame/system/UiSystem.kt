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
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.PlayerComponent

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage,
    private val attackCmps: ComponentMapper<AttackComponent>
) : IntervalSystem() {

    var touchLeft = false
    var touchRight = false
    var touchShoot = false
    var touchReload = false

    private lateinit var ammoLabel: Label

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
        if (Gdx.app.type == Application.ApplicationType.Android) {
            setupTouchControls()
        }
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

        rightTable.add(btnReload).size(btnSize).padBottom(30f).row()
        rightTable.add(btnShoot).size(btnSize * 1.6f).pad(60f)
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
        world.family(allOf = arrayOf(PlayerComponent::class, AttackComponent::class)).forEach { player ->
            val attackCmp = attackCmps[player]
            ammoLabel.setText("Ammo: ${attackCmp.ammo} / ${attackCmp.maxAmmo}${if (attackCmp.isReloading) " (Reloading...)" else ""}")
        }
    }

    override fun onDispose() {
        touchTexture?.dispose()
        pressedTexture?.dispose()
    }
}
