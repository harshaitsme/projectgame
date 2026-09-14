package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Fixed
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent

@AllOf([PhysicComponent::class , ImageComponent::class])
class PhysicSystem(
    private val phWorld: World,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>
) : IteratingSystem(interval = Fixed(1/60f)) {


    override fun onTickEntity(entity: Entity) {
        TODO("Not yet implemented")
    }


}
