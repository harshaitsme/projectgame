package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.*

@AllOf([FragComponent::class, ImageComponent::class])
class FragSystem(
    private val fragCmps: ComponentMapper<FragComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val fragCmp = fragCmps[entity]
        val imageCmp = imageCmps[entity]

        fragCmp.fuseTime -= deltaTime
        if (fragCmp.fuseTime <= 0) {
            val x = imageCmp.image.x + imageCmp.image.width * 0.5f
            val y = imageCmp.image.y + imageCmp.image.height * 0.5f

            world.entity {
                add<SpawnComponent> {
                    type = "Explosion"
                    location.set(x, y)
                }
            }
            world.remove(entity)
        }
    }
}
