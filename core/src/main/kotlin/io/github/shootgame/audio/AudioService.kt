package io.github.shootgame.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.math.MathUtils
import ktx.assets.disposeSafely
import ktx.log.logger

enum class SoundType(val fileName: String) {
    SHOT("sounds/shot.wav"),
    RELOAD("sounds/reload.wav"),
    THROW("sounds/throw.wav"),
    EXPLOSION("sounds/explosion.wav"),
    JUMP("sounds/jump.wav"),
    CLICK("sounds/click.wav")
}

class AudioService {
    private val soundCache = mutableMapOf<SoundType, Sound>()
    private var music: Music? = null

    var isSoundEnabled: Boolean = true
    var isMusicEnabled: Boolean = true

    var soundVolume: Float = 0.8f
    var musicVolume: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            music?.volume = if (isMusicEnabled) field else 0f
        }

    init {
        loadSounds()
        loadMusic()
    }

    private fun loadSounds() {
        SoundType.values().forEach { type ->
            try {
                val file = Gdx.files.internal(type.fileName)
                if (file.exists()) {
                    soundCache[type] = Gdx.audio.newSound(file)
                    log.info { "Loaded sound: ${type.fileName}" }
                } else {
                    log.error { "Sound file not found: ${type.fileName}" }
                }
            } catch (e: Exception) {
                log.error(e) { "Failed to load sound: ${type.fileName}" }
            }
        }
    }

    private fun loadMusic() {
        try {
            val musicFile = Gdx.files.internal("sounds/bgm.wav")
            if (musicFile.exists()) {
                music = Gdx.audio.newMusic(musicFile).apply {
                    isLooping = true
                    volume = if (isMusicEnabled) musicVolume else 0f
                }
                log.info { "Loaded background music: sounds/bgm.wav" }
            } else {
                log.error { "Background music file not found: sounds/bgm.wav" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load background music: sounds/bgm.wav" }
        }
    }

    fun play(type: SoundType, volumeModifier: Float = 1f, pitchVariation: Float = 0f) {
        if (!isSoundEnabled) return
        val sound = soundCache[type] ?: return

        val finalVolume = (soundVolume * volumeModifier).coerceIn(0f, 1f)
        val finalPitch = if (pitchVariation > 0f) {
            (1f + MathUtils.random(-pitchVariation, pitchVariation)).coerceIn(0.5f, 2.0f)
        } else {
            1f
        }

        sound.play(finalVolume, finalPitch, 0f)
    }

    fun playMusic() {
        if (music != null) {
            music?.volume = if (isMusicEnabled) musicVolume else 0f
            if (!music!!.isPlaying) {
                music?.play()
            }
        }
    }

    fun pauseMusic() {
        music?.pause()
    }

    fun resumeMusic() {
        if (isMusicEnabled && music != null && !music!!.isPlaying) {
            music?.play()
        }
    }

    fun stopMusic() {
        music?.stop()
    }

    fun toggleSound(): Boolean {
        isSoundEnabled = !isSoundEnabled
        return isSoundEnabled
    }

    fun toggleMusic(): Boolean {
        isMusicEnabled = !isMusicEnabled
        if (music != null) {
            if (isMusicEnabled) {
                music?.volume = musicVolume
                if (!music!!.isPlaying) {
                    music?.play()
                }
            } else {
                music?.pause()
            }
        }
        return isMusicEnabled
    }

    fun dispose() {
        soundCache.values.forEach { it.disposeSafely() }
        soundCache.clear()
        music?.disposeSafely()
        music = null
    }

    companion object {
        private val log = logger<AudioService>()
    }
}
