package io.github.shootgame.system

import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.BodyDef
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.Main
import io.github.shootgame.component.AnimationComponent
import io.github.shootgame.component.AnimationModel
import io.github.shootgame.component.AnimationType
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PhysicComponent.Companion.physicCmpFromImage
import io.github.shootgame.component.SpawnCfg
import io.github.shootgame.component.SpawnComponent
import io.github.shootgame.event.MapChangeEvent
import ktx.app.gdxError
import ktx.box2d.box
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
) : EventListener, IteratingSystem() {

    private val cachedCfgs = mutableMapOf<String, SpawnCfg>()
    private val cachedSizes = mutableMapOf<AnimationModel, Vector2>()

    override fun onTickEntity(entity: Entity) {
        val spawnCmp = spawnCmps[entity]
        val cfg = spawnCfg(spawnCmp.type)

        world.entity {
          val imageCmp =   add<ImageComponent> {
                image = Image().apply {
                    val size = size(cfg.model)
                    setPosition(spawnCmp.location.x, spawnCmp.location.y)
                    setSize(size.x, size.y)
                    setScaling(Scaling.fill)
                }
            }
            add<AnimationComponent> {
                nextAnimation(cfg.model, AnimationType.IDLE)
            }

            physicCmpFromImage(phWorld,imageCmp.image, BodyDef.BodyType.DynamicBody){ phWorld, width, height ->
                box(width,height){
                    isSensor = false
//                    userData = "INTERACTION_SENSOR"
//                    friction =
                }

            }
        }
        world.remove(entity)
    }

    private fun spawnCfg(type: String): SpawnCfg = cachedCfgs.getOrPut(type) {
        when (type) {
            "Player" -> SpawnCfg(AnimationModel.PLAYER)
            else -> gdxError("Type $type has no SpawnCfg setup")
        }
    }

    private fun size(model: AnimationModel): Vector2 = cachedSizes.getOrPut(model){
        val regions = atlas.findRegions("${model.atlasKey}/${AnimationType.IDLE.atlasKey}")
        if(regions.isEmpty){
            gdxError("There are no regions for the idle animation of model $model")
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
}
