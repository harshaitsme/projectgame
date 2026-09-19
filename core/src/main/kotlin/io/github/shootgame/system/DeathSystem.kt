package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.HealthComponent

@AllOf([DeadComponent::class])
class DeathSystem(
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val dead = deadCmps[entity]
        dead.time += deltaTime

        if (dead.time >= DEATH_DURATION) {
            healthCmps.getOrNull(entity)?.isDead = true
            world.remove(entity)
        }
    }

    companion object {
        const val DEATH_DURATION = 3f
    }
}
