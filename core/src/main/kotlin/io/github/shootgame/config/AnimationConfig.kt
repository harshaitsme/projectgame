package io.github.shootgame.config

/**
 * Animation-specific configuration constants.
 * Centralized frame timing and animation parameters.
 */
object AnimationConfig {
    // Frame timing
    const val FRAME_DURATION = 1 / 8f
    const val FRAME_RATE_FPS = 8

    // Player animations
    object Player {
        const val IDLE = "Idle"
        const val WALK = "Walk"
        const val RUN = "Run"
        const val SHOT1 = "Shot1"
        const val SHOT2 = "Shot2"
        const val ATTACK = "Attack"
        const val RECHARGE = "Recharge"
        const val GRENADE = "Grenade"
        const val HURT = "Hurt"
        const val DEAD = "Dead"
        const val EXPLOSION = "Explosion"
    }

    /**
     * Get animation key in format "Model/Type"
     */
    fun getAnimationKey(model: String, type: String): String = "$model/$type"
}
