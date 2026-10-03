package io.github.shootgame.system

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.NoneOf
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.DeadComponent
import io.github.shootgame.component.MoveComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.RemotePlayerComponent
import io.github.shootgame.component.WeaponComponent
import io.github.shootgame.component.WeaponType

import com.badlogic.gdx.physics.box2d.World as PhWorld
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PickupType

@AllOf([PlayerComponent::class, MoveComponent::class, AttackComponent::class, PhysicComponent::class])
@NoneOf([DeadComponent::class, RemotePlayerComponent::class])
class PlayerInputSystem(
    private val phWorld: PhWorld,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>
) : IteratingSystem() {
    private val uiSystem: UiSystem by lazy { world.system<UiSystem>() }
    private var pickupSpawnIndex = 0

    override fun onTickEntity(entity: Entity) {
        val moveCmp = moveCmps[entity]
        val attackCmp = attackCmps[entity]

        var x = 0f

        if (!attackCmp.isReloading && !attackCmp.isAttacking && !attackCmp.isThrowing) {
            if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT) || uiSystem.touchLeft) x -= 1f
            if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT) || uiSystem.touchRight) x += 1f
        }

        if (x != 0f) {
            moveCmp.cos = x
            moveCmp.sin = 0f
            moveCmp.lastCos = moveCmp.cos
            moveCmp.lastSin = moveCmp.sin
        } else {
            moveCmp.cos = 0f
            moveCmp.sin = 0f
        }

        // Weapon switching (1-4 keys, Q/E cycle, or touch button)
        val weaponCmp = weaponCmps.getOrNull(entity)
        if (weaponCmp != null) {
            val previousWeapon = weaponCmp.currentWeapon
            when {
                Gdx.input.isKeyJustPressed(Input.Keys.NUM_1) -> weaponCmp.selectWeapon(WeaponType.PISTOL)
                Gdx.input.isKeyJustPressed(Input.Keys.NUM_2) -> weaponCmp.selectWeapon(WeaponType.SHOTGUN)
                Gdx.input.isKeyJustPressed(Input.Keys.NUM_3) -> weaponCmp.selectWeapon(WeaponType.MACHINE_GUN)
                Gdx.input.isKeyJustPressed(Input.Keys.NUM_4) -> weaponCmp.selectWeapon(WeaponType.SNIPER)
                Gdx.input.isKeyJustPressed(Input.Keys.Q) -> weaponCmp.switchPrevious()
                Gdx.input.isKeyJustPressed(Input.Keys.E) || uiSystem.consumeWeaponSwitch() -> weaponCmp.switchNext()
            }
            if (weaponCmp.currentWeapon != previousWeapon) {
                // Cancel current reload when swapping weapons and add small swap cooldown
                attackCmp.isReloading = false
                attackCmp.stateTime = 0.15f
            }
        }

        attackCmp.isAttacking = Gdx.input.isKeyPressed(Input.Keys.SPACE) || uiSystem.touchShoot

        if (Gdx.input.isKeyJustPressed(Input.Keys.R) || uiSystem.touchReload) {
            attackCmp.isReloading = true
        }

        attackCmp.isThrowing = Gdx.input.isKeyJustPressed(Input.Keys.G) || uiSystem.touchGrenade

        moveCmp.doJump = Gdx.input.isKeyJustPressed(Input.Keys.W) || Gdx.input.isKeyJustPressed(Input.Keys.UP) || uiSystem.touchJump
        
        // Debug / Test Loot drop on 'P' key
        if (Gdx.input.isKeyJustPressed(Input.Keys.P)) {
            val physic = physicCmps.getOrNull(entity)
            if (physic != null) {
                val types = PickupType.values()
                val nextType = types[(pickupSpawnIndex++) % types.size]
                val pickupEntity = PickupSystem.spawn(
                    world = world,
                    phWorld = phWorld,
                    type = nextType,
                    x = physic.body.position.x + if (moveCmp.lastCos >= 0) 0.8f else -0.8f,
                    y = physic.body.position.y + 1.2f,
                    lifeTime = 20f
                )
                val pickupPhysic = world.mapper<PhysicComponent>().getOrNull(pickupEntity)
                pickupPhysic?.body?.setLinearVelocity(if (moveCmp.lastCos >= 0) 2.5f else -2.5f, 4f)
            }
        }
    }
}
