package io.github.shootgame.system

import com.artemis.annotations.All
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer
import com.badlogic.gdx.maps.tiled.tiles.AnimatedTiledMapTile
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.github.quillraven.fleks.*
import com.github.quillraven.fleks.collection.compareEntity
import io.github.shootgame.Main.Companion.UNIT_SCALE
import io.github.shootgame.component.ImageComponent
import io.github.shootgame.component.PlayerComponent
import io.github.shootgame.event.MapChangeEvent
import ktx.assets.disposeSafely
import ktx.graphics.use
import ktx.tiled.forEachLayer


// pov : this kt class responsible for rendering
@AllOf([ImageComponent::class])
class RenderSystem(
    private val stage: Stage,
    @Qualifier("uiStage") private val uiStage: Stage,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>
) : EventListener, IteratingSystem(

    comparator = compareEntity { e1, e2 -> imageCmps[e1].compareTo(imageCmps[e2]) }
){



    private val bgdLayers = mutableListOf<TiledMapTileLayer>()
    private val fgdLayers = mutableListOf<TiledMapTileLayer>()

    private val mapRenderer = OrthogonalTiledMapRenderer(null,UNIT_SCALE,stage.batch)
    private val orthoCam = stage.camera as OrthographicCamera

    override fun onTick() {
        super.onTick()
        world.system<CameraShakeSystem>().apply(orthoCam)

        with(stage){
            viewport.apply()


            // animation tiled handling part Note: if we create some animated tile stuff that the reason for call this fun
            AnimatedTiledMapTile.updateAnimationBaseTime()
            mapRenderer.setView(orthoCam)


            if(bgdLayers.isNotEmpty()){
                stage.batch.use(orthoCam.combined){

                    bgdLayers.forEach { mapRenderer.renderTileLayer(it) }
                }
            }
            act(deltaTime)
            draw()

            if(fgdLayers.isNotEmpty()){
                stage.batch.use(orthoCam.combined){
                    fgdLayers.forEach { mapRenderer.renderTileLayer(it) }
                }
            }
        }

        with(uiStage) {
            viewport.apply()
            act(deltaTime)
            draw()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val image = imageCmps[entity].image
        image.toFront()

        // Camera follow
        if (entity in playerCmps) {
            orthoCam.position.set(
                image.x + image.width * 0.5f,
                image.y + image.height * 0.5f,
                0f
            )
        }
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

    override fun onDispose() {
        mapRenderer.disposeSafely()
    }

}
