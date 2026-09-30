package io.github.shootgame.system

import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.component.*
import kotlin.math.abs

@AllOf([AiComponent::class, MoveComponent::class, AttackComponent::class, ImageComponent::class, PhysicComponent::class])
@NoneOf([DeadComponent::class])
class AiSystem(
    private val aiCmps: ComponentMapper<AiComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>
) : IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val ai = aiCmps[entity]
        val move = moveCmps[entity]
        val attack = attackCmps[entity]
        val image = imageCmps[entity].image
        val physic = physicCmps[entity]

        // Find the active Player entity
        var targetPlayer: Entity? = null
        world.family(allOf = arrayOf(PlayerComponent::class, ImageComponent::class), noneOf = arrayOf(DeadComponent::class)).forEach { player ->
            targetPlayer = player
        }

        val player = targetPlayer
        if (player == null || player !in imageCmps) {
            patrol(ai, move)
            attack.isAttacking = false
            return
        }

        val playerImg = imageCmps[player].image
        val enemyCenterX = image.x + image.width * 0.5f
        val enemyCenterY = image.y + image.height * 0.5f
        val playerCenterX = playerImg.x + playerImg.width * 0.5f
        val playerCenterY = playerImg.y + playerImg.height * 0.5f

        val dx = playerCenterX - enemyCenterX
        val dy = playerCenterY - enemyCenterY
        val distSq = dx * dx + dy * dy
        val detectionSq = ai.detectionRadius * ai.detectionRadius
        val attackSq = ai.attackRange * ai.attackRange

        if (distSq <= detectionSq) {
            val facingRight = dx >= 0
            image.originX = image.width * 0.5f
            image.scaleX = if (facingRight) 1f else -1f
            move.lastCos = if (facingRight) 1f else -1f

            if (distSq <= attackSq) {
                // In shooting range: Halt and fire towards player
                ai.state = AiState.ATTACK
                move.cos = 0f

                ai.shootTimer -= deltaTime
                if (ai.shootTimer <= 0f) {
                    attack.isAttacking = true
                    ai.shootTimer = ai.shootCooldown
                } else {
                    attack.isAttacking = false
                }
            } else {
                // In detection range: Chase the player
                ai.state = AiState.CHASE
                move.cos = if (facingRight) 0.65f else -0.65f
                attack.isAttacking = false

                // Jump if blocked or player is on a higher platform
                ai.jumpTimer -= deltaTime
                val body = physic.body
                val vel = body.linearVelocity
                val isStuck = abs(move.cos) > 0.1f && abs(vel.x) < 0.2f
                val playerHigher = dy > 1.2f && abs(dx) < 5f

                if ((isStuck || playerHigher) && ai.jumpTimer <= 0f) {
                    move.doJump = true
                    ai.jumpTimer = ai.jumpCooldown
                }
            }
        } else {
            // Outside detection range: Patrol back and forth
            ai.state = AiState.PATROL
            attack.isAttacking = false
            patrol(ai, move)

            // Turn around if bumped into a wall
            val body = physic.body
            val vel = body.linearVelocity
            if (abs(vel.x) < 0.1f && ai.patrolTimer > 0.4f) {
                ai.patrolDirection = -ai.patrolDirection
                ai.patrolTimer = 0f
            }
        }
    }

    private fun patrol(ai: AiComponent, move: MoveComponent) {
        ai.patrolTimer += deltaTime
        if (ai.patrolTimer >= ai.patrolInterval) {
            ai.patrolDirection = -ai.patrolDirection
            ai.patrolTimer = 0f
        }
        move.cos = ai.patrolDirection * 0.35f
    }
}
