package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PhysicComponent

import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.*
import kotlin.math.abs

@AllOf([MoveComponent::class, PhysicComponent::class])
@NoneOf([DeadComponent::class, RemotePlayerComponent::class])
class MoveSystem(
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val bulletCmps: ComponentMapper<BulletComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val fragCmps: ComponentMapper<FragComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val perkCmps: ComponentMapper<PerkComponent>,
    private val audioService: AudioService
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val moveCmp = moveCmps[entity]
        val physicCmp = physicCmps[entity]
        val isBullet = entity in bulletCmps
        val isFrag = entity in fragCmps
        val body = physicCmp.body
        val currentVelocity = body.linearVelocity

        // Movement
        if (isBullet) {
            // Bullets move in their intended direction (cos, sin) at constant speed
            body.setLinearVelocity(
                moveCmp.cos * moveCmp.speed,
                moveCmp.sin * moveCmp.speed
            )
            world.system<ParticleSystem>().spawnBulletTrail(body.position.x, body.position.y)
        } else if (isFrag) {
            // Let Box2D handle grenade arc & wall bounces; apply gentle friction when rolling on floor
            if (abs(currentVelocity.y) < 0.25f) {
                body.setLinearVelocity(currentVelocity.x * 0.94f, currentVelocity.y)
            }
        } else if (entity !in remotePlayerCmps) {
            // Keep local player & enemies inside the indoor map arena (prevent roof/void trapping)
            if (body.position.y > 9.5f || body.position.y < 1.0f) {
                body.setTransform(12.47f, 5.2f, 0f)
                body.setLinearVelocity(0f, 0f)
            } else {
                // Non-projectiles (Local Player & Enemies) move horizontally via input
                body.setLinearVelocity(
                    moveCmp.cos * moveCmp.speed,
                    currentVelocity.y
                )
            }
        }

        // Jump logic (only for local player / enemies)
        if (!isBullet && !isFrag && entity !in remotePlayerCmps && moveCmp.doJump) {
            val hasDoubleJump = entity in perkCmps && perkCmps[entity].hasPerk(PerkType.DOUBLE_JUMP)
            // Simple grounded check: check if vertical velocity is near 0
            if (abs(currentVelocity.y) < 0.1f) {
                body.applyLinearImpulse(0f, moveCmp.jumpImpulse, body.worldCenter.x, body.worldCenter.y, true)
                audioService.play(SoundType.JUMP, pitchVariation = 0.06f)
                world.system<ParticleSystem>().spawnFootstepDust(body.position.x, body.position.y - 0.35f)
                moveCmp.extraJumps = if (hasDoubleJump) 1 else 0
            } else if (moveCmp.extraJumps > 0) {
                body.setLinearVelocity(currentVelocity.x, 0f)
                body.applyLinearImpulse(0f, moveCmp.jumpImpulse * 0.95f, body.worldCenter.x, body.worldCenter.y, true)
                audioService.play(SoundType.JUMP, pitchVariation = 0.15f)
                world.system<ParticleSystem>().spawnSparks(body.position.x, body.position.y - 0.2f, count = 8)
                moveCmp.extraJumps--
            }
            moveCmp.doJump = false
        }

        // Update animation for all humanoid entities (Player & Enemies)
        if (entity in animationCmps && !isBullet && !isFrag) {
            val aniCmp = animationCmps[entity]
            val attackCmp = attackCmps.getOrNull(entity)

            if (attackCmp != null && attackCmp.isReloading) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.RECHARGE)
            } else if (attackCmp != null && attackCmp.isThrowing) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.GRENADE)
            } else if (attackCmp != null && attackCmp.isAttacking) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.SHOT1)
            } else if (moveCmp.cos != 0f || moveCmp.sin != 0f) {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.RUN)
            } else {
                aniCmp.nextAnimation(aniCmp.model, AnimationType.IDLE)
            }
        }

        if (entity in imageCmps && moveCmp.cos != 0f) {
            val image = imageCmps[entity].image
            image.originX = image.width * 0.5f
            image.scaleX = if (moveCmp.cos < 0f) -1f else 1f
        }
    }
}
