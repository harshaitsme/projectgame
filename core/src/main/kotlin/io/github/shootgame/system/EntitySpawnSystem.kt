package io.github.shootgame.system

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
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
    private val moveCmps: ComponentMapper<MoveComponent>
) : EventListener, IteratingSystem() {

    private val cachedCfgs = mutableMapOf<String, SpawnCfg>()
    private val cachedSizes = mutableMapOf<AnimationModel, Vector2>()

    private val bulletRegion: TextureRegion by lazy {
        val pixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        pixmap.setColor(1f, 1f, 0f, 1f) // Yellow bullet
        pixmap.fill()
        val texture = Texture(pixmap)
        pixmap.dispose()
        TextureRegion(texture)
    }

    override fun onTickEntity(entity: Entity) {
        val spawnCmp = spawnCmps[entity]
        val cfg = spawnCfg(spawnCmp.type)
        val originalMoveCmp = if (entity in moveCmps) moveCmps[entity] else null

        world.entity {
            val imageCmp = add<ImageComponent> {
                image = Image().apply {
                    if (spawnCmp.type == "Bullet") {
                        drawable = TextureRegionDrawable(bulletRegion)
                        setSize(0.2f, 0.2f) // Small bullet size in world units
                    } else {
                        val size = size(cfg.model, cfg.type)
                        setSize(size.x, size.y)
                    }
                    setPosition(spawnCmp.location.x, spawnCmp.location.y)
                    setScaling(Scaling.fill)
                }
            }

            if (spawnCmp.type != "Bullet") {
                add<AnimationComponent> {
                    nextAnimation(cfg.model, cfg.type)
                }
            }

            if (spawnCmp.type == "Player") {
                add<PlayerComponent>()
                add<MoveComponent>()
                add<AttackComponent>()
            }

            if (spawnCmp.type == "Bullet") {
                add<BulletComponent>()
                if (originalMoveCmp != null) {
                    add<MoveComponent> {
                        cos = originalMoveCmp.cos
                        sin = originalMoveCmp.sin
                        speed = originalMoveCmp.speed
                    }
                }
            }

            if (spawnCmp.type == "Bullet") {
                physicCmpFromImage(phWorld, imageCmp.image, BodyDef.BodyType.DynamicBody) { _, width, height ->
                    circle(radius = width * 0.5f) {
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
            "Player" -> SpawnCfg(AnimationModel.PLAYER, AnimationType.IDLE)
            "Bullet" -> SpawnCfg(AnimationModel.BULLET, AnimationType.UNDEFINED)
            else -> gdxError("Type $type has no SpawnCfg setup")
        }
    }

    private fun size(model: AnimationModel, type: AnimationType): Vector2 = cachedSizes.getOrPut(model){
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

                    val type = mapObject.type ?: gdxError("Map object : $mapObject missing type property")
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
    }
}
