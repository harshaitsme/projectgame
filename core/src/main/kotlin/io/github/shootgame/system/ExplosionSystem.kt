package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.EnemyComponent
import io.github.shootgame.component.ExplosionComponent
import io.github.shootgame.component.HazardComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.OwnerComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent
import kotlin.math.sqrt

@AllOf([ExplosionComponent::class])
class ExplosionSystem(
    private val explosionCmps: ComponentMapper<ExplosionComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val ownerCmps: ComponentMapper<OwnerComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val enemyCmps: ComponentMapper<EnemyComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val hazardCmps: ComponentMapper<HazardComponent>
) : IteratingSystem() {

    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }

    override fun onTickEntity(entity: Entity) {
        val explosion = explosionCmps[entity]
        if (!explosion.damageApplied) {
            applyExplosion(entity, explosion)
            explosion.damageApplied = true
        }

        explosion.duration -= deltaTime
        if (explosion.duration <= 0f) {
            world.remove(entity)
        }
    }

    private fun applyExplosion(entity: Entity, explosion: ExplosionComponent) {
        val explosionImage = imageCmps.getOrNull(entity)
        val explosionCenterX = if (explosion.centerX != 0f) {
            explosion.centerX
        } else {
            (explosionImage?.image?.x ?: 0f) + (explosionImage?.image?.width ?: 0f) * 0.5f
        }
        val explosionCenterY = if (explosion.centerY != 0f) {
            explosion.centerY
        } else {
            (explosionImage?.image?.y ?: 0f) + (explosionImage?.image?.height ?: 0f) * 0.5f
        }

        val range = explosion.range.coerceAtLeast(0f)
        if (range <= 0f) return

        val ownerEntity = ownerCmps.getOrNull(entity)?.owner
        val healthSystem = world.system<HealthSystem>()
        var playerInRange = false
        var minLocalPlayerDist = Float.MAX_VALUE

        world.forEach { target ->
            if ((target in playerCmps || target in enemyCmps || target in hazardCmps) && target in healthCmps) {
                val targetPos = physicCmps.getOrNull(target)?.body?.position
                val targetImage = imageCmps.getOrNull(target)?.image

                val targetCenterX = targetPos?.x ?: ((targetImage?.x ?: 0f) + (targetImage?.width ?: 0f) * 0.5f)
                val targetCenterY = targetPos?.y ?: ((targetImage?.y ?: 0f) + (targetImage?.height ?: 0f) * 0.5f)

                val dist = distance(explosionCenterX, explosionCenterY, targetCenterX, targetCenterY)
                if (target in playerCmps && target !in remotePlayerCmps && dist < minLocalPlayerDist) {
                    minLocalPlayerDist = dist
                }

                if (dist <= range) {
                    val damageFraction = if (dist <= range * 0.5f) INNER_DAMAGE_FRACTION else OUTER_DAMAGE_FRACTION
                    when {
                        target in remotePlayerCmps -> {
                            // If our local bomb hit a remote player, mark them as hit by us so if their
                            // authoritative health drops to 0 on the next state sync, we get the kill credit!
                            if (ownerEntity != null && ownerEntity in playerCmps && ownerEntity !in remotePlayerCmps) {
                                networkSystem.onLocalBulletHitRemote(target)
                            }
                        }

                        target in playerCmps -> {
                            // Local player is inside the blast radius!
                            playerInRange = true
                            val health = healthCmps[target]
                            health.invulnerableTime = 0f // Ensure bomb blast is never skipped by brief bullet invulnerability
                            val damaged = healthSystem.damagePercent(target, damageFraction)
                            if (damaged && networkSystem.isConnected) {
                                val died = health.currentHealth <= 0f || health.isDead
                                if (ownerEntity != null && ownerEntity in remotePlayerCmps) {
                                    networkSystem.onLocalPlayerHitByRemote(ownerEntity, died)
                                } else {
                                    // Self-damage or environment bomb: force immediate health sync
                                    networkSystem.forceImmediateSync()
                                }
                            }
                        }

                        target in enemyCmps || target in hazardCmps -> {
                            healthSystem.damagePercent(target, damageFraction)
                        }
                    }
                }
            }
        }

        // Trigger camera shake & haptic boom vibration for every bomb explosion based on proximity
        if (playerInRange) {
            world.system<CameraShakeSystem>().triggerExplosionBoom(1.15f)
        } else if (minLocalPlayerDist <= 22f) {
            val proximityFactor = (1f - (minLocalPlayerDist / 22f)).coerceIn(0.35f, 0.90f)
            world.system<CameraShakeSystem>().triggerExplosionBoom(proximityFactor)
        } else {
            world.system<CameraShakeSystem>().triggerExplosionBoom(0.45f)
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x2 - x1
        val deltaY = y2 - y1
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    companion object {
        private const val INNER_DAMAGE_FRACTION = 0.65f
        private const val OUTER_DAMAGE_FRACTION = 0.35f
    }
}
