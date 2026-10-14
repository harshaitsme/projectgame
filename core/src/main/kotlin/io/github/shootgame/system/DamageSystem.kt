package io.github.shootgame.system

import com.badlogic.gdx.physics.box2d.Contact
import com.badlogic.gdx.physics.box2d.ContactImpulse
import com.badlogic.gdx.physics.box2d.ContactListener
import com.badlogic.gdx.physics.box2d.Manifold
import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.component.BulletComponent
import io.github.shootgame.component.DamageComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.OwnerComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent

class DamageSystem(
    private val phWorld: PhWorld,
    private val damageCmps: ComponentMapper<DamageComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val ownerCmps: ComponentMapper<OwnerComponent>,
    private val bulletCmps: ComponentMapper<BulletComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>
) : IntervalSystem(), ContactListener {

    private data class PendingDamage(val source: Entity, val target: Entity, val amount: Float)

    private val pendingDamage = mutableListOf<PendingDamage>()
    private val bulletsHitWall = mutableSetOf<Entity>()
    private val queuedHits = mutableSetOf<Pair<Entity, Entity>>()
    private val networkSystem: NetworkSystem by lazy { world.system<NetworkSystem>() }

    init {
        phWorld.setContactListener(this)
    }

    override fun onTick() {
        if (bulletsHitWall.isNotEmpty()) {
            val toRemove = bulletsHitWall.toList()
            bulletsHitWall.clear()
            toRemove.forEach { bullet ->
                if (bullet in bulletCmps) {
                    world.remove(bullet)
                }
            }
        }

        val damageRequests = pendingDamage.toList()
        pendingDamage.clear()
        queuedHits.clear()

        val healthSystem = world.system<HealthSystem>()
        damageRequests.forEach { request ->
            if (request.source !in damageCmps || request.target !in healthCmps) return@forEach

            // Remote players broadcast their own authoritative health; local player & AI enemies take damage directly
            if (request.target !in remotePlayerCmps) {
                val h = healthCmps[request.target]
                val wasAlive = !h.isDead && h.currentHealth > 0f
                val damaged = healthSystem.damage(request.target, request.amount)
                if (damaged && request.target in playerCmps) {
                    val attacker = ownerCmps.getOrNull(request.source)?.owner
                    if (attacker != null && attacker in remotePlayerCmps) {
                        networkSystem.onLocalPlayerHitByRemote(attacker, wasAlive && h.currentHealth <= 0f)
                    }
                }
            } else {
                // Record that we hit this remote player so if their broadcast health drops to 0 we credit the kill
                val shooter = ownerCmps.getOrNull(request.source)?.owner
                if (shooter != null && shooter in playerCmps && shooter !in remotePlayerCmps) {
                    networkSystem.onLocalBulletHitRemote(request.target)
                }
            }

            if (request.source in bulletCmps) {
                val pos = physicCmps.getOrNull(request.source)?.body?.position
                if (pos != null) {
                    world.system<ParticleSystem>().spawnSparks(pos.x, pos.y, count = 7)
                }
                world.remove(request.source)
            }
        }
    }

    override fun beginContact(contact: Contact) {
        val entityA = contact.fixtureA.body.userData as? Entity
        val entityB = contact.fixtureB.body.userData as? Entity

        // Destroy bullets when they hit static world geometry (walls/floors/ceilings where userData == null)
        if (entityA != null && entityA in bulletCmps && entityB == null && !contact.fixtureB.isSensor) {
            bulletsHitWall.add(entityA)
            return
        }
        if (entityB != null && entityB in bulletCmps && entityA == null && !contact.fixtureA.isSensor) {
            bulletsHitWall.add(entityB)
            return
        }

        queueDamage(entityA, entityB)
        queueDamage(entityB, entityA)
    }

    private fun queueDamage(source: Entity?, target: Entity?) {
        if (source == null || target == null || source == target) return
        val damage = damageCmps.getOrNull(source) ?: return
        if (target !in healthCmps) return

        val sourceOwner = ownerCmps.getOrNull(source)?.owner
        val targetOwner = ownerCmps.getOrNull(target)?.owner
        if (sourceOwner == target || targetOwner == source) return
        if (sourceOwner != null && targetOwner != null && sourceOwner == targetOwner) return

        if (!queuedHits.add(source to target)) return
        pendingDamage.add(PendingDamage(source, target, damage.amount))
    }

    override fun endContact(contact: Contact) = Unit

    override fun preSolve(contact: Contact, manifold: Manifold) = Unit

    override fun postSolve(contact: Contact, impulse: ContactImpulse) = Unit

    override fun onDispose() {
        phWorld.setContactListener(null)
    }
}
