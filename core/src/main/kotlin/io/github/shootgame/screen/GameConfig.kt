package io.github.shootgame.screen

import com.badlogic.gdx.Gdx
import io.github.shootgame.network.GameClient

enum class GameMode(val displayName: String, val maxPlayers: Int, val description: String) {
    OFFLINE("Solo Practice", 1, "Play solo against AI bots on the arena map."),
    DUO("2P Duo Arena", 2, "2-Player live multiplayer PvP arena match."),
    SQUAD("4P Squad Arena", 4, "4-Player live multiplayer Free-For-All / Squad arena.")
}

object GameSettings {
    private const val PREFS_NAME = "shootgame_settings"

    var sfxEnabled: Boolean = true
        private set
    var sfxVolume: Float = 0.8f
        private set
    var bgmEnabled: Boolean = true
        private set
    var bgmVolume: Float = 0.5f
        private set
    var vibrationEnabled: Boolean = true
        private set
    var vibrationIntensity: Float = 1.0f // 0.0f .. 2.0f (0% .. 200%)
        private set
    var gamma: Float = 1.0f // 0.5f .. 1.5f (50% .. 150%)
        private set

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val prefs = Gdx.app?.getPreferences(PREFS_NAME) ?: return
            sfxEnabled = prefs.getBoolean("sfxEnabled", true)
            sfxVolume = prefs.getFloat("sfxVolume", 0.8f).coerceIn(0f, 1f)
            bgmEnabled = prefs.getBoolean("bgmEnabled", true)
            bgmVolume = prefs.getFloat("bgmVolume", 0.5f).coerceIn(0f, 1f)
            vibrationEnabled = prefs.getBoolean("vibrationEnabled", true)
            vibrationIntensity = prefs.getFloat("vibrationIntensity", 1.0f).coerceIn(0f, 2f)
            gamma = prefs.getFloat("gamma", 1.0f).coerceIn(0.5f, 1.5f)
        } catch (_: Exception) {
        }
    }

    fun updateSfx(enabled: Boolean = sfxEnabled, volume: Float = sfxVolume) {
        ensureLoaded()
        sfxEnabled = enabled
        sfxVolume = volume.coerceIn(0f, 1f)
        save()
    }

    fun updateBgm(enabled: Boolean = bgmEnabled, volume: Float = bgmVolume) {
        ensureLoaded()
        bgmEnabled = enabled
        bgmVolume = volume.coerceIn(0f, 1f)
        save()
    }

    fun updateVibration(enabled: Boolean = vibrationEnabled, intensity: Float = vibrationIntensity) {
        ensureLoaded()
        vibrationEnabled = enabled
        vibrationIntensity = intensity.coerceIn(0f, 2f)
        save()
    }

    fun updateGamma(value: Float) {
        ensureLoaded()
        gamma = value.coerceIn(0.5f, 1.5f)
        save()
    }

    fun resetDefaults() {
        sfxEnabled = true
        sfxVolume = 0.8f
        bgmEnabled = true
        bgmVolume = 0.5f
        vibrationEnabled = true
        vibrationIntensity = 1.0f
        gamma = 1.0f
        save()
    }

    private fun save() {
        try {
            val prefs = Gdx.app?.getPreferences(PREFS_NAME) ?: return
            prefs.putBoolean("sfxEnabled", sfxEnabled)
            prefs.putFloat("sfxVolume", sfxVolume)
            prefs.putBoolean("bgmEnabled", bgmEnabled)
            prefs.putFloat("bgmVolume", bgmVolume)
            prefs.putBoolean("vibrationEnabled", vibrationEnabled)
            prefs.putFloat("vibrationIntensity", vibrationIntensity)
            prefs.putFloat("gamma", gamma)
            prefs.flush()
        } catch (_: Exception) {
        }
    }
}

data class GameConfig(
    var mode: GameMode = GameMode.DUO,
    var playerName: String = "Player",
    var serverHost: String = "161.118.255.172",
    var roomCode: String = "",
    var createRoom: Boolean = false,
    var jwtToken: String = "",
    var sessionToken: String = "",
    var onExitToMenu: () -> Unit = {}
) {
    companion object {
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateRoomCode(): String {
            return (1..6)
                .map { CODE_CHARS.random() }
                .joinToString("")
        }
    }
}

