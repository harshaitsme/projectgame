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
import com.github.quillraven.fleks.Qualifier

class UiSystem(
    @Qualifier("uiStage") private val uiStage: Stage
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

        // Size in pixels for ScreenViewport
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

        table.add(dpadTable).pad(20f)
        uiStage.addActor(table)
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
        // uiStage.act(deltaTime) is handled by RenderSystem or here
    }

    override fun onDispose() {
        touchTexture?.dispose()
    }
}
