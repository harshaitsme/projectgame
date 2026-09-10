package io.github.shootgame.system

import com.artemis.annotations.All
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.github.quillraven.fleks.*
import com.github.quillraven.fleks.collection.compareEntity
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.event.MapChangeEvent
import ktx.tiled.forEachLayer


// pov : this kt class responsible for rendering
@AllOf([ImageComponent::class])
class RenderSystem(
    private val stage: Stage,
    private val imageCmps: ComponentMapper<ImageComponent>
) : EventListener, IteratingSystem(

    comparator = compareEntity { e1, e2 -> imageCmps[e1].compareTo(imageCmps[e2]) }
){

    private val bgdLayers = mutableListOf<TiledMapTileLayer>()
    private val fgdLayers = mutableListOf<TiledMapTileLayer>()

    override fun onTick() {
        super.onTick()

        with(stage){
            viewport.apply()
            act(deltaTime)
            draw()
        }
    }

    override fun onTickEntity(entity: Entity) {
        imageCmps[entity].image.toFront()
    }

    override fun onDispose() {
        super.onDispose()
//        TODO:implement dispose sys render convenient way
    }

    override fun handle(event: Event?): Boolean {

        when(event){
             is MapChangeEvent -> {

                 bgdLayers.clear()
                 fgdLayers.clear()

                 event.map.forEachLayer<TiledMapTileLayer> { layer ->

                     if(layer.name.startsWith("fg")){

                            fgdLayers.add(layer)
                     }else{
                            bgdLayers.add(layer)
                     }
                 }
                return true
            }
        }
        return false
    }


}
