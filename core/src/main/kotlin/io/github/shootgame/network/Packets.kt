package io.github.shootgame.network

import com.esotericsoftware.kryo.Kryo

object NetworkConfig {
    const val TCP_PORT = 54555
    const val UDP_PORT = 54777

    fun register(kryo: Kryo) {
        kryo.register(LoginPacket::class.java)
        kryo.register(LoginResponsePacket::class.java)
        kryo.register(PlayerConnectedPacket::class.java)
        kryo.register(PlayerDisconnectedPacket::class.java)
        kryo.register(PlayerStatePacket::class.java)
        kryo.register(PlayerShootPacket::class.java)
    }
}

class LoginPacket {
    var playerName: String = "Player"
}

class LoginResponsePacket {
    var assignedId: Int = -1
    var spawnX: Float = 0f
    var spawnY: Float = 0f
}

class PlayerConnectedPacket {
    var id: Int = -1
    var x: Float = 0f
    var y: Float = 0f
}

class PlayerDisconnectedPacket {
    var id: Int = -1
}

class PlayerStatePacket {
    var id: Int = -1
    var x: Float = 0f
    var y: Float = 0f
    var cos: Float = 0f
    var sin: Float = 0f
    var isAttacking: Boolean = false
    var isReloading: Boolean = false
    var isThrowing: Boolean = false
}

class PlayerShootPacket {
    var id: Int = -1
    var weaponIndex: Int = 0
    var originX: Float = 0f
    var originY: Float = 0f
    var directionX: Float = 1f
    var directionY: Float = 0f
}
