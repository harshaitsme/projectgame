package io.github.shootgame.component

enum class AiState {
    IDLE,
    PATROL,
    CHASE,
    ATTACK
}

class AiComponent(
    var state: AiState = AiState.PATROL,
    var detectionRadius: Float = 10f,
    var attackRange: Float = 6.5f,
    var patrolDirection: Float = 1f,
    var patrolTimer: Float = 0f,
    var patrolInterval: Float = 2.5f,
    var shootCooldown: Float = 1.0f,
    var shootTimer: Float = 0f,
    var jumpCooldown: Float = 1.5f,
    var jumpTimer: Float = 0f
)
