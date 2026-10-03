package io.github.shootgame.config

/**
 * Animation-specific configuration constants.
 * Centralized frame timing and animation parameters.
 */
object AnimationConfig {
    // Frame timing
    const val FRAME_RATE_FPS = 8
    const val FRAME_DURATION = 1f / FRAME_RATE_FPS

    // Player animations
    enum class PlayerAnimation {
        IDLE, WALK, RUN, SHOT1, SHOT2, ATTACK, RECHARGE, GRENADE, HURT, DEAD, EXPLOSION;

        val key: String get() = name.capitalize() // Or just name if you prefer uppercase
    }

    /**
     * Get animation key in format "Model/Type"
     */
    fun getAnimationKey(model: String, type: PlayerAnimation): String = "$model/${type.key}"
}
