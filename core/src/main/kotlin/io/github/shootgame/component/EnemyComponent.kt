package io.github.shootgame.component

enum class EnemyType {
    WALKER,
    CHASER,
    SNIPER
}

class EnemyComponent(
    var type: EnemyType = EnemyType.WALKER,
    var scoreValue: Int = 100
)
