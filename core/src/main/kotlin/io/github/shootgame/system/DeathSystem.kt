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
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent
import io.github.shootgame.component.WeaponComponent

@AllOf([DeadComponent::class])
class DeathSystem(
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>
) : IteratingSystem() {

    private var respawnIndex = 0
    private val spawnLocations = listOf(
        12.5f to 16.0f,
        18.0f to 16.0f,
        7.5f to 11.5f,
        22.5f to 10.5f
    )

    override fun onTickEntity(entity: Entity) {
        val dead = deadCmps[entity]
        dead.time += deltaTime

        if (entity in playerCmps) {
            // Players respawn after PLAYER_RESPAWN_DURATION
            if (dead.time >= PLAYER_RESPAWN_DURATION) {
                world.configureEntity(entity) {
                    deadCmps.remove(entity)
                }
                val health = healthCmps.getOrNull(entity)
                if (health != null) {
                    health.currentHealth = health.maxHealth
                    health.isDead = false
                    health.invulnerableTime = 1.0f // 1s spawn protection
                }
                weaponCmps.getOrNull(entity)?.refillAllAmmo()
                val anim = animationCmps.getOrNull(entity)
                if (anim != null) {
                    anim.playMode = Animation.PlayMode.LOOP
                    anim.nextAnimation(AnimationModel.PLAYER, AnimationType.IDLE)
                }
                if (entity !in remotePlayerCmps) {
                    val physic = physicCmps.getOrNull(entity)
                    if (physic != null) {
                        val (sx, sy) = spawnLocations[(respawnIndex++) % spawnLocations.size]
                        physic.body.setTransform(sx, sy, 0f)
                        physic.body.setLinearVelocity(0f, 0f)
                    }
                }
            }
        } else {
            // Non-player entities (AI Enemies) despawn after DEATH_DURATION
            if (dead.time >= DEATH_DURATION) {
                healthCmps.getOrNull(entity)?.isDead = true
                world.remove(entity)
            }
        }
    }

    companion object {
        const val DEATH_DURATION = 3.0f
        const val PLAYER_RESPAWN_DURATION = 2.0f
    }
}
