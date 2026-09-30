package io.github.shootgame.system

import com.badlogic.gdx.math.MathUtils
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*

@AllOf([AttackComponent::class, MoveComponent::class, ImageComponent::class, AnimationComponent::class])
@NoneOf([DeadComponent::class])
class CombatSystem(
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    private val cameraShakeSystem: CameraShakeSystem by lazy { world.system<CameraShakeSystem>() }
    private val reloadingEntities = mutableSetOf<Entity>()

    override fun onTickEntity(entity: Entity) {
        val attackCmp = attackCmps[entity]
        val moveCmp = moveCmps[entity]
        val imageCmp = imageCmps[entity]
        val animationCmp = animationCmps[entity]
        val weaponCmp = weaponCmps.getOrNull(entity)
        val currentWeapon = weaponCmp?.currentWeapon

        val maxAmmo = currentWeapon?.maxAmmo ?: attackCmp.maxAmmo
        val currentAmmo = if (weaponCmp != null && currentWeapon != null) {
            weaponCmp.ammoMap.getOrPut(currentWeapon) { maxAmmo }
        } else {
            attackCmp.ammo
        }
        val reloadDuration = currentWeapon?.reloadTime ?: attackCmp.reloadTime
        val fireRate = currentWeapon?.fireRate ?: attackCmp.fireRate

        if (attackCmp.isReloading) {
            if (entity !in reloadingEntities) {
                reloadingEntities.add(entity)
                audioService.play(SoundType.RELOAD)
            }
            attackCmp.stateTime += deltaTime
            if (attackCmp.stateTime >= reloadDuration) {
                if (weaponCmp != null && currentWeapon != null) {
                    weaponCmp.ammoMap[currentWeapon] = maxAmmo
                } else {
                    attackCmp.ammo = attackCmp.maxAmmo
                }
                attackCmp.isReloading = false
                attackCmp.stateTime = 0f
                reloadingEntities.remove(entity)
            }
            return
        } else {
            reloadingEntities.remove(entity)
        }

        if (attackCmp.stateTime > 0) {
            attackCmp.stateTime -= deltaTime
        }

        if (attackCmp.isAttacking && attackCmp.stateTime <= 0) {
            if (currentAmmo > 0) {
                // Wait for the fire frame in SHOT1 animation (faster response for automatic weapons)
                val minAnimDelay = if (currentWeapon == WeaponType.MACHINE_GUN) 0.05f else 0.15f
                if (animationCmp.type == AnimationType.SHOT1 && animationCmp.stateTime >= minAnimDelay) {
                    spawnBulletsForWeapon(entity, imageCmp, currentWeapon)
                    if (weaponCmp != null && currentWeapon != null) {
                        weaponCmp.ammoMap[currentWeapon] = currentAmmo - 1
                    } else {
                        attackCmp.ammo--
                    }
                    attackCmp.stateTime = fireRate

                    playWeaponSound(currentWeapon)
                    if (entity in playerCmps && currentWeapon != null && currentWeapon.cameraShake > 0f) {
                        cameraShakeSystem.trigger(amount = currentWeapon.cameraShake)
                    }
                }
            } else {
                attackCmp.isReloading = true
                attackCmp.stateTime = 0f
            }
        } else if (attackCmp.isThrowing && attackCmp.stateTime <= 0) {
            if (attackCmp.fragAmmo > 0) {
                if (animationCmp.type == AnimationType.GRENADE && animationCmp.stateTime >= 0.3f) {
                    spawnGrenade(entity, imageCmp, moveCmp)
                    attackCmp.fragAmmo--
                    attackCmp.stateTime = attackCmp.throwRate
                    audioService.play(SoundType.THROW, pitchVariation = 0.05f)
                }
            }
        }
    }

    private fun spawnGrenade(shooter: Entity, imageCmp: ImageComponent, moveCmp: MoveComponent) {
        val image = imageCmp.image
        val facingRight = image.scaleX > 0
        val x = image.x + image.width * 0.5f + (if (facingRight) 0.5f else -0.5f)
        val y = image.y + image.height * 0.5f

        world.entity {
            add<SpawnComponent> {
                type = "Frag"
                location.set(x, y)
            }
            add<OwnerComponent> {
                owner = shooter
            }
            add<MoveComponent> {
                cos = moveCmp.lastCos
                sin = 0.5f // Throw slightly upwards
                speed = 8f
            }
        }
    }

    private fun spawnBulletsForWeapon(
        shooter: Entity,
        imageCmp: ImageComponent,
        weapon: WeaponType?
    ) {
        val image = imageCmp.image
        val facingRight = image.scaleX > 0

        val offsetX = if (facingRight) 0.6f else -0.6f
        val offsetY = -0.9f

        val startX = image.x + image.width * 0.5f + offsetX
        val startY = image.y + image.height * 0.5f + offsetY

        val baseSpeed = weapon?.bulletSpeed ?: 12f
        val bulletDamage = weapon?.damage ?: 15f
        val lifeTime = weapon?.bulletLifeTime ?: 3f
        val bulletCount = weapon?.bulletCount ?: 1
        val spreadAngle = weapon?.spreadAngle ?: 0f

        val bulletWidth = when (weapon) {
            WeaponType.SHOTGUN -> 0.14f
            WeaponType.SNIPER -> 0.35f
            WeaponType.MACHINE_GUN -> 0.18f
            else -> 0.2f
        }
        val bulletHeight = when (weapon) {
            WeaponType.SHOTGUN -> 0.14f
            WeaponType.SNIPER -> 0.12f
            else -> 0.2f
        }

        val baseAngleDeg = if (facingRight) 0f else 180f

        for (i in 0 until bulletCount) {
            val angleOffset = if (bulletCount > 1) {
                -spreadAngle / 2f + (spreadAngle / (bulletCount - 1)) * i
            } else if (spreadAngle > 0f) {
                MathUtils.random(-spreadAngle / 2f, spreadAngle / 2f)
            } else {
                0f
            }

            val finalAngleDeg = baseAngleDeg + angleOffset
            val rad = MathUtils.degreesToRadians * finalAngleDeg
            val bulletCos = MathUtils.cos(rad)
            val bulletSin = MathUtils.sin(rad)

            world.entity {
                add<SpawnComponent> {
                    type = "Bullet"
                    location.set(startX, startY)
                }
                add<OwnerComponent> {
                    owner = shooter
                }
                add<MoveComponent> {
                    cos = bulletCos
                    sin = bulletSin
                    speed = baseSpeed
                }
                add<DamageComponent> {
                    amount = bulletDamage
                }
                add<BulletComponent> {
                    this.lifeTime = lifeTime
                    this.width = bulletWidth
                    this.height = bulletHeight
                }
            }
        }
    }

    private fun playWeaponSound(weapon: WeaponType?) {
        when (weapon) {
            WeaponType.SHOTGUN -> audioService.play(
                SoundType.SHOT,
                volumeModifier = 1.0f,
                pitchVariation = 0.05f,
                basePitch = 0.72f
            )
            WeaponType.MACHINE_GUN -> audioService.play(
                SoundType.SHOT,
                volumeModifier = 0.75f,
                pitchVariation = 0.12f,
                basePitch = 1.25f
            )
            WeaponType.SNIPER -> audioService.play(
                SoundType.SHOT,
                volumeModifier = 1.0f,
                pitchVariation = 0.02f,
                basePitch = 0.55f
            )
            else -> audioService.play(
                SoundType.SHOT,
                volumeModifier = 0.9f,
                pitchVariation = 0.08f,
                basePitch = 1.0f
            )
        }
    }
}
