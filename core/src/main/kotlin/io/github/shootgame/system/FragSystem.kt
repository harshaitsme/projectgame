package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*

@AllOf([FragComponent::class, PhysicComponent::class])
class FragSystem(
    private val fragCmps: ComponentMapper<FragComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val fragCmp = fragCmps[entity]
        val physicCmp = physicCmps[entity]

        fragCmp.fuseTime -= deltaTime
        if (fragCmp.fuseTime <= 0) {
            val body = physicCmp.body
            // Capture the exact center of the grenade
            val posX = body.position.x
            val posY = body.position.y

            world.entity {
                add<SpawnComponent> {
                    type = "Explosion"
                    location.set(posX, posY)
                }
            }
            audioService.play(SoundType.EXPLOSION, pitchVariation = 0.05f)
            world.remove(entity)
        }
    }
}
