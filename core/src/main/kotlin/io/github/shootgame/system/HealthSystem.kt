package io.github.shootgame.system

import com.badlogic.gdx.graphics.g2d.Animation
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationModel
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PhysicComponent

@AllOf([HealthComponent::class])
class HealthSystem(
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val health = healthCmps[entity]
        if (health.invulnerableTime > 0f) {
            health.invulnerableTime = (health.invulnerableTime - deltaTime).coerceAtLeast(0f)
        }
    }

    fun damagePercent(entity: Entity, fraction: Float): Boolean {
        val health = healthCmps.getOrNull(entity) ?: return false
        if (fraction <= 0f) return false
        return damage(entity, health.maxHealth * fraction.coerceAtMost(1f))
    }

    fun damage(entity: Entity, amount: Float): Boolean {
        val health = healthCmps.getOrNull(entity) ?: return false
        if (health.isDead || health.invulnerableTime > 0f || amount <= 0f) return false

        health.currentHealth = (health.currentHealth - amount).coerceAtLeast(0f)
        health.invulnerableTime = INVULNERABILITY_DURATION

        if (health.currentHealth <= 0f) {
            health.isDead = true
            kill(entity)
        }

        return true
    }

    private fun kill(entity: Entity) {
        world.configureEntity(entity) {
            deadCmps.add(entity) {
                time = 0f
            }
            if (entity in moveCmps) {
                moveCmps.remove(entity)
            }
            if (entity in physicCmps) {
                physicCmps.remove(entity)
            }
        }

        val animation = animationCmps.getOrNull(entity) ?: return
        animation.nextAnimation(AnimationModel.PLAYER, AnimationType.DEAD)
        animation.playMode = Animation.PlayMode.NORMAL
    }

    companion object {
        const val INVULNERABILITY_DURATION = 0.5f
    }
}
