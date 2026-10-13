package io.github.shootgame.component

enum class StatusEffectType {
    SPEED_BOOST,
    SHIELD,
    POISON,
    REGENERATION
}

data class ActiveStatusEffect(
    val type: StatusEffectType,
    var duration: Float,
    var magnitude: Float = 1f,
    var tickInterval: Float = 1f,
    var tickTimer: Float = 0f
)

class StatusEffectComponent {
    val activeEffects = mutableListOf<ActiveStatusEffect>()

    fun addEffect(
        type: StatusEffectType,
        duration: Float,
        magnitude: Float = 1f,
        tickInterval: Float = 1f
    ) {
        val existing = activeEffects.find { it.type == type }
        if (existing != null) {
            existing.duration = maxOf(existing.duration, duration)
            existing.magnitude = maxOf(existing.magnitude, magnitude)
        } else {
            activeEffects.add(ActiveStatusEffect(type, duration, magnitude, tickInterval))
        }
    }

    fun hasEffect(type: StatusEffectType): Boolean = activeEffects.any { it.type == type }

    fun getEffect(type: StatusEffectType): ActiveStatusEffect? = activeEffects.find { it.type == type }

    fun removeEffect(type: StatusEffectType) {
        activeEffects.removeAll { it.type == type }
    }
}
