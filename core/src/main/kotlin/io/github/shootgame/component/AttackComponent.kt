package io.github.shootgame.component

class AttackComponent {
    var isAttacking: Boolean = false
    var isReloading: Boolean = false
    var ammo: Int = 10
    var maxAmmo: Int = 10
    var fireRate: Float = 0.25f
    var reloadTime: Float = 1.5f
    var stateTime: Float = 0f
}
