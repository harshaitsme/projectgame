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
    private val ownerCmps: ComponentMapper<OwnerComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }

    override fun onTickEntity(entity: Entity) {
        val fragCmp = fragCmps[entity]
        val physicCmp = physicCmps[entity]

        fragCmp.fuseTime -= deltaTime
        if (fragCmp.fuseTime <= 0f) {
            val body = physicCmp.body
            // Capture the exact center of the grenade
            val posX = body.position.x
            val posY = body.position.y
            val fragOwner = ownerCmps.getOrNull(entity)?.owner

            // If this is the local player's bomb, broadcast authoritative explosion position to all room clients
            if (!fragCmp.isRemote && networkSystem.isConnected) {
                networkSystem.sendBombExplosion(posX, posY)
            } else if (fragCmp.isRemote) {
                networkSystem.markRemoteBombDetonated(fragCmp.remoteOwnerNetId)
            }

            world.entity {
                add<SpawnComponent> {
                    type = "Explosion"
                    location.set(posX, posY)
                }
                if (fragOwner != null) {
                    add<OwnerComponent> {
                        owner = fragOwner
                    }
                }
            }
            world.entity {
                add<AudioEmitterComponent> {
                    sound = SoundType.EXPLOSION
                    pitchVariation = 0.08f
                    volume = 1.0f
                }
                add<ImageComponent> {
                    image = com.badlogic.gdx.scenes.scene2d.ui.Image()
                    image.setPosition(posX, posY)
                }
            }
            world.remove(entity)
        }
    }
}
