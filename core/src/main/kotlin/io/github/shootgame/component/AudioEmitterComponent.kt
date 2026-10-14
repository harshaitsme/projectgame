package io.github.shootgame.component

import io.github.shootgame.audio.SoundType

class AudioEmitterComponent(
    var sound: SoundType = SoundType.SHOT,
    var volume: Float = 1f,
    var pitchVariation: Float = 0.05f,
    var maxDistance: Float = 25f,
    var trigger: Boolean = true
)
