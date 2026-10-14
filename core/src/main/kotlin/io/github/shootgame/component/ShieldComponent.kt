package io.github.shootgame.component

class ShieldComponent(
    var maxShield: Float = 50f,
    var currentShield: Float = 50f,
    var regenDelay: Float = 3.5f,
    var regenRate: Float = 15f,
    var timeSinceDamage: Float = 3.5f
)
