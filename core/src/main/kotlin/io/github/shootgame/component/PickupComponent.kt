package io.github.shootgame.component

enum class PickupType(
    val displayName: String,
    val description: String,
    val defaultAmount: Float
) {
    HEALTH("Health Pack", "Restores 35 HP", 35f),
    AMMO("Ammo Crate", "Restores weapon ammo", 30f),
    GRENADE("Frag Grenade", "+2 Grenades", 2f),
    COIN("Gold Coin", "+50 Points", 50f)
}

class PickupComponent {
    var type: PickupType = PickupType.HEALTH
    var amount: Float = 35f
    var magnetRadius: Float = 2.5f    // Radius in world units where item starts flying to player
    var collectRadius: Float = 0.6f   // Radius where item is collected
    var bobTimer: Float = 0f          // Timer for gentle floating bob
    var lifeTime: Float = -1f         // -1 = infinite, or duration in seconds until despawn
}
