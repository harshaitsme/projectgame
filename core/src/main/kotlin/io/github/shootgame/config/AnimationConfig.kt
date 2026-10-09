package io.github.shootgame.config

/**
 * Animation-specific configuration constants.
 * Centralized frame timing and animation parameters.
 */
object AnimationConfig {
    // Frame timing - synchronized with GameConfig
    const val FRAME_RATE_FPS = GameConfig.ANIMATION_FRAME_RATE_FPS
    const val FRAME_DURATION = GameConfig.ANIMATION_FRAME_DURATION

    // Player animations
    enum class PlayerAnimation {
        IDLE, WALK, RUN, SHOT1, SHOT2, ATTACK, RECHARGE, GRENADE, HURT, DEAD, EXPLOSION;

        val key: String get() = name.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Get animation key in format "Model/Type"
     * @param model the model name (e.g., "Player")
     * @param type the animation type
     * @return formatted animation key string
     */
    fun getAnimationKey(model: String, type: PlayerAnimation): String = "$model/${type.key}"
}
