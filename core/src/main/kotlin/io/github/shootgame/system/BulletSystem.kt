package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.BulletComponent

@AllOf([BulletComponent::class])
class BulletSystem(
    private val bulletCmps: ComponentMapper<BulletComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val bulletCmp = bulletCmps[entity]
        bulletCmp.lifeTime -= deltaTime
        if (bulletCmp.lifeTime <= 0) {
            world.remove(entity)
        }
    }
}
