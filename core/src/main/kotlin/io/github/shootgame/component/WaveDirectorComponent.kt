package io.github.shootgame.component

import com.badlogic.gdx.math.Vector2

enum class WaveState {
    COUNTDOWN,     // Intermission countdown before the next wave
    SPAWNING,      // Spawning enemies sequentially into the arena
    IN_PROGRESS,   // All enemies spawned; waiting for player to eliminate them
    WAVE_CLEARED   // Wave completed celebration before next countdown
}

class WaveDirectorComponent(
    var wave: Int = 0,
    var state: WaveState = WaveState.COUNTDOWN,
    var stateTimer: Float = 4.0f,
    var enemiesToSpawn: Int = 0,
    var enemiesSpawned: Int = 0,
    var spawnInterval: Float = 1.2f,
    var spawnTimer: Float = 0f,
    val spawnPoints: MutableList<Vector2> = mutableListOf(
        Vector2(6f, 9.5f),
        Vector2(16f, 14.6f),
        Vector2(22f, 8.5f),
        Vector2(10f, 12.0f)
    )
)
