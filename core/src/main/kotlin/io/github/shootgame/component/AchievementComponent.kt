package io.github.shootgame.component

enum class AchievementType(
    val title: String,
    val description: String,
    val scoreReward: Int
) {
    FIRST_BLOOD("First Blood", "Eliminate your first enemy", 200),
    SHARPSHOOTER("Sharpshooter", "Reach 10 kills", 500),
    RAMPAGE("Rampage", "Reach 25 kills", 1500),
    WAVE_SURVIVOR("Wave Survivor", "Survive wave 5", 1000),
    BOSS_SLAYER("Boss Slayer", "Defeat a boss enemy", 2000),
    ARSENAL_MASTER("Arsenal Master", "Equip 3 different weapons", 750)
}

class AchievementComponent {
    val unlockedAchievements = mutableSetOf<AchievementType>()
    val pendingNotifications = mutableListOf<AchievementType>()
    val equippedWeapons = mutableSetOf<WeaponType>()

    fun unlock(type: AchievementType): Boolean {
        return if (unlockedAchievements.add(type)) {
            pendingNotifications.add(type)
            true
        } else {
            false
        }
    }

    fun hasUnlocked(type: AchievementType): Boolean = type in unlockedAchievements
}
