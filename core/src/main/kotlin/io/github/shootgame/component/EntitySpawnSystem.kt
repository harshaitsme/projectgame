package io.github.shootgame.component

import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.Scaling
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.Main.Companion.UNIT_SCALE
import io.github.shootgame.event.MapChangeEvent
import ktx.app.gdxError
import ktx.tiled.layer
import ktx.tiled.type
import ktx.tiled.x
import ktx.tiled.y

@AllOf([SpawnComponent::class])
class EntitySpawnSystem(

    private val spawnCmps: ComponentMapper<SpawnComponent>,
) : EventListener, IteratingSystem() {

    private val cachedCfgs = mutableMapOf<String, SpawnCfg>()

    override fun onTickEntity(entity: Entity) {
        val spawnCmp = spawnCmps[entity]
        val cfg = spawnCfg(spawnCmp.type)

        world.entity {
            add<ImageComponent> {
                image = Image().apply {
                    setPosition(spawnCmp.location.x, spawnCmp.location.y)
                    setSize(4f,4f)
                    setScaling(Scaling.fill)
                }
            }
            add<AnimationComponent> {
                nextAnimation(cfg.model, AnimationType.IDLE)
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

    override fun handle(event: Event?): Boolean {
        when(event){
            is MapChangeEvent -> {

               val entitiesLayer = event.map.layer("entities")
                entitiesLayer.objects.forEach { mapObject ->

                    val type = mapObject.type ?: gdxError("Map object : $mapObject missing type property")
                    world.entity {
                        add <SpawnComponent>{
                            this.type = type
                            this.location.set(mapObject.x*UNIT_SCALE,mapObject.y*UNIT_SCALE)
                        }
                    }
                }

            return true
        }
    }
        return false
    }
}
