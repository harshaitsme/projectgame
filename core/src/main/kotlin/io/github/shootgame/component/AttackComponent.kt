package io.github.shootgame.component

class AttackComponent {
    var isAttacking: Boolean = false
    var isReloading: Boolean = false
    var isThrowing: Boolean = false
    var ammo: Int = 10
    var maxAmmo: Int = 10
    var fragAmmo: Int = 3
    var fireRate: Float = 0.25f
    var reloadTime: Float = 1.5f
    var throwRate: Float = 1.0f
    var stateTime: Float = 0f
}
