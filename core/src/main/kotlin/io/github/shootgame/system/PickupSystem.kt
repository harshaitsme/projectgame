package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Align
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import com.github.quillraven.fleks.World
import io.github.shootgame.audio.AudioService
import io.github.shootgame.audio.SoundType
import io.github.shootgame.component.AttackComponent
import io.github.shootgame.component.HealthComponent
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent
import io.github.shootgame.component.PhysicComponent.Companion.physicCmpFromImage
import io.github.shootgame.component.PickupComponent
import io.github.shootgame.component.PickupType
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.component.WeaponComponent
import ktx.box2d.circle
import kotlin.math.sin
import kotlin.math.sqrt

@AllOf([PickupComponent::class, PhysicComponent::class, ImageComponent::class])
class PickupSystem(
    private val phWorld: PhWorld,
    private val audioService: AudioService,
    private val pickupCmps: ComponentMapper<PickupComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>
) : IteratingSystem() {

    private val healthRegion: TextureRegion by lazy {
        val pixmap = Pixmap(24, 24, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.1f, 0.1f, 0.15f, 1f) // Dark outer border
        pixmap.fillRectangle(0, 0, 24, 24)
        pixmap.setColor(0.95f, 0.95f, 0.95f, 1f) // White medical crate
        pixmap.fillRectangle(2, 2, 20, 20)
        pixmap.setColor(0.9f, 0.15f, 0.2f, 1f) // Red medical cross
        pixmap.fillRectangle(10, 4, 4, 16)
        pixmap.fillRectangle(4, 10, 16, 4)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    private val ammoRegion: TextureRegion by lazy {
        val pixmap = Pixmap(24, 24, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.12f, 0.2f, 0.12f, 1f) // Military green border
        pixmap.fillRectangle(0, 0, 24, 24)
        pixmap.setColor(0.25f, 0.45f, 0.25f, 1f) // Olive body
        pixmap.fillRectangle(2, 2, 20, 20)
        pixmap.setColor(1f, 0.85f, 0.1f, 1f) // Gold bullets
        pixmap.fillRectangle(6, 6, 3, 12)
        pixmap.fillRectangle(11, 6, 3, 12)
        pixmap.fillRectangle(16, 6, 3, 12)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    private val grenadeRegion: TextureRegion by lazy {
        val pixmap = Pixmap(24, 24, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.1f, 0.1f, 0.1f, 0f) // Transparent background
        pixmap.fill()
        pixmap.setColor(0.15f, 0.35f, 0.15f, 1f) // Dark olive grenade
        pixmap.fillCircle(12, 13, 9)
        pixmap.setColor(0.7f, 0.7f, 0.75f, 1f) // Metal cap and ring
        pixmap.fillRectangle(10, 2, 4, 4)
        pixmap.drawCircle(8, 3, 3)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    private val coinRegion: TextureRegion by lazy {
        val pixmap = Pixmap(24, 24, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.1f, 0.1f, 0.1f, 0f) // Transparent
        pixmap.fill()
        pixmap.setColor(0.85f, 0.65f, 0.05f, 1f) // Dark gold rim
        pixmap.fillCircle(12, 12, 10)
        pixmap.setColor(1f, 0.88f, 0.15f, 1f) // Bright gold coin
        pixmap.fillCircle(12, 12, 8)
        pixmap.setColor(1f, 1f, 0.8f, 0.8f) // Shine highlight
        pixmap.drawCircle(12, 12, 5)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    fun getRegionFor(type: PickupType): TextureRegion = when (type) {
        PickupType.HEALTH -> healthRegion
        PickupType.AMMO -> ammoRegion
        PickupType.GRENADE -> grenadeRegion
        PickupType.COIN -> coinRegion
    }

    override fun onTickEntity(entity: Entity) {
        val pickup = pickupCmps[entity]
        val physic = physicCmps[entity]
        val imageCmp = imageCmps[entity]
        val pickupPos = physic.body.position

        // Floating / bobbing visual pulse
        pickup.bobTimer += deltaTime * 5f
        val pulse = 1f + 0.08f * sin(pickup.bobTimer)
        imageCmp.image.setOrigin(Align.center)
        imageCmp.image.setScale(pulse)

        // Lifetime handling (optional despawn)
        if (pickup.lifeTime > 0f) {
            pickup.lifeTime -= deltaTime
            if (pickup.lifeTime <= 0f) {
                world.remove(entity)
                return
            } else if (pickup.lifeTime <= 3f) {
                // Flash when about to disappear
                val visible = ((pickup.lifeTime * 8).toInt() % 2) == 0
                imageCmp.image.color = if (visible) Color.WHITE else Color.CLEAR
            }
        }

        // Find player entity
        var player: Entity? = null
        world.family(
            allOf = arrayOf(PlayerComponent::class, PhysicComponent::class)
        ).forEach {
            player = it
        }
        val targetPlayer = player ?: return

        val playerPhysic = physicCmps[targetPlayer]
        val playerPos = playerPhysic.body.position

        val dx = playerPos.x - pickupPos.x
        val dy = playerPos.y - pickupPos.y
        val distSq = dx * dx + dy * dy

        // Collection detection
        if (distSq <= pickup.collectRadius * pickup.collectRadius) {
            collectPickup(targetPlayer, pickup)
            world.remove(entity)
            return
        }

        // Magnet attraction
        if (distSq <= pickup.magnetRadius * pickup.magnetRadius) {
            val dist = sqrt(distSq).coerceAtLeast(0.001f)
            val dirX = dx / dist
            val dirY = dy / dist
            val speed = 8f * (1f - dist / pickup.magnetRadius) + 4f
            physic.body.setLinearVelocity(dirX * speed, dirY * speed)
        }
    }

    private fun collectPickup(player: Entity, pickup: PickupComponent) {
        when (pickup.type) {
            PickupType.HEALTH -> {
                val health = healthCmps.getOrNull(player)
                if (health != null) {
                    health.currentHealth = (health.currentHealth + pickup.amount).coerceAtMost(health.maxHealth)
                }
            }
            PickupType.AMMO -> {
                val weaponCmp = weaponCmps.getOrNull(player)
                if (weaponCmp != null) {
                    weaponCmp.weapons.forEach { weapon ->
                        weaponCmp.ammoMap[weapon] = weapon.maxAmmo
                    }
                }
                val attackCmp = attackCmps.getOrNull(player)
                if (attackCmp != null) {
                    attackCmp.ammo = attackCmp.maxAmmo
                    attackCmp.isReloading = false
                    attackCmp.stateTime = 0f
                }
            }
            PickupType.GRENADE -> {
                val attackCmp = attackCmps.getOrNull(player)
                if (attackCmp != null) {
                    attackCmp.fragAmmo = (attackCmp.fragAmmo + pickup.amount.toInt()).coerceAtMost(10)
                }
            }
            PickupType.COIN -> {
                val playerCmp = playerCmps.getOrNull(player)
                if (playerCmp != null) {
                    playerCmp.score += pickup.amount.toInt()
                }
            }
        }

        // Play feedback sound
        audioService.play(SoundType.CLICK, volumeModifier = 0.9f)
    }

    override fun onDispose() {
        if (healthRegion.texture != null) healthRegion.texture.dispose()
        if (ammoRegion.texture != null) ammoRegion.texture.dispose()
        if (grenadeRegion.texture != null) grenadeRegion.texture.dispose()
        if (coinRegion.texture != null) coinRegion.texture.dispose()
    }

    companion object {
        fun spawn(
            world: World,
            phWorld: PhWorld,
            type: PickupType,
            x: Float,
            y: Float,
            amount: Float = type.defaultAmount,
            lifeTime: Float = -1f
        ): Entity {
            val system = world.system<PickupSystem>()
            val region = system.getRegionFor(type)

            return world.entity {
                val imgCmp = add<ImageComponent> {
                    image = Image(TextureRegionDrawable(region)).apply {
                        setSize(0.5f, 0.5f)
                        setOrigin(Align.center)
                        setPosition(x - 0.25f, y - 0.25f)
                    }
                }

                add<PickupComponent> {
                    this.type = type
                    this.amount = amount
                    this.lifeTime = lifeTime
                }

                physicCmpFromImage(phWorld, imgCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    gravityScale = 1.0f
                    circle(radius = width * 0.45f) {
                        isSensor = false
                        friction = 0.6f
                        restitution = 0.45f // Bouncy drop effect
                    }
                }
            }
        }
    }
}
