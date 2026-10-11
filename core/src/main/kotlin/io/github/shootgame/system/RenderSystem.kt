package io.github.shootgame.system

import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.maps.tiled.TiledMapTileLayer
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer
import com.badlogic.gdx.maps.tiled.tiles.AnimatedTiledMapTile
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.github.quillraven.fleks.*
import com.github.quillraven.fleks.collection.compareEntity
import io.github.shootgame.Main.Companion.UNIT_SCALE
import io.github.shootgame.component.*
import io.github.shootgame.event.MapChangeEvent
import ktx.assets.disposeSafely
import ktx.graphics.use
import ktx.tiled.forEachLayer

@AllOf([ImageComponent::class])
class RenderSystem(
    private val stage: Stage,
    @Qualifier("uiStage") private val uiStage: Stage,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>,
    private val enemyCmps: ComponentMapper<EnemyComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val netCmps: ComponentMapper<NetworkComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>
) : EventListener, IteratingSystem(
    comparator = compareEntity { e1, e2 -> imageCmps[e1].compareTo(imageCmps[e2]) }
) {

    private val bgdLayers = mutableListOf<TiledMapTileLayer>()
    private val fgdLayers = mutableListOf<TiledMapTileLayer>()

    private val mapRenderer = OrthogonalTiledMapRenderer(null, UNIT_SCALE, stage.batch)
    private val orthoCam = stage.camera as OrthographicCamera

    private val whitePixel: Texture by lazy {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        val tex = Texture(pixmap)
        pixmap.dispose()
        tex
    }

    private val nameTagFont: BitmapFont by lazy {
        BitmapFont().apply {
            data.setScale(1.05f)
            setUseIntegerPositions(false)
        }
    }
    private val glyphLayout = GlyphLayout()
    private val tmpVec3 = Vector3()

    override fun onTick() {
        super.onTick()
        world.system<CameraShakeSystem>().apply(orthoCam)

        with(stage) {
            viewport.apply()

            AnimatedTiledMapTile.updateAnimationBaseTime()
            mapRenderer.setView(orthoCam)

            if (bgdLayers.isNotEmpty()) {
                stage.batch.use(orthoCam.combined) {
                    bgdLayers.forEach { mapRenderer.renderTileLayer(it) }
                }
            }
            act(deltaTime)
            draw()

            // Render world-space overhead health bars above all living units
            stage.batch.use(orthoCam.combined) { batch ->
                world.family(allOf = arrayOf(ImageComponent::class, HealthComponent::class)).forEach { entity ->
                    if (entity in deadCmps) return@forEach
                    val img = imageCmps[entity].image
                    val health = healthCmps[entity]
                    val ratio = (health.currentHealth / health.maxHealth).coerceIn(0f, 1f)

                    val centerX = img.x + img.width * 0.5f
                    val barY = img.y + img.height * 0.62f
                    val barW = 1.25f
                    val barH = 0.14f

                    // Dark border / background
                    batch.setColor(0.05f, 0.07f, 0.1f, 0.85f)
                    batch.draw(whitePixel, centerX - barW * 0.5f - 0.03f, barY - 0.03f, barW + 0.06f, barH + 0.06f)

                    // Missing health bar (dark red)
                    batch.setColor(0.35f, 0.08f, 0.08f, 0.9f)
                    batch.draw(whitePixel, centerX - barW * 0.5f, barY, barW, barH)

                    // Current health fill
                    when {
                        entity in remotePlayerCmps -> {
                            if (ratio > 0.4f) batch.setColor(0.2f, 0.85f, 1f, 0.95f)
                            else batch.setColor(1f, 0.45f, 0.15f, 0.95f)
                        }
                        entity in enemyCmps -> batch.setColor(0.95f, 0.25f, 0.25f, 0.95f)
                        else -> {
                            if (ratio > 0.4f) batch.setColor(0.25f, 0.95f, 0.4f, 0.95f)
                            else batch.setColor(1f, 0.35f, 0.2f, 0.95f)
                        }
                    }
                    batch.draw(whitePixel, centerX - barW * 0.5f, barY, barW * ratio, barH)
                }
                batch.setColor(Color.WHITE)
            }

            if (fgdLayers.isNotEmpty()) {
                stage.batch.use(orthoCam.combined) {
                    fgdLayers.forEach { mapRenderer.renderTileLayer(it) }
                }
            }
        }

        with(uiStage) {
            viewport.apply()
            // Render crisp screen-space player name tags above players
            uiStage.batch.use(uiStage.camera.combined) { batch ->
                world.family(allOf = arrayOf(PlayerComponent::class, ImageComponent::class, HealthComponent::class)).forEach { entity ->
                    val img = imageCmps[entity].image
                    val health = healthCmps[entity]
                    val pCmp = playerCmps[entity]
                    val netCmp = netCmps.getOrNull(entity)
                    val displayName = netCmp?.playerName?.ifEmpty { pCmp.playerName } ?: pCmp.playerName

                    val worldX = img.x + img.width * 0.5f
                    val worldY = img.y + img.height * 0.62f + 0.32f
                    tmpVec3.set(worldX, worldY, 0f)
                    stage.viewport.project(tmpVec3)

                    val weaponCmp = weaponCmps.getOrNull(entity)
                    val ammoSuffix = if (weaponCmp != null && entity !in remotePlayerCmps) {
                        val wType = weaponCmp.currentWeapon
                        val clip = weaponCmp.ammoMap[wType] ?: 0
                        val reserve = weaponCmp.reserveAmmoMap[wType] ?: 0
                        " | Ammo $clip/$reserve"
                    } else {
                        ""
                    }

                    val tagText = if (entity in deadCmps) {
                        "$displayName [DEAD]"
                    } else {
                        "$displayName (${health.currentHealth.toInt()} HP$ammoSuffix)"
                    }

                    glyphLayout.setText(nameTagFont, tagText)
                    val screenX = tmpVec3.x - glyphLayout.width * 0.5f
                    val screenY = tmpVec3.y + glyphLayout.height

                    // Pill background behind name tag
                    batch.setColor(0.05f, 0.08f, 0.14f, 0.72f)
                    batch.draw(
                        whitePixel,
                        screenX - 6f,
                        screenY - glyphLayout.height - 4f,
                        glyphLayout.width + 12f,
                        glyphLayout.height + 8f
                    )
                    batch.setColor(Color.WHITE)

                    nameTagFont.color = when {
                        entity in deadCmps -> Color(0.95f, 0.35f, 0.35f, 1f)
                        entity in remotePlayerCmps -> Color(0.4f, 0.9f, 1f, 1f)
                        else -> Color(0.4f, 1f, 0.55f, 1f)
                    }
                    nameTagFont.draw(batch, tagText, screenX, screenY)
                }
                batch.setColor(Color.WHITE)
            }

            act(deltaTime)
            draw()
        }
    }

    override fun onTickEntity(entity: Entity) {
        val image = imageCmps[entity].image
        image.toFront()

        // Camera follow (local player only)
        if (entity in playerCmps && entity !in remotePlayerCmps) {
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
        whitePixel.disposeSafely()
        nameTagFont.disposeSafely()
    }
}
