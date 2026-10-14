package io.github.shootgame.system

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.screen.GameSettings
import kotlin.math.cos
import kotlin.math.sin

class CameraShakeSystem : IntervalSystem() {

    private var remainingTime = 0f
    private var totalTime = 0f
    private var amplitude = 0f
    private var elapsed = 0f
    private var hasBasePosition = false
    private val basePosition = Vector2()

    fun trigger(
        duration: Float = SHAKE_DURATION,
        amount: Float = SHAKE_AMPLITUDE,
        vibrateMs: Int = 55
    ) {
        GameSettings.ensureLoaded()
        if (!GameSettings.vibrationEnabled || GameSettings.vibrationIntensity <= 0.01f) return
        if (duration <= 0f || amount <= 0f) return

        val scaledAmount = (amount * GameSettings.vibrationIntensity).coerceIn(0f, 1.5f)
        if (scaledAmount <= 0.001f) return

        if (remainingTime <= 0f) {
            totalTime = duration
            elapsed = 0f
            amplitude = scaledAmount
            hasBasePosition = false
            remainingTime = duration
        } else {
            remainingTime = maxOf(remainingTime, duration)
            totalTime = maxOf(totalTime, elapsed + remainingTime)
            amplitude = maxOf(amplitude, scaledAmount)
        }

        if (vibrateMs > 0 && Gdx.app?.type == Application.ApplicationType.Android) {
            try {
                val scaledMs = (vibrateMs * GameSettings.vibrationIntensity).toInt().coerceIn(15, 450)
                Gdx.input.vibrate(scaledMs)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Heavy "BOOOM" camera shake + haptic rumble for bomb explosions and settings preview.
     */
    fun triggerExplosionBoom(distanceFactor: Float = 1.0f) {
        val factor = distanceFactor.coerceIn(0.30f, 1.25f)
        trigger(
            duration = 0.48f * factor.coerceAtLeast(0.65f),
            amount = 0.56f * factor,
            vibrateMs = (160f * factor).toInt()
        )
    }

    fun apply(camera: OrthographicCamera) {
        if (remainingTime <= 0f) return
        if (!hasBasePosition) {
            basePosition.set(camera.position.x, camera.position.y)
            hasBasePosition = true
        }

        val progress = (1f - elapsed / totalTime).coerceIn(0f, 1f)
        val currentAmplitude = amplitude * progress
        camera.position.x = basePosition.x + sin(elapsed * SHAKE_FREQUENCY) * currentAmplitude
        camera.position.y = basePosition.y + cos(elapsed * SHAKE_FREQUENCY * 0.8f) * currentAmplitude
    }

    override fun onTick() {
        if (remainingTime <= 0f) return

        elapsed += deltaTime
        remainingTime = (remainingTime - deltaTime).coerceAtLeast(0f)
        if (remainingTime <= 0f) {
            reset()
        }
    }

    private fun reset() {
        remainingTime = 0f
        totalTime = 0f
        amplitude = 0f
        elapsed = 0f
        hasBasePosition = false
    }

    companion object {
        private const val SHAKE_DURATION = 0.35f
        private const val SHAKE_AMPLITUDE = 0.35f
        private const val SHAKE_FREQUENCY = 45f
    }
}

