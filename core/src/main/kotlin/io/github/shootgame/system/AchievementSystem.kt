package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*

@AllOf([PlayerComponent::class, AchievementComponent::class])
@NoneOf([RemotePlayerComponent::class])
class AchievementSystem(
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val achievementCmps: ComponentMapper<AchievementComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val waveCmps: ComponentMapper<WaveDirectorComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val player = playerCmps[entity]
        val achievements = achievementCmps[entity]

        // 1. First Blood & Kill Milestones
        if (player.kills >= 1 && !achievements.hasUnlocked(AchievementType.FIRST_BLOOD)) {
            unlock(entity, player, achievements, AchievementType.FIRST_BLOOD)
        }
        if (player.kills >= 10 && !achievements.hasUnlocked(AchievementType.SHARPSHOOTER)) {
            unlock(entity, player, achievements, AchievementType.SHARPSHOOTER)
        }
        if (player.kills >= 25 && !achievements.hasUnlocked(AchievementType.RAMPAGE)) {
            unlock(entity, player, achievements, AchievementType.RAMPAGE)
        }

        // 2. Weapon Milestones
        val weaponCmp = weaponCmps.getOrNull(entity)
        if (weaponCmp != null) {
            achievements.equippedWeapons.add(weaponCmp.currentWeapon)
            if (achievements.equippedWeapons.size >= 3 && !achievements.hasUnlocked(AchievementType.ARSENAL_MASTER)) {
                unlock(entity, player, achievements, AchievementType.ARSENAL_MASTER)
            }
        }

        // 3. Wave Survival Milestones
        world.family(allOf = arrayOf(WaveDirectorComponent::class)).forEach { waveEntity ->
            val wave = waveCmps[waveEntity]
            if (wave.wave >= 5 && !achievements.hasUnlocked(AchievementType.WAVE_SURVIVOR)) {
                unlock(entity, player, achievements, AchievementType.WAVE_SURVIVOR)
            }
        }
    }

    private fun unlock(
        entity: Entity,
        player: PlayerComponent,
        achievements: AchievementComponent,
        type: AchievementType
    ) {
        if (achievements.unlock(type)) {
            player.score += type.scoreReward
            audioService.play(SoundType.CLICK)
        }
    }

    fun triggerBossKill(playerEntity: Entity) {
        val player = playerCmps.getOrNull(playerEntity) ?: return
        val achievements = achievementCmps.getOrNull(playerEntity) ?: return
        if (!achievements.hasUnlocked(AchievementType.BOSS_SLAYER)) {
            unlock(playerEntity, player, achievements, AchievementType.BOSS_SLAYER)
        }
    }
}
