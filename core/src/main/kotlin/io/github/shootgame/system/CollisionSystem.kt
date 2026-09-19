package io.github.shootgame.system

import com.badlogic.gdx.maps.objects.RectangleMapObject
import com.badlogic.gdx.maps.tiled.objects.TiledMapTileMapObject
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer
import com.badlogic.gdx.physics.box2d.World
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.Main.Companion.UNIT_SCALE
import io.github.shootgame.event.MapChangeEvent
import ktx.box2d.body
import ktx.box2d.box
import ktx.tiled.*

class CollisionSystem(
    private val phWorld: World
) : EventListener, IntervalSystem() {

    override fun onTick() = Unit

    override fun handle(event: Event?): Boolean {
        if (event is MapChangeEvent) {
            val layer = try {
                event.map.layer("physics")
            } catch (e: Exception) {
                println("ERROR: 'physics' layer not found in Tiled map! Ground will not be created.")
                return true
            }

            var groundCount = 0

            if (layer is TiledMapTileLayer) {
                // Handle as Tile Layer (painting tiles)
                val tileSize = layer.tileWidth.toFloat() // assuming square tiles
                for (x in 0 until layer.width) {
                    for (y in 0 until layer.height) {
                        val cell = layer.getCell(x, y)
                        if (cell?.tile != null) {
                            phWorld.body {
                                position.set(
                                    (x * tileSize + tileSize * 0.5f) * UNIT_SCALE,
                                    (y * tileSize + tileSize * 0.5f) * UNIT_SCALE
                                )
                                box(width = tileSize * UNIT_SCALE, height = tileSize * UNIT_SCALE)
                            }
                            groundCount++
                        }
                    }
                }
            } else {
                // Handle as Object Layer (rectangles or tile objects)
                layer.objects.forEach { mapObject ->
                    if (mapObject is RectangleMapObject) {
                        val rect = mapObject.rectangle
                        phWorld.body {
                            position.set(
                                (rect.x + rect.width * 0.5f) * UNIT_SCALE,
                                (rect.y + rect.height * 0.5f) * UNIT_SCALE
                            )
                            box(width = rect.width * UNIT_SCALE, height = rect.height * UNIT_SCALE)
                        }
                        groundCount++
                    } else if (mapObject is TiledMapTileMapObject) {
                        val x = mapObject.x
                        val y = mapObject.y
                        val width = mapObject.tile.textureRegion.regionWidth.toFloat()
                        val height = mapObject.tile.textureRegion.regionHeight.toFloat()
                        phWorld.body {
                            position.set(
                                (x + width * 0.5f) * UNIT_SCALE,
                                (y + height * 0.5f) * UNIT_SCALE
                            )
                            box(width = width * UNIT_SCALE, height = height * UNIT_SCALE)
                        }
                        groundCount++
                    }
                }
            }
            println("SUCCESS: Created $groundCount ground collision bodies from 'physics' layer.")
            return true
        }
        return false
    }
}
