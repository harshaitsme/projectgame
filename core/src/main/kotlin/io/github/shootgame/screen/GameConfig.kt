package io.github.shootgame.screen

import io.github.shootgame.network.GameClient

enum class GameMode(val displayName: String, val maxPlayers: Int, val description: String) {
    OFFLINE("Solo Practice", 1, "Play solo against AI bots on the arena map."),
    DUO("2P Duo Arena", 2, "2-Player live multiplayer PvP arena match."),
    SQUAD("4P Squad Arena", 4, "4-Player live multiplayer Free-For-All / Squad arena.")
}

data class GameConfig(
    var mode: GameMode = GameMode.DUO,
    var playerName: String = "Player",
    var serverHost: String = "161.118.255.172",
    var roomCode: String = "",
    var createRoom: Boolean = false,
    var jwtToken: String = "",
    var sessionToken: String = "",
    var preConnectedClient: GameClient? = null,
    var onExitToMenu: () -> Unit = {}
) {
    companion object {
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateRoomCode(): String {
            return (1..6)
                .map { CODE_CHARS.random() }
                .joinToString("")
        }
    }
}

