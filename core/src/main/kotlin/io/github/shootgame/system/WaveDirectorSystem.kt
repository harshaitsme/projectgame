package io.github.shootgame.system

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Event
import com.badlogic.gdx.scenes.scene2d.EventListener
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IteratingSystem
import io.github.shootgame.Main
import io.github.shootgame.component.*
import io.github.shootgame.event.MapChangeEvent
import ktx.tiled.layer
import ktx.tiled.type
import ktx.tiled.x
import ktx.tiled.y

@AllOf([WaveDirectorComponent::class])
class WaveDirectorSystem(
    private val waveCmps: ComponentMapper<WaveDirectorComponent>,
    private val enemyCmps: ComponentMapper<EnemyComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val perkCmps: ComponentMapper<PerkComponent>
) : EventListener, IteratingSystem() {

    override fun onTickEntity(entity: Entity) {
        val waveCmp = waveCmps[entity]

        when (waveCmp.state) {
            WaveState.COUNTDOWN -> {
                waveCmp.stateTimer -= deltaTime
                if (waveCmp.stateTimer <= 0f) {
                    waveCmp.wave++
                    waveCmp.enemiesToSpawn = calculateEnemiesForWave(waveCmp.wave)
                    waveCmp.enemiesSpawned = 0
                    waveCmp.spawnTimer = 0.5f // Quick first spawn
                    waveCmp.spawnInterval = (1.5f - (waveCmp.wave * 0.1f)).coerceAtLeast(0.6f)
                    waveCmp.state = WaveState.SPAWNING
                }
            }
            WaveState.SPAWNING -> {
                waveCmp.spawnTimer += deltaTime
                if (waveCmp.spawnTimer >= waveCmp.spawnInterval && waveCmp.enemiesSpawned < waveCmp.enemiesToSpawn) {
                    waveCmp.spawnTimer = 0f
                    spawnNextEnemy(waveCmp)
                    waveCmp.enemiesSpawned++
                    if (waveCmp.enemiesSpawned >= waveCmp.enemiesToSpawn) {
                        waveCmp.state = WaveState.IN_PROGRESS
                    }
                }
            }
            WaveState.IN_PROGRESS -> {
                // Check if any non-dead enemies remain
                var aliveEnemies = 0
                world.family(
                    allOf = arrayOf(EnemyComponent::class),
                    noneOf = arrayOf(DeadComponent::class)
                ).forEach { _ ->
                    aliveEnemies++
                }

                if (aliveEnemies == 0 && waveCmp.enemiesSpawned >= waveCmp.enemiesToSpawn) {
                    // Award wave completion bonus & unlock next perk
                    world.family(allOf = arrayOf(PlayerComponent::class)).forEach { player ->
                        playerCmps[player].score += waveCmp.wave * 300
                        val perkCmp = perkCmps.getOrNull(player)
                        if (perkCmp != null) {
                            val available = PerkType.values().firstOrNull { !perkCmp.hasPerk(it) }
                            if (available != null) {
                                perkCmp.addPerk(available)
                            }
                        }
                    }
                    waveCmp.state = WaveState.WAVE_CLEARED
                    waveCmp.stateTimer = 2.0f
                }
            }
            WaveState.WAVE_CLEARED -> {
                waveCmp.stateTimer -= deltaTime
                if (waveCmp.stateTimer <= 0f) {
                    waveCmp.state = WaveState.COUNTDOWN
                    waveCmp.stateTimer = 4.0f
                }
            }
        }
    }

    private fun calculateEnemiesForWave(wave: Int): Int {
        return 3 + (wave - 1) * 2
    }

    private fun spawnNextEnemy(waveCmp: WaveDirectorComponent) {
        if (waveCmp.spawnPoints.isEmpty()) return
        val pos = waveCmp.spawnPoints[waveCmp.enemiesSpawned % waveCmp.spawnPoints.size]

        // Add slight random offset so they don't stack exactly on top of each other
        val spawnX = pos.x + MathUtils.random(-0.5f, 0.5f)
        val spawnY = pos.y

        world.entity {
            add<SpawnComponent> {
                this.type = "Enemy"
                this.location.set(spawnX, spawnY)
            }
        }
    }

    override fun handle(event: Event?): Boolean {
        if (event is MapChangeEvent) {
            val entitiesLayer = event.map.layer("entities")
            val points = mutableListOf<Vector2>()

            entitiesLayer.objects.forEach { obj ->
                val type = obj.type ?: obj.properties.get("type", String::class.java)
                if (type == "Enemy") {
                    points.add(Vector2(obj.x * Main.UNIT_SCALE, obj.y * Main.UNIT_SCALE))
                }
            }

            // Create or update WaveDirector singleton entity
            var existingDirector: Entity? = null
            world.family(allOf = arrayOf(WaveDirectorComponent::class)).forEach {
                existingDirector = it
            }
            if (existingDirector != null) {
                val cmp = waveCmps[existingDirector!!]
                cmp.wave = 0
                cmp.state = WaveState.COUNTDOWN
                cmp.stateTimer = 3.0f
                if (points.isNotEmpty()) {
                    cmp.spawnPoints.clear()
                    cmp.spawnPoints.addAll(points)
                }
            } else {
                world.entity {
                    add<WaveDirectorComponent> {
                        if (points.isNotEmpty()) {
                            spawnPoints.clear()
                            spawnPoints.addAll(points)
                        }
                    }
                }
            }
            return true
        }
        return false
    }
}
