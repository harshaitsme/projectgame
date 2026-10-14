package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*

@AllOf([BossComponent::class, HealthComponent::class, ImageComponent::class, PhysicComponent::class])
@NoneOf([DeadComponent::class])
class BossSystem(
    private val bossCmps: ComponentMapper<BossComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val boss = bossCmps[entity]
        val health = healthCmps[entity]
        val imageCmp = imageCmps[entity]
        val physic = physicCmps[entity]

        // Find active Player
        var playerX = 0f
        var playerY = 0f
        var playerFound = false
        world.family(allOf = arrayOf(PlayerComponent::class, ImageComponent::class), noneOf = arrayOf(DeadComponent::class)).forEach { player ->
            val img = imageCmps[player].image
            playerX = img.x + img.width * 0.5f
            playerY = img.y + img.height * 0.5f
            playerFound = true
        }

        val bossX = imageCmp.image.x + imageCmp.image.width * 0.5f
        val bossY = imageCmp.image.y + imageCmp.image.height * 0.5f

        // Face player
        if (playerFound) {
            val facingRight = playerX >= bossX
            imageCmp.image.originX = imageCmp.image.width * 0.5f
            imageCmp.image.scaleX = if (facingRight) 1.5f else -1.5f
        }

        // Check Enrage mode (< 40% HP)
        val hpPercent = health.currentHealth / health.maxHealth
        if (hpPercent <= 0.40f && !boss.isEnraged) {
            boss.isEnraged = true
            boss.attackCooldown = 1.3f
            boss.spiralCooldown = 2.5f
            boss.slamCooldown = 3.2f
            imageCmp.image.color = Color(1.0f, 0.1f, 0.1f, 1f) // Intense enraged crimson
            world.system<CameraShakeSystem>().trigger(duration = 0.6f, amount = 0.5f)
            audioService.play(SoundType.EXPLOSION, pitchVariation = 0.2f)
        }

        // Attack 1: Spiral Bullet Barrage
        boss.spiralTimer += deltaTime
        if (boss.spiralTimer >= boss.spiralCooldown) {
            boss.spiralTimer = 0f
            spawnBulletSpiral(entity, bossX, bossY, if (boss.isEnraged) 12 else 8)
        }

        // Attack 2: Ground Slam Shockwave
        boss.slamTimer += deltaTime
        if (boss.slamTimer >= boss.slamCooldown && playerFound) {
            boss.slamTimer = 0f
            performGroundSlam(entity, bossX, bossY)
        }
    }

    private fun spawnBulletSpiral(bossEntity: Entity, originX: Float, originY: Float, bulletCount: Int) {
        val angleStep = 360f / bulletCount
        for (i in 0 until bulletCount) {
            val angleDeg = i * angleStep
            val rad = MathUtils.degreesToRadians * angleDeg
            val cos = MathUtils.cos(rad)
            val sin = MathUtils.sin(rad)

            world.entity {
                add<SpawnComponent> {
                    type = "Bullet"
                    location.set(originX, originY)
                }
                add<OwnerComponent> {
                    owner = bossEntity
                }
                add<DamageComponent> {
                    amount = 14f
                }
                add<BulletComponent> {
                    lifeTime = 2.5f
                    width = 0.22f
                    height = 0.22f
                }
                add<MoveComponent> {
                    this.cos = cos
                    this.sin = sin
                    speed = 7.5f
                }
            }
        }
        audioService.play(SoundType.SHOTGUN, pitchVariation = 0.1f)
    }

    private fun performGroundSlam(bossEntity: Entity, originX: Float, originY: Float) {
        // Trigger camera shake and particle effects
        world.system<CameraShakeSystem>().trigger(duration = 0.45f, amount = 0.45f)
        world.system<ParticleSystem>().spawnSparks(originX, originY, count = 25)

        // Spawn damaging explosion at slam center
        world.entity {
            add<SpawnComponent> {
                type = "Explosion"
                location.set(originX, originY)
            }
            add<OwnerComponent> {
                owner = bossEntity
            }
            add<DamageComponent> {
                amount = 28f
            }
        }
        audioService.play(SoundType.EXPLOSION, pitchVariation = 0.05f)
    }
}
