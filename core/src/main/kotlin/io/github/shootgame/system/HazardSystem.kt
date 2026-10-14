package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*

@AllOf([HazardComponent::class])
class HazardSystem(
    private val hazardCmps: ComponentMapper<HazardComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val statusEffectCmps: ComponentMapper<StatusEffectComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    private val cameraShakeSystem: CameraShakeSystem by lazy { world.system<CameraShakeSystem>() }
    private val particleSystem: ParticleSystem by lazy { world.system<ParticleSystem>() }
    private val healthSystem: HealthSystem by lazy { world.system<HealthSystem>() }
    private var systemTime: Float = 0f

    override fun onTick() {
        systemTime += deltaTime
        super.onTick()
    }

    override fun onTickEntity(entity: Entity) {
        val hazard = hazardCmps[entity]
        val pos = getPosition(entity)

        when (hazard.type) {
            HazardType.EXPLOSIVE_BARREL -> {
                val health = healthCmps.getOrNull(entity)
                if (hazard.isTriggered || (health != null && health.currentHealth <= 0f)) {
                    detonateBarrel(entity, hazard, pos)
                } else if (health != null && health.currentHealth < health.maxHealth) {
                    val img = imageCmps.getOrNull(entity)?.image
                    if (img != null) {
                        val flicker = 0.7f + 0.3f * MathUtils.sin(systemTime * 15f)
                        img.color = Color(1f, flicker * 0.4f, flicker * 0.2f, 1f)
                    }
                }
            }

            HazardType.ELECTRIC_GATE, HazardType.TOXIC_SPIKES -> {
                hazard.cycleTimer += deltaTime
                if (hazard.cycleTimer >= hazard.cyclePeriod) {
                    hazard.cycleTimer = 0f
                    hazard.isActive = !hazard.isActive
                    if (hazard.isActive) {
                        particleSystem.spawnSparks(pos.x, pos.y, count = 10)
                        audioService.play(SoundType.SHOT, pitchVariation = 0.3f)
                    }
                }

                val img = imageCmps.getOrNull(entity)?.image
                if (img != null) {
                    if (hazard.isActive) {
                        img.color = if (hazard.type == HazardType.ELECTRIC_GATE) {
                            Color(0.2f, 0.85f, 1f, 0.95f)
                        } else {
                            Color(0.2f, 0.95f, 0.3f, 0.95f)
                        }
                        if (MathUtils.randomBoolean(0.2f)) {
                            particleSystem.spawnSparks(pos.x, pos.y, count = 3)
                        }
                    } else {
                        img.color = Color(0.4f, 0.4f, 0.4f, 0.25f)
                    }
                }

                if (hazard.isActive) {
                    hazard.timeSinceLastTick += deltaTime
                    if (hazard.timeSinceLastTick >= hazard.tickInterval) {
                        hazard.timeSinceLastTick = 0f
                        applyHazardZoneTick(pos.x, pos.y, hazard)
                    }
                }
            }
        }
    }

    private fun detonateBarrel(entity: Entity, hazard: HazardComponent, pos: Vector2) {
        hazard.isTriggered = true

        world.entity {
            add<SpawnComponent> {
                type = "Explosion"
                location.set(pos.x - 1.5f, pos.y - 1.5f)
            }
            add<ExplosionComponent> {
                duration = 0.8f
                range = hazard.blastRadius
                centerX = pos.x
                centerY = pos.y
            }
        }

        particleSystem.spawnSparks(pos.x, pos.y, count = 24)
        particleSystem.spawnBulletTrail(pos.x, pos.y)
        cameraShakeSystem.trigger(0.35f, 0.45f)
        audioService.play(SoundType.EXPLOSION)

        // Detonate adjacent barrels directly for chain reactions
        world.family(allOf = arrayOf(HazardComponent::class)).forEach { other ->
            if (other != entity) {
                val otherPos = getPosition(other)
                val dist = Vector2.dst(pos.x, pos.y, otherPos.x, otherPos.y)
                if (dist <= hazard.blastRadius) {
                    val otherHazard = hazardCmps[other]
                    if (!otherHazard.isTriggered) {
                        val otherHealth = healthCmps.getOrNull(other)
                        if (otherHealth != null) {
                            otherHealth.currentHealth = 0f
                            otherHazard.isTriggered = true
                        }
                    }
                }
            }
        }

        world.remove(entity)
    }

    private fun applyHazardZoneTick(centerX: Float, centerY: Float, hazard: HazardComponent) {
        val radius = 1.6f
        world.family(allOf = arrayOf(HealthComponent::class)).forEach { target ->
            if (target in hazardCmps) return@forEach
            val targetPos = getPosition(target)
            val dist = Vector2.dst(centerX, centerY, targetPos.x, targetPos.y)
            if (dist <= radius) {
                healthSystem.damage(target, hazard.tickDamage)
                val status = statusEffectCmps.getOrNull(target)
                status?.addEffect(StatusEffectType.POISON, duration = 2.5f, magnitude = 8f)
                particleSystem.spawnSparks(targetPos.x, targetPos.y, count = 6)
            }
        }
    }

    private fun getPosition(entity: Entity): Vector2 {
        val bodyPos = physicCmps.getOrNull(entity)?.body?.position
        if (bodyPos != null) return bodyPos
        val img = imageCmps.getOrNull(entity)?.image
        val x = (img?.x ?: 0f) + (img?.width ?: 0f) * 0.5f
        val y = (img?.y ?: 0f) + (img?.height ?: 0f) * 0.5f
        return Vector2(x, y)
    }
}
