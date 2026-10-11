package io.github.shootgame.screen

enum class GameMode(val displayName: String, val maxPlayers: Int, val description: String) {
    OFFLINE("Offline Mode", 1, "Play solo against AI enemies in offline mode."),
    DUO("Duo Mode", 2, "2-Player Co-op / Duo squad match."),
    SQUAD("Squad Mode", 4, "4-Player Squad battle mode.")
}

data class GameConfig(
    var mode: GameMode = GameMode.OFFLINE,
    var playerName: String = "Player",
    var serverHost: String = "161.118.255.172"
)
