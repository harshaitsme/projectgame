package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.audio.AudioService
import io.github.shootgame.component.AudioEmitterComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PlayerComponent

@AllOf([AudioEmitterComponent::class])
class AudioSystem(
    private val audioEmitterCmps: ComponentMapper<AudioEmitterComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val emitter = audioEmitterCmps[entity]
        if (!emitter.trigger) return

        var listenerX = 0f
        var listenerY = 0f
        world.family(allOf = arrayOf(PlayerComponent::class, ImageComponent::class)).forEach { player ->
            val img = imageCmps[player].image
            listenerX = img.x + img.width * 0.5f
            listenerY = img.y + img.height * 0.5f
        }

        val emitterImg = imageCmps.getOrNull(entity)
        val sourceX = emitterImg?.image?.let { it.x + it.width * 0.5f } ?: listenerX
        val sourceY = emitterImg?.image?.let { it.y + it.height * 0.5f } ?: listenerY

        audioService.playSpatial(
            type = emitter.sound,
            sourceX = sourceX,
            sourceY = sourceY,
            listenerX = listenerX,
            listenerY = listenerY,
            maxDistance = emitter.maxDistance,
            volumeModifier = emitter.volume,
            pitchVariation = emitter.pitchVariation
        )

        emitter.trigger = false
        world.remove(entity)
    }
}
