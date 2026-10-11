package io.github.shootgame.audio

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.Music
import com.badlogic.gdx.audio.Sound
import com.badlogic.gdx.math.MathUtils
import ktx.assets.disposeSafely
import ktx.log.logger

enum class SoundType(val fileName: String) {
    SHOT("sounds/shot.mp3"),
    SHOTGUN("sounds/shotgun.mp3"),
    RELOAD("sounds/reload.mp3"),
    THROW("sounds/throw.mp3"),
    EXPLOSION("sounds/explosion.wav"),
    JUMP("sounds/jump.mp3"),
    CLICK("sounds/click.mp3")
}

class AudioService {
    private val soundCache = mutableMapOf<SoundType, Sound>()
    private var music: Music? = null
    private var isDisposed = false

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
            val musicFile = Gdx.files.internal("sounds/bgm.mp3")
            if (musicFile.exists()) {
                music = Gdx.audio.newMusic(musicFile).apply {
                    isLooping = true
                    volume = if (isMusicEnabled) musicVolume else 0f
                }
                log.info { "Loaded background music: sounds/bgm.mp3" }
            } else {
                log.error { "Background music file not found: sounds/bgm.mp3" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load background music: sounds/bgm.wav" }
        }
    }

    fun play(
        type: SoundType,
        volumeModifier: Float = 1f,
        pitchVariation: Float = 0f,
        basePitch: Float = 1f
    ) {
        if (isDisposed) {
            log.warn { "Attempted to play sound after AudioService was disposed" }
            return
        }
        if (!isSoundEnabled) return
        val sound = soundCache[type] ?: return

        val finalVolume = (soundVolume * volumeModifier).coerceIn(0f, 1f)
        val finalPitch = if (pitchVariation > 0f) {
            (basePitch + MathUtils.random(-pitchVariation, pitchVariation)).coerceIn(0.5f, 2.0f)
        } else {
            basePitch.coerceIn(0.5f, 2.0f)
        }

        sound.play(finalVolume, finalPitch, 0f)
    }

    fun playMusic() {
        if (isDisposed) {
            log.warn { "Attempted to play music after AudioService was disposed" }
            return
        }
        val currentMusic = music ?: return
        currentMusic.volume = if (isMusicEnabled) musicVolume else 0f
        if (!currentMusic.isPlaying) {
            currentMusic.play()
        }
    }

    fun pauseMusic() {
        if (isDisposed) return
        music?.pause()
    }

    fun resumeMusic() {
        if (isDisposed) return
        if (isMusicEnabled && music != null && !music!!.isPlaying) {
            music?.play()
        }
    }

    fun stopMusic() {
        if (isDisposed) return
        music?.stop()
    }

    fun toggleSound(): Boolean {
        if (isDisposed) return false
        isSoundEnabled = !isSoundEnabled
        return isSoundEnabled
    }

    fun toggleMusic(): Boolean {
        if (isDisposed) return false
        isMusicEnabled = !isMusicEnabled
        if (music != null) {
            if (isMusicEnabled) {
                music?.volume = musicVolume
                if (music != null && !music!!.isPlaying) {
                    music?.play()
                }
            } else {
                music?.pause()
            }
        }
        return isMusicEnabled
    }

    fun dispose() {
        if (isDisposed) return
        soundCache.values.forEach { it.disposeSafely() }
        soundCache.clear()
        music?.disposeSafely()
        music = null
        isDisposed = true
    }

    companion object {
        private val log = logger<AudioService>()
    }
}
