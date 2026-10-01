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

/** Owns the game's sound effects and background music lifecycle. */
class AudioService {
    private val soundCache = mutableMapOf<SoundType, Sound>()
    private var music: Music? = null

    var isSoundEnabled: Boolean = true
    var isMusicEnabled: Boolean = true

    var soundVolume: Float = DEFAULT_SOUND_VOLUME
        set(value) {
            field = value.coerceIn(MIN_VOLUME, MAX_VOLUME)
        }

    var musicVolume: Float = DEFAULT_MUSIC_VOLUME
        set(value) {
            field = value.coerceIn(MIN_VOLUME, MAX_VOLUME)
            music?.volume = if (isMusicEnabled) field else MIN_VOLUME
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
            val musicFile = Gdx.files.internal(BACKGROUND_MUSIC)
            if (musicFile.exists()) {
                music = Gdx.audio.newMusic(musicFile).apply {
                    isLooping = true
                    volume = if (isMusicEnabled) musicVolume else MIN_VOLUME
                }
                log.info { "Loaded background music: $BACKGROUND_MUSIC" }
            } else {
                log.error { "Background music file not found: $BACKGROUND_MUSIC" }
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load background music: $BACKGROUND_MUSIC" }
        }
    }

    /** Plays a cached sound, if sound playback is enabled and the asset loaded. */
    fun play(type: SoundType, volumeModifier: Float = 1f, pitchVariation: Float = 0f) {
        if (!isSoundEnabled) return
        val sound = soundCache[type] ?: return

        val finalVolume = (soundVolume * volumeModifier).coerceIn(MIN_VOLUME, MAX_VOLUME)
        val finalPitch = if (pitchVariation > 0f) {
            (1f + MathUtils.random(-pitchVariation, pitchVariation)).coerceIn(MIN_PITCH, MAX_PITCH)
        } else {
            1f
        }

        sound.play(finalVolume, finalPitch, 0f)
    }

    /** Starts background music when it is loaded and not already playing. */
    fun playMusic() {
        music?.let {
            it.volume = if (isMusicEnabled) musicVolume else MIN_VOLUME
            if (!it.isPlaying) it.play()
        }
    }

    fun pauseMusic() {
        music?.pause()
    }

    fun resumeMusic() {
        music?.let {
            if (isMusicEnabled && !it.isPlaying) it.play()
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
        music?.let {
            if (isMusicEnabled) {
                it.volume = musicVolume
                if (!it.isPlaying) it.play()
            } else {
                it.pause()
            }
        }
        return isMusicEnabled
    }

    /** Releases all native audio resources owned by this service. */
    fun dispose() {
        soundCache.values.forEach { it.disposeSafely() }
        soundCache.clear()
        music?.disposeSafely()
        music = null
    }

    companion object {
        private const val BACKGROUND_MUSIC = "sounds/bgm.wav"
        private const val DEFAULT_SOUND_VOLUME = 0.8f
        private const val DEFAULT_MUSIC_VOLUME = 0.5f
        private const val MIN_VOLUME = 0f
        private const val MAX_VOLUME = 1f
        private const val MIN_PITCH = 0.5f
        private const val MAX_PITCH = 2f
        private val log = logger<AudioService>()
    }
}
