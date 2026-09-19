package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.ExplosionComponent

@AllOf([ExplosionComponent::class])
class ExplosionSystem(
    private val explosionCmps: ComponentMapper<ExplosionComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val explosionCmp = explosionCmps[entity]
        explosionCmp.duration -= deltaTime
        if (explosionCmp.duration <= 0) {
            world.remove(entity)
        }
    }
}
