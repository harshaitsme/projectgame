package io.github.shootgame.system

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
import ktx.tiled.type
import ktx.tiled.x
import ktx.tiled.y

@AllOf([SpawnComponent::class])
class EntitySpawnSystem(
    private val phWorld: World,
    private val atlas: TextureAtlas,
    private val spawnCmps: ComponentMapper<SpawnComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val ownerCmps: ComponentMapper<OwnerComponent>
) : EventListener, IteratingSystem() {

    private val cachedCfgs = mutableMapOf<String, SpawnCfg>()
    private val cachedSizes = mutableMapOf<Pair<AnimationModel, AnimationType>, Vector2>()

    private val bulletRegion: TextureRegion by lazy {
        val pixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        pixmap.setColor(1f, 1f, 0f, 1f) // Yellow bullet
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
        val cfg = spawnCfg(spawnCmp.type)
        val originalMoveCmp = if (entity in moveCmps) moveCmps[entity] else null
        val originalOwnerCmp = if (entity in ownerCmps) ownerCmps[entity] else null

        world.entity { spawnedEntity ->
            val imageCmp = add<ImageComponent> {
                image = Image().apply {
                    if (spawnCmp.type == "Bullet") {
                        drawable = TextureRegionDrawable(bulletRegion)
                        setSize(0.2f, 0.2f)
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
                        val finalWidth = if (spawnCmp.type == "Bullet") 0.2f
                                        else if (spawnCmp.type == "Frag") 0.4f
                                        else size(cfg.model, cfg.type).x * 2.5f
                        val finalHeight = if (spawnCmp.type == "Bullet") 0.2f
                                         else if (spawnCmp.type == "Frag") 0.4f
                                         else size(cfg.model, cfg.type).y * 2.5f
                        val positionX = spawnCmp.location.x - finalWidth * 0.5f
                        val positionY = if (spawnCmp.type == "Explosion") {
                            spawnCmp.location.y
                        } else {
                            spawnCmp.location.y - finalHeight * 0.5f
                        }

                        setPosition(positionX, positionY)
                    } else {
                        // Standard bottom-left for player/map objects
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
                add<PlayerComponent>()
                add<MoveComponent>()
                add<AttackComponent>()
                add<HealthComponent>()
                add<OwnerComponent> {
                    owner = spawnedEntity
                }
            }

            if (spawnCmp.type == "Bullet") {
                add<BulletComponent>()
                add<DamageComponent>()
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
                add<FragComponent>()
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
                    range = size(cfg.model, cfg.type).x * 2.5f * 0.5f
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
            } else if (spawnCmp.type == "Player") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    // Narrower box (25% width) to fit the character and not the whitespace
                    box(width = width * 0.25f, height = height * 0.85f) {
                        isSensor = false
                        friction = 0f // Prevent sticking to walls
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
            "Player" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.IDLE)
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
        when(event){
            is MapChangeEvent -> {

               val entitiesLayer = event.map.layer("entities")
                entitiesLayer.objects.forEach { mapObject ->
                    var type = mapObject.type
                    if (type == null && mapObject is TiledMapTileMapObject) {
                        type = mapObject.tile.properties.get("type", String::class.java)
                    }

                    if (type == null) {
                        println("WARNING: Map object $mapObject in 'entities' layer is missing a 'Type' (Class) property. Skipping...")
                        return@forEach
                    }

                    world.entity {
                        add <SpawnComponent>{
                            this.type = type
                            this.location.set(mapObject.x* Main.UNIT_SCALE,mapObject.y* Main.UNIT_SCALE)
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
