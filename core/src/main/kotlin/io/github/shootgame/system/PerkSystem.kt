package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PerkComponent
import io.github.shootgame.component.PerkType

@AllOf([PerkComponent::class])
class PerkSystem(
    private val perkCmps: ComponentMapper<PerkComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        if (entity in deadCmps) return

        val perks = perkCmps[entity]

        // Apply movement speed modifier if SWIFT_BOOTS is active
        val moveCmp = moveCmps.getOrNull(entity)
        if (moveCmp != null) {
            val speedMultiplier = if (perks.hasPerk(PerkType.SWIFT_BOOTS)) 1.25f else 1.0f
            moveCmp.speed = moveCmp.baseSpeed * speedMultiplier
        }

        // Apply fire rate modifier if RAPID_FIRE is active
        val attackCmp = attackCmps.getOrNull(entity)
        if (attackCmp != null) {
            val rofMultiplier = if (perks.hasPerk(PerkType.RAPID_FIRE)) 0.70f else 1.0f
            // Lower attack delay = faster rate of fire
            attackCmp.fireRate = 0.25f * rofMultiplier
        }
    }
}
