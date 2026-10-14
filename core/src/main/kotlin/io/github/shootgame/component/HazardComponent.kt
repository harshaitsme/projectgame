package io.github.shootgame.component

enum class HazardType {
    EXPLOSIVE_BARREL,
    ELECTRIC_GATE,
    TOXIC_SPIKES
}

class HazardComponent {
    var type: HazardType = HazardType.EXPLOSIVE_BARREL
    var blastRadius: Float = 4.2f
    var blastDamage: Float = 110f
    var cycleTimer: Float = 0f
    var cyclePeriod: Float = 3.0f
    var isActive: Boolean = true
    var tickDamage: Float = 25f
    var tickInterval: Float = 0.5f
    var timeSinceLastTick: Float = 0f
    var isTriggered: Boolean = false
}
