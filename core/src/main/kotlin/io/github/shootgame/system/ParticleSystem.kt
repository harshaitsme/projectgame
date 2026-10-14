package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.ParticleComponent
import io.github.shootgame.component.ParticleType

@AllOf([ParticleComponent::class, ImageComponent::class])
class ParticleSystem(
    private val particleCmps: ComponentMapper<ParticleComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>
) : IteratingSystem() {

    private val circleRegion: TextureRegion by lazy {
        val pixmap = Pixmap(16, 16, Pixmap.Format.RGBA8888)
        pixmap.setColor(1f, 1f, 1f, 1f)
        pixmap.fillCircle(8, 8, 7)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    override fun onTickEntity(entity: Entity) {
        val particle = particleCmps[entity]
        val imageCmp = imageCmps[entity]
        val image = imageCmp.image

        particle.lifeTime -= deltaTime
        if (particle.lifeTime <= 0f) {
            world.remove(entity)
            return
        }

        // Apply physics
        particle.velY -= particle.gravity * deltaTime
        image.setPosition(
            image.x + particle.velX * deltaTime,
            image.y + particle.velY * deltaTime
        )

        // Interpolate progress (0 -> 1)
        val t = (1f - (particle.lifeTime / particle.maxLifeTime)).coerceIn(0f, 1f)

        // Scale interpolation
        val scale = MathUtils.lerp(particle.scaleStart, particle.scaleEnd, t)
        image.setScale(scale)

        // Color & alpha interpolation
        image.color.set(particle.colorStart).lerp(particle.colorEnd, t)
    }

    fun spawnBulletTrail(x: Float, y: Float) {
        world.entity {
            add<ImageComponent> {
                image = Image(circleRegion).apply {
                    setSize(0.12f, 0.12f)
                    setOrigin(Align.center)
                    setScaling(Scaling.fill)
                    setPosition(x - 0.06f, y - 0.06f)
                    color = Color(1f, 0.95f, 0.4f, 0.8f) // Golden yellow trail
                }
            }
            add<ParticleComponent> {
                type = ParticleType.BULLET_TRAIL
                maxLifeTime = 0.15f
                lifeTime = 0.15f
                scaleStart = 1f
                scaleEnd = 0.2f
                colorStart.set(1f, 0.95f, 0.4f, 0.8f)
                colorEnd.set(1f, 0.6f, 0.1f, 0f)
            }
        }
    }

    fun spawnSparks(x: Float, y: Float, count: Int = 6) {
        for (i in 0 until count) {
            val angle = MathUtils.random(0f, MathUtils.PI2)
            val speed = MathUtils.random(2f, 6f)
            val life = MathUtils.random(0.15f, 0.35f)

            world.entity {
                add<ImageComponent> {
                    image = Image(circleRegion).apply {
                        setSize(0.08f, 0.08f)
                        setOrigin(Align.center)
                        setScaling(Scaling.fill)
                        setPosition(x - 0.04f, y - 0.04f)
                        color = Color(1f, 0.85f, 0.2f, 1f)
                    }
                }
                add<ParticleComponent> {
                    type = ParticleType.SPARK
                    maxLifeTime = life
                    lifeTime = life
                    velX = MathUtils.cos(angle) * speed
                    velY = MathUtils.sin(angle) * speed
                    gravity = 9.8f
                    scaleStart = 1f
                    scaleEnd = 0.1f
                    colorStart.set(1f, 0.9f, 0.3f, 1f)
                    colorEnd.set(1f, 0.2f, 0f, 0f)
                }
            }
        }
    }

    fun spawnFootstepDust(x: Float, y: Float) {
        world.entity {
            add<ImageComponent> {
                image = Image(circleRegion).apply {
                    setSize(0.15f, 0.15f)
                    setOrigin(Align.center)
                    setScaling(Scaling.fill)
                    setPosition(x - 0.075f, y - 0.075f)
                    color = Color(0.8f, 0.8f, 0.8f, 0.6f)
                }
            }
            add<ParticleComponent> {
                type = ParticleType.DUST
                maxLifeTime = 0.3f
                lifeTime = 0.3f
                velX = MathUtils.random(-0.5f, 0.5f)
                velY = MathUtils.random(0.2f, 0.8f)
                scaleStart = 0.5f
                scaleEnd = 1.3f
                colorStart.set(0.85f, 0.85f, 0.85f, 0.5f)
                colorEnd.set(0.6f, 0.6f, 0.6f, 0f)
            }
        }
    }

    override fun onDispose() {
        if (circleRegion.texture != null) {
            circleRegion.texture.dispose()
        }
    }
}
