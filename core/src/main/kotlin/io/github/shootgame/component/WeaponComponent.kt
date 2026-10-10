package io.github.shootgame.component

enum class WeaponType(
    val displayName: String,
    val damage: Float,
    val fireRate: Float,
    val reloadTime: Float,
    val maxAmmo: Int,
    val maxReserveAmmo: Int,
    val bulletSpeed: Float,
    val bulletCount: Int,
    val spreadAngle: Float,
    val bulletLifeTime: Float,
    val cameraShake: Float
) {
    PISTOL(
        displayName = "Pistol",
        damage = 35f,
        fireRate = 0.25f,
        reloadTime = 1.2f,
        maxAmmo = 12,
        maxReserveAmmo = 60,
        bulletSpeed = 16f,
        bulletCount = 1,
        spreadAngle = 0f,
        bulletLifeTime = 2.0f,
        cameraShake = 0.05f
    ),
    SHOTGUN(
        displayName = "Shotgun",
        damage = 22f,
        fireRate = 0.65f,
        reloadTime = 2.0f,
        maxAmmo = 6,
        maxReserveAmmo = 30,
        bulletSpeed = 14f,
        bulletCount = 5,
        spreadAngle = 20f,
        bulletLifeTime = 0.55f,
        cameraShake = 0.20f
    ),
    MACHINE_GUN(
        displayName = "Machine Gun",
        damage = 20f,
        fireRate = 0.10f,
        reloadTime = 1.8f,
        maxAmmo = 30,
        maxReserveAmmo = 120,
        bulletSpeed = 18f,
        bulletCount = 1,
        spreadAngle = 5f,
        bulletLifeTime = 2.0f,
        cameraShake = 0.08f
    );
}

class WeaponComponent {
    val weapons: MutableList<WeaponType> = mutableListOf(
        WeaponType.PISTOL,
        WeaponType.SHOTGUN,
        WeaponType.MACHINE_GUN
    )
    var currentIndex: Int = 0
    val ammoMap: MutableMap<WeaponType, Int> = mutableMapOf()
    val reserveAmmoMap: MutableMap<WeaponType, Int> = mutableMapOf()

    val currentWeapon: WeaponType
        get() = weapons[currentIndex.coerceIn(0, weapons.lastIndex)]

    init {
        refillAllAmmo()
    }

    fun refillAllAmmo() {
        WeaponType.values().forEach { weapon ->
            ammoMap[weapon] = weapon.maxAmmo
            reserveAmmoMap[weapon] = weapon.maxReserveAmmo
        }
    }

    fun switchNext(): WeaponType {
        currentIndex = (currentIndex + 1) % weapons.size
        return currentWeapon
    }

    fun switchPrevious(): WeaponType {
        currentIndex = if (currentIndex - 1 < 0) weapons.size - 1 else currentIndex - 1
        return currentWeapon
    }

    fun selectWeapon(type: WeaponType): Boolean {
        val idx = weapons.indexOf(type)
        if (idx != -1) {
            currentIndex = idx
            return true
        }
        return false
    }
}
