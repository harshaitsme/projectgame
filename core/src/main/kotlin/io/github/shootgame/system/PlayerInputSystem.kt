package io.github.shootgame.system

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PlayerComponent

@AllOf([PlayerComponent::class, MoveComponent::class, AttackComponent::class])
class PlayerInputSystem(
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>
) : IteratingSystem() {
    private val uiSystem: UiSystem by lazy { world.system<UiSystem>() }

    override fun onTickEntity(entity: Entity) {
        val moveCmp = moveCmps[entity]
        val attackCmp = attackCmps[entity]

        var x = 0f
        var y = 0f

        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP) || uiSystem.touchUp) y += 1f
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN) || uiSystem.touchDown) y -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT) || uiSystem.touchLeft) x -= 1f
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT) || uiSystem.touchRight) x += 1f

        if (x != 0f || y != 0f) {
            val len = Math.sqrt((x * x + y * y).toDouble()).toFloat()
            moveCmp.cos = x / len
            moveCmp.sin = y / len
            moveCmp.lastCos = moveCmp.cos
            moveCmp.lastSin = moveCmp.sin
        } else {
            moveCmp.cos = 0f
            moveCmp.sin = 0f
        }

        attackCmp.isAttacking = Gdx.input.isKeyPressed(Input.Keys.SPACE) || uiSystem.touchShoot

        if (Gdx.input.isKeyJustPressed(Input.Keys.R) || uiSystem.touchReload) {
            attackCmp.isReloading = true
        }
    }
}
