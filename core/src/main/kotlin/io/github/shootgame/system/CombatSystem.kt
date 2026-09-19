package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.component.*

@AllOf([AttackComponent::class, MoveComponent::class, ImageComponent::class, AnimationComponent::class])
@NoneOf([DeadComponent::class])
class CombatSystem(
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val attackCmp = attackCmps[entity]
        val moveCmp = moveCmps[entity]
        val imageCmp = imageCmps[entity]
        val animationCmp = animationCmps[entity]

        if (attackCmp.isReloading) {
            attackCmp.stateTime += deltaTime
            if (attackCmp.stateTime >= attackCmp.reloadTime) {
                attackCmp.ammo = attackCmp.maxAmmo
                attackCmp.isReloading = false
                attackCmp.stateTime = 0f
            }
            return
        }

        if (attackCmp.stateTime > 0) {
            attackCmp.stateTime -= deltaTime
        }

        if (attackCmp.isAttacking && attackCmp.stateTime <= 0) {
            if (attackCmp.ammo > 0) {
                // Wait for the fire frame in SHOT1 animation (e.g., 0.15s delay)
                if (animationCmp.type == AnimationType.SHOT1 && animationCmp.stateTime >= 0.15f) {
                    spawnBullet(entity, imageCmp, moveCmp)
                    attackCmp.ammo--
                    attackCmp.stateTime = attackCmp.fireRate
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

    private fun spawnBullet(shooter: Entity, imageCmp: ImageComponent, moveCmp: MoveComponent) {
        val image = imageCmp.image
        val facingRight = image.scaleX > 0

        // Define offsets in world units (relative to player center)
        // Adjust these values to perfectly align with your gun's nozzle
        val offsetX = if (facingRight) 0.6f else -0.6f
        val offsetY = -0.9f

        val x = image.x + image.width * 0.5f + offsetX
        val y = image.y + image.height * 0.5f + offsetY

        world.entity {
            add<SpawnComponent> {
                type = "Bullet"
                location.set(x, y)
            }
            add<OwnerComponent> {
                owner = shooter
            }
            add<MoveComponent> {
                cos = moveCmp.lastCos
                sin = moveCmp.lastSin
                speed = 15f
            }
        }
    }
}
