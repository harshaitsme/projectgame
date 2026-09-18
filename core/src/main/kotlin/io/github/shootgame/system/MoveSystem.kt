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

@AllOf([MoveComponent::class, PhysicComponent::class])
class MoveSystem(
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val moveCmp = moveCmps[entity]
        val physicCmp = physicCmps[entity]

        val body = physicCmp.body
        body.setLinearVelocity(
            moveCmp.cos * moveCmp.speed,
            moveCmp.sin * moveCmp.speed
        )

        // Update animation and flipping
        if (entity in animationCmps) {
            val aniCmp = animationCmps[entity]
            val attackCmp = attackCmps.getOrNull(entity)

            if (attackCmp != null && attackCmp.isReloading) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.RECHARGE)
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
