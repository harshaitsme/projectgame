package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PhysicComponent

import io.github.shootgame.component.*
import kotlin.math.abs

@AllOf([MoveComponent::class, PhysicComponent::class])
class MoveSystem(
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val bulletCmps: ComponentMapper<BulletComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val fragCmps: ComponentMapper<FragComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val moveCmp = moveCmps[entity]
        val physicCmp = physicCmps[entity]
        val isBullet = entity in bulletCmps
        val isFrag = entity in fragCmps

        val body = physicCmp.body
        val currentVelocity = body.linearVelocity

        // Movement
        if (isBullet) {
            // Bullets move in their intended direction (cos, sin) at constant speed
            body.setLinearVelocity(
                moveCmp.cos * moveCmp.speed,
                moveCmp.sin * moveCmp.speed
            )
        } else if (isFrag) {
            // For grenades, we only set the horizontal speed, let gravity handle vertical
            body.setLinearVelocity(
                moveCmp.cos * moveCmp.speed,
                currentVelocity.y
            )
        } else {
            // Non-projectiles (Player) move horizontally via input
            body.setLinearVelocity(
                moveCmp.cos * moveCmp.speed,
                currentVelocity.y
            )
        }

        // Jump logic (only for non-bullets)
        if (!isBullet && moveCmp.doJump) {
            // Simple grounded check: check if vertical velocity is near 0
            if (abs(currentVelocity.y) < 0.1f) {
                body.applyLinearImpulse(0f, moveCmp.jumpImpulse, body.worldCenter.x, body.worldCenter.y, true)
            }
            moveCmp.doJump = false
        }

        // Update animation and flipping (Player only)
        if (entity in playerCmps && entity in animationCmps) {
            val aniCmp = animationCmps[entity]
            val attackCmp = attackCmps.getOrNull(entity)

            if (attackCmp != null && attackCmp.isReloading) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.RECHARGE)
            } else if (attackCmp != null && attackCmp.isThrowing) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.GRENADE)
            } else if (attackCmp != null && attackCmp.isAttacking) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.SHOT1)
            } else if (moveCmp.cos != 0f || moveCmp.sin != 0f) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.RUN)
            } else {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.IDLE)
            }
        }

        if (entity in imageCmps && moveCmp.cos != 0f) {
            val image = imageCmps[entity].image
            image.originX = image.width * 0.5f
            image.scaleX = if (moveCmp.cos < 0f) -1f else 1f
        }
    }
}
