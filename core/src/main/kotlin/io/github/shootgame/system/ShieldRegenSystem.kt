package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.ShieldComponent

@AllOf([ShieldComponent::class])
class ShieldRegenSystem(
    private val shieldCmps: ComponentMapper<ShieldComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        if (entity in deadCmps) return

        val shield = shieldCmps[entity]
        shield.timeSinceDamage += deltaTime

        if (shield.timeSinceDamage >= shield.regenDelay && shield.currentShield < shield.maxShield) {
            shield.currentShield = (shield.currentShield + shield.regenRate * deltaTime).coerceAtMost(shield.maxShield)
        }
    }
}
