package io.github.shootgame.component

enum class BossPhase {
    IDLE,
    SPIRAL_BARRAGE,
    GROUND_SLAM,
    ENRAGED
}

class BossComponent(
    var name: String = "CYBERNETIC BEHEMOTH",
    var phase: BossPhase = BossPhase.IDLE,
    var phaseTimer: Float = 0f,
    var attackCooldown: Float = 2.5f,
    var slamCooldown: Float = 5.0f,
    var slamTimer: Float = 0f,
    var spiralCooldown: Float = 4.0f,
    var spiralTimer: Float = 0f,
    var isEnraged: Boolean = false
)
