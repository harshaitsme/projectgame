package io.github.shootgame.component

class MoveComponent {
    var cos: Float = 0f
    var sin: Float = 0f
    var speed: Float = 5f
    var lastCos: Float = 1f
    var lastSin: Float = 0f
    var doJump: Boolean = false
    var jumpImpulse: Float = 10f
}
