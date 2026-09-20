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

class DamageSystem(
    private val phWorld: PhWorld,
    private val damageCmps: ComponentMapper<DamageComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val ownerCmps: ComponentMapper<OwnerComponent>,
    private val bulletCmps: ComponentMapper<BulletComponent>
) : IntervalSystem(), ContactListener {

    private data class PendingDamage(val source: Entity, val target: Entity, val amount: Float)

    private val pendingDamage = mutableListOf<PendingDamage>()
    private val queuedHits = mutableSetOf<Pair<Entity, Entity>>()

    init {
        phWorld.setContactListener(this)
    }

    override fun onTick() {
        val damageRequests = pendingDamage.toList()
        pendingDamage.clear()
        queuedHits.clear()

        val healthSystem = world.system<HealthSystem>()
        damageRequests.forEach { request ->
            if (request.source !in damageCmps || request.target !in healthCmps) return@forEach

            healthSystem.damage(request.target, request.amount)
            if (request.source in bulletCmps) {
                world.remove(request.source)
            }
        }
    }

    override fun beginContact(contact: Contact) {
        val entityA = contact.fixtureA.body.userData as? Entity
        val entityB = contact.fixtureB.body.userData as? Entity
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
