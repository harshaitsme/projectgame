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
@NoneOf([DeadComponent::class, RemotePlayerComponent::class])
class CombatSystem(
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    private val cameraShakeSystem: CameraShakeSystem by lazy { world.system<CameraShakeSystem>() }
    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }
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
        val reserveAmmo = if (weaponCmp != null && currentWeapon != null) {
            weaponCmp.reserveAmmoMap.getOrPut(currentWeapon) { currentWeapon.maxReserveAmmo }
        } else {
            0
        }
        val reloadDuration = currentWeapon?.reloadTime ?: attackCmp.reloadTime
        val fireRate = currentWeapon?.fireRate ?: attackCmp.fireRate

        if (attackCmp.isReloading) {
            if (weaponCmp != null && currentWeapon != null && (reserveAmmo <= 0 || currentAmmo >= maxAmmo)) {
                attackCmp.isReloading = false
                attackCmp.stateTime = 0f
                reloadingEntities.remove(entity)
                return
            }
            if (entity !in reloadingEntities) {
                reloadingEntities.add(entity)
                audioService.play(SoundType.RELOAD)
            }
            attackCmp.stateTime += deltaTime
            if (attackCmp.stateTime >= reloadDuration) {
                if (weaponCmp != null && currentWeapon != null) {
                    val needed = (maxAmmo - currentAmmo).coerceAtLeast(0)
                    val reserve = weaponCmp.reserveAmmoMap.getOrPut(currentWeapon) { currentWeapon.maxReserveAmmo }
                    if (reserve > 0 && needed > 0) {
                        val toLoad = needed.coerceAtMost(reserve)
                        weaponCmp.ammoMap[currentWeapon] = currentAmmo + toLoad
                        weaponCmp.reserveAmmoMap[currentWeapon] = (reserve - toLoad).coerceAtLeast(0)
                    }
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
                        weaponCmp.ammoMap[currentWeapon] = (currentAmmo - 1).coerceAtLeast(0)
                    } else {
                        attackCmp.ammo = (attackCmp.ammo - 1).coerceAtLeast(0)
                    }
                    attackCmp.stateTime = fireRate

                    playWeaponSound(currentWeapon)
                    if (entity in playerCmps && currentWeapon != null && currentWeapon.cameraShake > 0f) {
                        cameraShakeSystem.trigger(amount = currentWeapon.cameraShake)
                    }
                }
            } else if (reserveAmmo > 0) {
                attackCmp.isAttacking = false
                attackCmp.isReloading = true
                attackCmp.stateTime = 0f
            } else {
                // Out of both clip and reserve ammo: cannot shoot or reload
                attackCmp.isAttacking = false
                attackCmp.isReloading = false
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
        val throwCos = if (facingRight) 1f else -1f
        val throwSin = 0.55f
        val x = image.x + image.width * 0.5f + (if (facingRight) 0.5f else -0.5f)
        val y = image.y + image.height * 0.5f

        if (shooter in playerCmps && shooter !in remotePlayerCmps && networkSystem.isConnected) {
            networkSystem.sendBombThrow(
                originX = x,
                originY = y,
                dirX = throwCos,
                dirY = throwSin
            )
        }

        world.entity {
            add<SpawnComponent> {
                type = "Frag"
                location.set(x, y)
            }
            add<FragComponent> {
                fuseTime = 2.0f
                isRemote = false
            }
            add<OwnerComponent> {
                owner = shooter
            }
            add<MoveComponent> {
                cos = throwCos
                sin = throwSin
                speed = 7.5f
            }
        }
    }

    fun spawnRemoteGrenade(
        shooter: Entity?,
        remoteNetId: Int,
        startX: Float,
        startY: Float,
        dirX: Float,
        dirY: Float
    ) {
        val imageCmp = shooter?.let { imageCmps.getOrNull(it) }
        if (imageCmp != null && dirX != 0f) {
            imageCmp.image.originX = imageCmp.image.width * 0.5f
            imageCmp.image.scaleX = if (dirX < 0f) -1f else 1f
        }
        world.entity {
            add<SpawnComponent> {
                type = "Frag"
                location.set(startX, startY)
            }
            add<FragComponent> {
                fuseTime = 2.35f
                isRemote = true
                remoteOwnerNetId = remoteNetId
            }
            if (shooter != null) {
                add<OwnerComponent> {
                    owner = shooter
                }
            }
            add<MoveComponent> {
                cos = if (dirX < 0f) -1f else 1f
                sin = if (dirY != 0f) dirY else 0.55f
                speed = 7.5f
            }
        }
        audioService.play(SoundType.THROW, pitchVariation = 0.05f)
    }

    fun spawnRemoteShot(shooter: Entity, weapon: WeaponType?, dirX: Float) {
        val imageCmp = imageCmps.getOrNull(shooter) ?: return
        if (dirX != 0f) {
            imageCmp.image.originX = imageCmp.image.width * 0.5f
            imageCmp.image.scaleX = if (dirX < 0f) -1f else 1f
        }
        spawnBulletsForWeapon(shooter, imageCmp, weapon, isRemote = true)
        playWeaponSound(weapon)
    }

    private fun spawnBulletsForWeapon(
        shooter: Entity,
        imageCmp: ImageComponent,
        weapon: WeaponType?,
        isRemote: Boolean = false
    ) {
        val image = imageCmp.image
        val facingRight = image.scaleX > 0

        val offsetX = if (facingRight) 0.6f else -0.6f
        val offsetY = -0.9f

        val bodyCenterX = image.x + image.width * 0.5f
        val bodyCenterY = image.y + image.height * 0.5f
        val startX = bodyCenterX + offsetX
        val startY = bodyCenterY + offsetY

        if (!isRemote && shooter in playerCmps && shooter !in remotePlayerCmps && networkSystem.isConnected) {
            networkSystem.sendShoot(
                originX = startX,
                originY = bodyCenterY,
                dirX = if (facingRight) 1f else -1f,
                dirY = 0f,
                weaponIndex = weapon?.ordinal ?: 0
            )
        }

        val baseSpeed = weapon?.bulletSpeed ?: 12f
        val bulletDamage = weapon?.damage ?: 15f
        val lifeTime = weapon?.bulletLifeTime ?: 3f
        val bulletCount = weapon?.bulletCount ?: 1
        val spreadAngle = weapon?.spreadAngle ?: 0f

        val bulletWidth = when (weapon) {
            WeaponType.SHOTGUN -> 0.07f
            WeaponType.MACHINE_GUN -> 0.10f
            else -> 0.09f
        }
        val bulletHeight = when (weapon) {
            WeaponType.SHOTGUN -> 0.06f
            WeaponType.MACHINE_GUN -> 0.06f
            else -> 0.06f
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
                SoundType.SHOTGUN,
                volumeModifier = 1.0f,
                pitchVariation = 0.05f
            )
            WeaponType.MACHINE_GUN -> audioService.play(
                SoundType.SHOT,
                volumeModifier = 0.75f,
                pitchVariation = 0.12f,
                basePitch = 1.25f
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
