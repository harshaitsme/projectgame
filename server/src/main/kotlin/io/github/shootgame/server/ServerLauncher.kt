@file:JvmName("ServerLauncher")

package io.github.shootgame.server

import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.Listener
import com.esotericsoftware.kryonet.Server
import io.github.shootgame.network.*
import java.util.concurrent.ConcurrentHashMap

data class ConnectedClient(
    val id: Int,
    var x: Float = 4f,
    var y: Float = 4f,
    var cos: Float = 1f,
    var sin: Float = 0f
)

/** Launches the server application. */
fun main(args: Array<String> = emptyArray()) {
    val tcpPort = args.getOrNull(0)?.toIntOrNull() ?: NetworkConfig.TCP_PORT
    val udpPort = args.getOrNull(1)?.toIntOrNull() ?: NetworkConfig.UDP_PORT

    println("🎮 Starting ShootGame Dedicated Server on TCP $tcpPort, UDP $udpPort...")

    val server = Server()
    NetworkConfig.register(server.kryo)

    val clients = ConcurrentHashMap<Int, ConnectedClient>()

    server.addListener(object : Listener() {
        override fun connected(connection: Connection) {
            println("Client connected: ${connection.id} (${connection.remoteAddressTCP})")
        }

        override fun disconnected(connection: Connection) {
            val id = connection.id
            clients.remove(id)
            println("Client disconnected: $id")

            val disconnectPacket = PlayerDisconnectedPacket().apply { this.id = id }
            server.sendToAllTCP(disconnectPacket)
        }

        override fun received(connection: Connection, obj: Any?) {
            when (obj) {
                is LoginPacket -> {
                    val id = connection.id
                    val spawnX = 3f + (id % 5) * 1.5f
                    val spawnY = 4f
                    val client = ConnectedClient(id = id, x = spawnX, y = spawnY)
                    clients[id] = client

                    // 1. Send accept packet with assigned ID to connecting client
                    val response = LoginResponsePacket().apply {
                        assignedId = id
                        this.spawnX = spawnX
                        this.spawnY = spawnY
                    }
                    connection.sendTCP(response)

                    // 2. Notify other players about this new player
                    val newPlayer = PlayerConnectedPacket().apply {
                        this.id = id
                        this.x = spawnX
                        this.y = spawnY
                    }
                    server.sendToAllExceptTCP(id, newPlayer)

                    // 3. Send existing players to the new player
                    clients.forEach { (existingId, existingClient) ->
                        if (existingId != id) {
                            val existingPlayer = PlayerConnectedPacket().apply {
                                this.id = existingId
                                this.x = existingClient.x
                                this.y = existingClient.y
                            }
                            connection.sendTCP(existingPlayer)
                        }
                    }
                }

                is PlayerStatePacket -> {
                    obj.id = connection.id
                    val client = clients[connection.id]
                    if (client != null) {
                        client.x = obj.x
                        client.y = obj.y
                        client.cos = obj.cos
                        client.sin = obj.sin
                    }
                    // Relay position & action state via UDP for low latency
                    server.sendToAllExceptUDP(connection.id, obj)
                }

                is PlayerShootPacket -> {
                    obj.id = connection.id
                    // Broadcast shot to all other players via TCP
                    server.sendToAllExceptTCP(connection.id, obj)
                }
            }
        }
    })

    server.bind(tcpPort, udpPort)
    server.start()

    println("ShootGame Server running successfully.")
}
