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
import io.github.shootgame.component.EnemyComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent

@AllOf([HealthComponent::class])
class HealthSystem(
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val enemyCmps: ComponentMapper<EnemyComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>
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

    fun damage(entity: Entity, amount: Float, fromNetwork: Boolean = false): Boolean {
        val health = healthCmps.getOrNull(entity) ?: return false
        if (health.isDead || amount <= 0f) return false
        if (!fromNetwork && health.invulnerableTime > 0f) return false

        health.currentHealth = (health.currentHealth - amount).coerceAtLeast(0f)
        health.invulnerableTime = INVULNERABILITY_DURATION

        if (health.currentHealth <= 0f) {
            health.isDead = true
            kill(entity)
        } else {
            // Brief hurt animation if not currently attacking
            val anim = animationCmps.getOrNull(entity)
            if (anim != null && anim.type == AnimationType.IDLE) {
                anim.nextAnimation(AnimationModel.PLAYER, AnimationType.HURT)
            }
        }

        return true
    }

    fun setAuthoritativeHealth(entity: Entity, targetHealth: Float) {
        val health = healthCmps.getOrNull(entity) ?: return
        val clamped = targetHealth.coerceIn(0f, health.maxHealth)
        if (clamped <= 0f && !health.isDead) {
            health.currentHealth = 0f
            health.isDead = true
            kill(entity)
        } else if (clamped > 0f) {
            health.currentHealth = clamped
            if (health.isDead && entity !in deadCmps) {
                health.isDead = false
            }
        }
    }

    fun forceKill(entity: Entity) {
        val health = healthCmps.getOrNull(entity) ?: return
        if (!health.isDead) {
            health.currentHealth = 0f
            health.isDead = true
            kill(entity)
        }
    }

    private fun kill(entity: Entity) {
        val enemy = enemyCmps.getOrNull(entity)
        if (enemy != null) {
            world.family(allOf = arrayOf(PlayerComponent::class)).forEach { player ->
                if (player !in remotePlayerCmps) {
                    playerCmps[player].score += enemy.scoreValue
                    playerCmps[player].kills += 1
                }
            }
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
        } else {
            // Player or RemotePlayer: keep MoveComponent & PhysicComponent intact so they can respawn!
            val physic = physicCmps.getOrNull(entity)
            physic?.body?.setLinearVelocity(0f, 0f)
            val move = moveCmps.getOrNull(entity)
            if (move != null) {
                move.cos = 0f
                move.sin = 0f
            }
            if (entity !in deadCmps) {
                world.configureEntity(entity) {
                    deadCmps.add(entity) {
                        time = 0f
                    }
                }
            }
        }

        val animation = animationCmps.getOrNull(entity) ?: return
        animation.nextAnimation(AnimationModel.PLAYER, AnimationType.DEAD)
        animation.playMode = Animation.PlayMode.NORMAL
    }

    companion object {
        const val INVULNERABILITY_DURATION = 0.05f
    }
}
