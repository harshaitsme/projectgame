package io.github.shootgame.component

import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.event.MapChangeEvent
import ktx.app.gdxError
import ktx.tiled.layer
import ktx.tiled.type
import ktx.tiled.x
import ktx.tiled.y

@AllOf([SpawnComponent::class])
class EntitySpawnSystem : EventListener, IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        TODO("Not yet implemented")
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
                            this.location.set(mapObject.x,mapObject.y)
                        }
                    }
                }

            return true
        }
    }
        return false
    }
}
