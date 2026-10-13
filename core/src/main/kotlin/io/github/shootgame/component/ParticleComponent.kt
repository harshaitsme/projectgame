package io.github.shootgame.component

import com.badlogic.gdx.graphics.Color

enum class ParticleType {
    BULLET_TRAIL,
    SPARK,
    DUST,
    EXPLOSION_EMBER
}

class ParticleComponent {
    var type: ParticleType = ParticleType.BULLET_TRAIL
    var lifeTime: Float = 0.3f
    var maxLifeTime: Float = 0.3f
    var velX: Float = 0f
    var velY: Float = 0f
    var gravity: Float = 0f
    var scaleStart: Float = 1f
    var scaleEnd: Float = 0.1f
    val colorStart: Color = Color(1f, 1f, 1f, 1f)
    val colorEnd: Color = Color(1f, 1f, 1f, 0f)
}
