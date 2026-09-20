package io.github.shootgame.system

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.IntervalSystem
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
        amount: Float = SHAKE_AMPLITUDE
    ) {
        if (duration <= 0f || amount <= 0f) return

        if (remainingTime <= 0f) {
            totalTime = duration
            elapsed = 0f
            amplitude = amount
            hasBasePosition = false
            remainingTime = duration
        } else {
            remainingTime = maxOf(remainingTime, duration)
            totalTime = maxOf(totalTime, elapsed + remainingTime)
            amplitude = maxOf(amplitude, amount)
        }
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
