package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.maps.tiled.objects.TiledMapTileMapObject
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.Main
import io.github.shootgame.component.*
import io.github.shootgame.component.PhysicComponent.Companion.physicCmpFromImage
import io.github.shootgame.event.MapChangeEvent
import ktx.app.gdxError
import ktx.box2d.box
import ktx.box2d.circle
import ktx.math.vec2
import ktx.tiled.layer
import io.github.shootgame.screen.GameConfig
import io.github.shootgame.screen.GameMode
import ktx.tiled.type
import ktx.tiled.x
import ktx.tiled.y

@AllOf([SpawnComponent::class])
class EntitySpawnSystem(
    private val phWorld: World,
    private val atlas: TextureAtlas,
    private val config: GameConfig,
    private val spawnCmps: ComponentMapper<SpawnComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val ownerCmps: ComponentMapper<OwnerComponent>,
    private val damageCmps: ComponentMapper<DamageComponent>,
    private val bulletCmps: ComponentMapper<BulletComponent>,
    private val fragCmps: ComponentMapper<FragComponent>
) : EventListener, IteratingSystem() {

    private val cachedCfgs = mutableMapOf<String, SpawnCfg>()
    private val cachedSizes = mutableMapOf<Pair<AnimationModel, AnimationType>, Vector2>()

    private val bulletRegion: TextureRegion by lazy {
        val pixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.05f, 0.05f, 0.05f, 1f) // Solid black bullet
        pixmap.fill()
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    private val grenadeRegion: TextureRegion by lazy {
        val pixmap = Pixmap(12, 12, Pixmap.Format.RGBA8888)
        pixmap.setColor(0.2f, 0.4f, 0.2f, 1f) // Dark green grenade
        pixmap.fillCircle(6, 6, 5)
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    override fun onTickEntity(entity: Entity) {
        val spawnCmp = spawnCmps[entity]

        if (spawnCmp.type.startsWith("Pickup")) {
            val pickupType = when (spawnCmp.type) {
                "Pickup_Health", "PickupHealth" -> PickupType.HEALTH
                "Pickup_Ammo", "PickupAmmo" -> PickupType.AMMO
                "Pickup_Grenade", "PickupGrenade" -> PickupType.GRENADE
                "Pickup_Coin", "PickupCoin" -> PickupType.COIN
                else -> PickupType.HEALTH
            }
            PickupSystem.spawn(world, phWorld, pickupType, spawnCmp.location.x, spawnCmp.location.y)
            world.remove(entity)
            return
        }

        val cfg = spawnCfg(spawnCmp.type)
        val originalMoveCmp = if (entity in moveCmps) moveCmps[entity] else null
        val originalOwnerCmp = if (entity in ownerCmps) ownerCmps[entity] else null
        val originalDamageCmp = if (entity in damageCmps) damageCmps[entity] else null
        val originalBulletCmp = if (entity in bulletCmps) bulletCmps[entity] else null
        val originalFragCmp = if (entity in fragCmps) fragCmps[entity] else null

        world.entity { spawnedEntity ->
            val imageCmp = add<ImageComponent> {
                image = Image().apply {
                    if (spawnCmp.type == "Bullet") {
                        drawable = TextureRegionDrawable(bulletRegion)
                        val bWidth = originalBulletCmp?.width ?: 0.09f
                        val bHeight = originalBulletCmp?.height ?: 0.06f
                        setSize(bWidth, bHeight)
                    } else if (spawnCmp.type == "Frag") {
                        drawable = TextureRegionDrawable(grenadeRegion)
                        setSize(0.4f, 0.4f)
                    } else if (spawnCmp.type == "Explosion") {
                        val size = size(cfg.model, cfg.type)
                        setSize(size.x * 2.5f, size.y * 2.5f)
                    } else {
                        val size = size(cfg.model, cfg.type)
                        setSize(size.x, size.y)
                    }

                    if (spawnCmp.type == "Bullet" || spawnCmp.type == "Frag" || spawnCmp.type == "Explosion") {
                        val bWidth = originalBulletCmp?.width ?: 0.09f
                        val bHeight = originalBulletCmp?.height ?: 0.06f
                        val finalWidth = if (spawnCmp.type == "Bullet") bWidth
                                        else if (spawnCmp.type == "Frag") 0.4f
                                        else size(cfg.model, cfg.type).x * 2.5f
                        val finalHeight = if (spawnCmp.type == "Bullet") bHeight
                                         else if (spawnCmp.type == "Frag") 0.4f
                                         else size(cfg.model, cfg.type).y * 2.5f
                        val positionX = spawnCmp.location.x - finalWidth * 0.5f
                        val positionY = if (spawnCmp.type == "Explosion") {
                            spawnCmp.location.y - finalHeight * 0.35f
                        } else {
                            spawnCmp.location.y - finalHeight * 0.5f
                        }

                        setPosition(positionX, positionY)
                    } else if (spawnCmp.type == "RemotePlayer") {
                        // spawnCmp.location is the Box2D body center (x, y) from network packets
                        val size = size(cfg.model, cfg.type)
                        setPosition(spawnCmp.location.x - size.x * 0.5f, spawnCmp.location.y - size.y * 0.5f)
                    } else {
                        // Standard bottom-left for Tiled map objects
                        setPosition(spawnCmp.location.x, spawnCmp.location.y)
                    }
                    setScaling(Scaling.fill)
                }
            }

            // Only add AnimationComponent if it's not a Bullet or Frag (which use static sprites)
            if (spawnCmp.type != "Bullet" && spawnCmp.type != "Frag") {
                add<AnimationComponent> {
                    nextAnimation(cfg.model, cfg.type)
                    if (spawnCmp.type == "Explosion") {
                        playMode = com.badlogic.gdx.graphics.g2d.Animation.PlayMode.NORMAL
                    }
                }
            }

            if (spawnCmp.type == "Player") {
                println("SPAWNING PLAYER at: ${spawnCmp.location}")
                add<PlayerComponent> {
                    playerName = config.playerName
                }
                add<MoveComponent>()
                add<AttackComponent>()
                add<WeaponComponent>()
                add<HealthComponent>()
                add<ShieldComponent>()
                add<PerkComponent>()
                add<StatusEffectComponent>()
                add<OwnerComponent> {
                    owner = spawnedEntity
                }
            }

            if (spawnCmp.type == "RemotePlayer") {
                println("SPAWNING REMOTE PLAYER at: ${spawnCmp.location} (netId=${spawnCmp.networkId})")
                imageCmp.image.color = Color(0.4f, 0.85f, 1f, 1f) // Distinct multiplayer cyan tint
                add<PlayerComponent> {
                    playerName = "Player_${spawnCmp.networkId}"
                }
                add<RemotePlayerComponent>()
                add<MoveComponent>()
                add<AttackComponent>()
                add<WeaponComponent>()
                add<HealthComponent>()
                add<NetworkComponent> {
                    networkId = spawnCmp.networkId
                    isLocal = false
                    playerName = "Player_${spawnCmp.networkId}"
                    targetX = spawnCmp.location.x
                    targetY = spawnCmp.location.y
                    targetCos = 0f
                    targetSin = 1f
                    hasInitializedPos = true
                }
                add<OwnerComponent> {
                    owner = spawnedEntity
                }
            }

            if (spawnCmp.type == "Enemy") {
                println("SPAWNING ENEMY at: ${spawnCmp.location}")
                imageCmp.image.color = Color(1f, 0.35f, 0.35f, 1f) // Crimson red hostile tint
                add<EnemyComponent>()
                add<AiComponent>()
                add<MoveComponent> {
                    speed = 3.5f
                    baseSpeed = 3.5f
                }
                add<AttackComponent> {
                    ammo = 999
                    maxAmmo = 999
                    fireRate = 0.8f
                }
                add<HealthComponent> {
                    maxHealth = 60f
                    currentHealth = 60f
                }
                add<StatusEffectComponent>()
                add<OwnerComponent> {
                    owner = spawnedEntity
                }
            }

            if (spawnCmp.type == "Boss") {
                println("SPAWNING BOSS at: ${spawnCmp.location}")
                imageCmp.image.color = Color(0.9f, 0.2f, 0.8f, 1f) // Menacing violet-crimson cyber tint
                imageCmp.image.setScale(1.5f)
                add<EnemyComponent> {
                    scoreValue = 2500
                }
                add<BossComponent>()
                add<MoveComponent> {
                    speed = 2.0f
                    baseSpeed = 2.0f
                }
                add<AttackComponent> {
                    ammo = 999
                    maxAmmo = 999
                    fireRate = 1.0f
                }
                add<HealthComponent> {
                    maxHealth = 400f
                    currentHealth = 400f
                }
                add<StatusEffectComponent>()
                add<OwnerComponent> {
                    owner = spawnedEntity
                }
            }

            if (spawnCmp.type == "HazardBarrel") {
                imageCmp.image.color = Color(1.0f, 0.42f, 0.05f, 1f) // Tactical neon hazard orange
                imageCmp.image.setScale(0.72f)
                add<HazardComponent> {
                    type = HazardType.EXPLOSIVE_BARREL
                    blastRadius = 4.2f
                    blastDamage = 110f
                }
                add<HealthComponent> {
                    maxHealth = 25f
                    currentHealth = 25f
                }
            }

            if (spawnCmp.type == "ElectricGate") {
                imageCmp.image.color = Color(0.2f, 0.85f, 1f, 0.95f) // Glowing cyan electric trap
                imageCmp.image.setScale(0.85f)
                add<HazardComponent> {
                    type = HazardType.ELECTRIC_GATE
                    cyclePeriod = 2.5f
                    tickDamage = 18f
                    tickInterval = 0.45f
                }
            }

            if (spawnCmp.type == "Bullet") {
                add<BulletComponent> {
                    if (originalBulletCmp != null) {
                        lifeTime = originalBulletCmp.lifeTime
                        width = originalBulletCmp.width
                        height = originalBulletCmp.height
                    }
                }
                add<DamageComponent> {
                    if (originalDamageCmp != null) {
                        amount = originalDamageCmp.amount
                    }
                }
                if (originalOwnerCmp != null) {
                    add<OwnerComponent> {
                        owner = originalOwnerCmp.owner
                    }
                }
                if (originalMoveCmp != null) {
                    add<MoveComponent> {
                        cos = originalMoveCmp.cos
                        sin = originalMoveCmp.sin
                        speed = originalMoveCmp.speed
                    }
                }
            }

            if (spawnCmp.type == "Frag") {
                add<FragComponent> {
                    if (originalFragCmp != null) {
                        fuseTime = originalFragCmp.fuseTime
                        isRemote = originalFragCmp.isRemote
                        remoteOwnerNetId = originalFragCmp.remoteOwnerNetId
                    }
                }
                if (originalOwnerCmp != null) {
                    add<OwnerComponent> {
                        owner = originalOwnerCmp.owner
                    }
                }
                if (originalMoveCmp != null) {
                    add<MoveComponent> {
                        cos = originalMoveCmp.cos
                        sin = originalMoveCmp.sin
                        speed = originalMoveCmp.speed
                    }
                }
            }

            if (spawnCmp.type == "Explosion") {
                add<ExplosionComponent> {
                    range = 3.8f
                    centerX = spawnCmp.location.x
                    centerY = spawnCmp.location.y
                }
                if (originalOwnerCmp != null) {
                    add<OwnerComponent> {
                        owner = originalOwnerCmp.owner
                    }
                }
            }

            // ... (keep the rest) ...

            if (spawnCmp.type == "Bullet") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    gravityScale = 0f
                    circle(radius = width * 0.5f) {
                        isSensor = true
                        friction = 0f
                    }
                }
            } else if (spawnCmp.type == "Frag") {
                val pCmp = physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    gravityScale = 1.5f
                    circle(radius = width * 0.5f) {
                        isSensor = false
                        restitution = 0.5f
                    }
                }
                // Apply initial velocity immediately
                if (originalMoveCmp != null) {
                    pCmp.body.setLinearVelocity(
                        originalMoveCmp.cos * originalMoveCmp.speed,
                        originalMoveCmp.sin * originalMoveCmp.speed
                    )
                }
            }
else if (spawnCmp.type == "Explosion") {
                // No physics for explosion, just visuals
            } else if (spawnCmp.type == "RemotePlayer") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.KinematicBody) { _, width, height ->
                    gravityScale = 0f
                    box(width = width * 0.25f, height = height * 0.85f) {
                        isSensor = true
                        friction = 0f
                    }
                }
            } else if (spawnCmp.type == "Player" || spawnCmp.type == "Enemy") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    // Narrower box (25% width) to fit the character and not the whitespace
                    box(width = width * 0.25f, height = height * 0.85f) {
                        isSensor = false
                        friction = 0f // Prevent sticking to walls
                    }
                }
            } else if (spawnCmp.type == "HazardBarrel") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.StaticBody) { _, width, height ->
                    box(width = width * 0.4f, height = height * 0.65f) {
                        isSensor = false
                        friction = 0.8f
                    }
                }
            } else if (spawnCmp.type == "ElectricGate") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.StaticBody) { _, width, height ->
                    box(width = width * 0.5f, height = height * 0.85f) {
                        isSensor = true
                    }
                }
            } else {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    box(width, height) {
                        isSensor = false
                    }
                }
            }
        }
        world.remove(entity)
    }

    private fun spawnCfg(type: String): SpawnCfg = cachedCfgs.getOrPut(type) {
        when (type) {
            "Player", "RemotePlayer" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.IDLE)
            "Enemy", "Boss" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.IDLE)
            "HazardBarrel", "ElectricGate" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.IDLE)
            "Bullet" -> SpawnCfg(AnimationModel.BULLET, AnimationType.UNDEFINED)
            "Frag" -> SpawnCfg(AnimationModel.BULLET, AnimationType.UNDEFINED)
            "Explosion" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.EXPLOSION)
            else -> gdxError("Type $type has no SpawnCfg setup")
        }
    }

    private fun size(model: AnimationModel, type: AnimationType): Vector2 = cachedSizes.getOrPut(model to type){
        val regions = atlas.findRegions("${model.atlasKey}/${type.atlasKey}")
        if(regions.isEmpty){
            gdxError("There are no regions for the animation $type of model $model")
        }

        val firstFrame = regions.first()
        vec2(firstFrame.originalWidth * Main.UNIT_SCALE, firstFrame.originalHeight * Main.UNIT_SCALE)
    }

    override fun handle(event: Event?): Boolean {
        when (event) {
            is MapChangeEvent -> {
                val entitiesLayer = event.map.layer("entities")
                var enemyCount = 0
                entitiesLayer.objects.forEach { mapObject ->
                    var type = mapObject.type
                    if (type == null && mapObject is TiledMapTileMapObject) {
                        type = mapObject.tile.properties.get("type", String::class.java)
                    }

                    if (type == null) {
                        println("WARNING: Map object $mapObject in 'entities' layer is missing a 'Type' (Class) property. Skipping...")
                        return@forEach
                    }

                    if (type == "Enemy") {
                        if (config.mode != GameMode.OFFLINE) {
                            return@forEach
                        }
                        enemyCount++
                    }

                    world.entity {
                        add <SpawnComponent>{
                            this.type = type
                            this.location.set(mapObject.x* Main.UNIT_SCALE,mapObject.y* Main.UNIT_SCALE)
                        }
                    }
                }

                // Only spawn AI practice bots in OFFLINE solo mode
                if (enemyCount == 0 && config.mode == GameMode.OFFLINE) {
                    println("Spawning default practice enemies on map platforms...")
                    val defaultEnemyPositions = listOf(
                        Vector2(5.5f, 3.34f),
                        Vector2(15.0f, 3.34f),
                        Vector2(22.0f, 4.34f)
                    )
                    defaultEnemyPositions.forEach { pos ->
                        world.entity {
                            add<SpawnComponent> {
                                this.type = "Enemy"
                                this.location.set(pos.x, pos.y)
                            }
                        }
                    }

                    println("Spawning default arena interactive hazards...")
                    val defaultHazards = listOf(
                        "HazardBarrel" to Vector2(10.5f, 3.34f),
                        "HazardBarrel" to Vector2(11.3f, 3.34f), // Chain reaction pair
                        "HazardBarrel" to Vector2(18.5f, 3.34f),
                        "ElectricGate" to Vector2(14.0f, 5.2f)
                    )
                    defaultHazards.forEach { (hType, hPos) ->
                        world.entity {
                            add<SpawnComponent> {
                                this.type = hType
                                this.location.set(hPos.x, hPos.y)
                            }
                        }
                    }
                }

                return true
            }
        }
        return false
    }

    override fun onDispose() {
        if (bulletRegion.texture != null) {
            bulletRegion.texture.dispose()
        }
        if (grenadeRegion.texture != null) {
            grenadeRegion.texture.dispose()
        }
    }
}
