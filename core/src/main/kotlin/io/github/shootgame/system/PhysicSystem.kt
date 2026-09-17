package io.github.shootgame.system

import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Fixed
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent

@AllOf([PhysicComponent::class, ImageComponent::class])
class PhysicSystem(
    private val phWorld: PhWorld,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>
) : IteratingSystem(interval = Fixed(1 / 60f)) {

    override fun onTick() {
        phWorld.step(deltaTime, 6, 2)
        phWorld.clearForces()
        super.onTick()
    }

    override fun onTickEntity(entity: Entity) {
        val physicCmp = physicCmps[entity]
        val imageCmp = imageCmps[entity]

        val body = physicCmp.body
        imageCmp.image.run {
            setPosition(
                body.position.x - width * 0.5f,
                body.position.y - height * 0.5f
            )
        }
    }
}
