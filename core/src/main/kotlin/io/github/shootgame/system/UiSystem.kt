package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener
import com.github.quillraven.fleks.IntervalSystem

class UiSystem(
    private val stage: Stage
) : IntervalSystem() {

    var touchUp = false
    var touchDown = false
    var touchLeft = false
    var touchRight = false

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
        val table = Table()
        table.name = "uiTable"
        table.setFillParent(true)
        table.bottom().left()

        // 2f units = 64 pixels at 32 pixels/unit scale (Main.UNIT_SCALE = 1/32)
        val btnSize = 2f
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

        table.add(dpadTable).pad(0.5f)
        stage.addActor(table)
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
        // Keep UI at the front of the stage
        stage.root.children.lastOrNull()?.let { last ->
            val table = stage.root.findActor<Table>("uiTable")
            if (table != null && last != table) {
                table.toFront()
            }
        }
    }

    override fun onDispose() {
        touchTexture?.dispose()
    }
}
