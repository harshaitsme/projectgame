package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.*

@AllOf([StatusEffectComponent::class])
class StatusEffectSystem(
    private val statusEffectCmps: ComponentMapper<StatusEffectComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val status = statusEffectCmps[entity]
        val health = healthCmps.getOrNull(entity)
        val move = moveCmps.getOrNull(entity)

        var speedMultiplier = 1f

        val iterator = status.activeEffects.iterator()
        while (iterator.hasNext()) {
            val effect = iterator.next()
            effect.duration -= deltaTime

            when (effect.type) {
                StatusEffectType.SPEED_BOOST -> {
                    speedMultiplier *= effect.magnitude
                }
                StatusEffectType.POISON -> {
                    effect.tickTimer += deltaTime
                    if (effect.tickTimer >= effect.tickInterval) {
                        effect.tickTimer = 0f
                        health?.let {
                            if (!it.isDead) {
                                it.currentHealth = (it.currentHealth - effect.magnitude).coerceAtLeast(1f)
                            }
                        }
                    }
                }
                StatusEffectType.REGENERATION -> {
                    effect.tickTimer += deltaTime
                    if (effect.tickTimer >= effect.tickInterval) {
                        effect.tickTimer = 0f
                        health?.let {
                            if (!it.isDead) {
                                it.currentHealth = (it.currentHealth + effect.magnitude).coerceAtMost(it.maxHealth)
                            }
                        }
                    }
                }
                StatusEffectType.SHIELD -> {
                    // Shield magnitude represents remaining barrier health
                }
            }

            if (effect.duration <= 0f) {
                iterator.remove()
            }
        }

        move?.let {
            it.speed = it.baseSpeed * speedMultiplier
        }
    }
}
