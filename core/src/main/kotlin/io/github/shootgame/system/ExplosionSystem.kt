package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.ExplosionComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PlayerComponent
import kotlin.math.sqrt

@AllOf([ExplosionComponent::class])
class ExplosionSystem(
    private val explosionCmps: ComponentMapper<ExplosionComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>
) : IteratingSystem() {

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
        val explosionImage = imageCmps.getOrNull(entity) ?: return
        val explosionCenterX = explosionImage.image.x + explosionImage.image.width * 0.5f
        val explosionCenterY = explosionImage.image.y + explosionImage.image.height * 0.5f
        val range = explosion.range.coerceAtLeast(0f)
        if (range <= 0f) return

        val healthSystem = world.system<HealthSystem>()
        var playerInRange = false
        world.forEach { player ->
            if (player in playerCmps && player in healthCmps) {
                val playerImage = imageCmps.getOrNull(player)
                if (playerImage != null) {
                    val playerCenterX = playerImage.image.x + playerImage.image.width * 0.5f
                    val playerCenterY = playerImage.image.y + playerImage.image.height * 0.5f
                    val distance = distance(explosionCenterX, explosionCenterY, playerCenterX, playerCenterY)
                    if (distance <= range) {
                        playerInRange = true
                        val damageFraction = if (distance <= range * 0.5f) INNER_DAMAGE_FRACTION else OUTER_DAMAGE_FRACTION
                        healthSystem.damagePercent(player, damageFraction)
                    }
                }
            }
        }

        if (playerInRange) {
            world.system<CameraShakeSystem>().trigger()
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val deltaX = x2 - x1
        val deltaY = y2 - y1
        return sqrt(deltaX * deltaX + deltaY * deltaY)
    }

    companion object {
        private const val INNER_DAMAGE_FRACTION = 0.10f
        private const val OUTER_DAMAGE_FRACTION = 0.5f
    }
}
