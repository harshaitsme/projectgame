package io.github.shootgame.system

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.esotericsoftware.kryonet.Client
import com.esotericsoftware.kryonet.Connection
import com.esotericsoftware.kryonet.Listener
import com.github.quillraven.fleks.AllOf
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.component.*
import io.github.shootgame.network.*
import java.util.concurrent.ConcurrentLinkedQueue

class NetworkSystem(
    private val phWorld: PhWorld,
    private val netCmps: ComponentMapper<NetworkComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>
) : IntervalSystem() {

    private val client = Client()
    private val packetQueue = ConcurrentLinkedQueue<Any>()
    private val remoteEntities = mutableMapOf<Int, Entity>()

    var myNetworkId: Int = -1
        private set

    private var syncTimer = 0f
    private val syncRate = 0.033f // ~30 updates/sec to server

    init {
        NetworkConfig.register(client.kryo)
        client.addListener(object : Listener() {
            override fun received(connection: Connection, obj: Any?) {
                if (obj != null) {
                    packetQueue.add(obj)
                }
            }

            override fun disconnected(connection: Connection) {
                println("Disconnected from game server")
            }
        })
    }

    fun connect(host: String = "localhost", timeoutMs: Int = 3000) {
        Thread {
            try {
                client.start()
                client.connect(timeoutMs, host, NetworkConfig.TCP_PORT, NetworkConfig.UDP_PORT)
                val login = LoginPacket().apply { playerName = "Player_${System.currentTimeMillis() % 1000}" }
                client.sendTCP(login)
                println("Connected to game server at $host")
            } catch (e: Exception) {
                println("Multiplayer server not available at $host: ${e.message}. Running in offline mode.")
            }
        }.start()
    }

    override fun onTick() {
        // 1. Process received network packets on the main render thread
        while (packetQueue.isNotEmpty()) {
            when (val packet = packetQueue.poll()) {
                is LoginResponsePacket -> {
                    myNetworkId = packet.assignedId
                    println("Assigned player network ID: $myNetworkId")

                    // Tag local player entity with its assigned network ID
                    world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                        if (localPlayer !in netCmps) {
                            world.configureEntity(localPlayer) {
                                netCmps.add(localPlayer) {
                                    networkId = myNetworkId
                                    isLocal = true
                                }
                            }
                        }
                    }
                }

                is PlayerConnectedPacket -> {
                    if (packet.id != myNetworkId && !remoteEntities.containsKey(packet.id)) {
                        spawnRemotePlayer(packet.id, packet.x, packet.y)
                    }
                }

                is PlayerDisconnectedPacket -> {
                    val remote = remoteEntities.remove(packet.id)
                    if (remote != null) {
                        world.remove(remote)
                        println("Removed remote player: ${packet.id}")
                    }
                }

                is PlayerStatePacket -> {
                    if (packet.id != myNetworkId) {
                        val remote = remoteEntities[packet.id]
                        if (remote != null && remote in netCmps) {
                            val netCmp = netCmps[remote]
                            netCmp.targetX = packet.x
                            netCmp.targetY = packet.y
                            netCmp.targetCos = packet.cos
                            netCmp.targetSin = packet.sin

                            val attack = attackCmps.getOrNull(remote)
                            if (attack != null) {
                                attack.isAttacking = packet.isAttacking
                                attack.isReloading = packet.isReloading
                                attack.isThrowing = packet.isThrowing
                            }
                        } else if (!remoteEntities.containsKey(packet.id)) {
                            spawnRemotePlayer(packet.id, packet.x, packet.y)
                        }
                    }
                }
            }
        }

        // 2. Interpolate position and sync movement for remote player entities
        remoteEntities.values.forEach { remote ->
            if (remote in netCmps && remote in moveCmps && remote in physicCmps) {
                val net = netCmps[remote]
                val move = moveCmps[remote]
                val physic = physicCmps[remote]

                move.cos = net.targetCos
                move.sin = net.targetSin
                if (net.targetCos != 0f) {
                    move.lastCos = net.targetCos
                }

                // Smooth position correction toward target position
                val body = physic.body
                val dx = net.targetX - body.position.x
                val dy = net.targetY - body.position.y
                if (Math.abs(dx) > 0.05f || Math.abs(dy) > 0.1f) {
                    body.setTransform(
                        body.position.x + dx * 0.25f,
                        body.position.y + dy * 0.25f,
                        body.angle
                    )
                }
            }
        }

        // 3. Send local player state to server periodically
        if (client.isConnected && myNetworkId != -1) {
            syncTimer += deltaTime
            if (syncTimer >= syncRate) {
                syncTimer = 0f
                world.family(allOf = arrayOf(PlayerComponent::class, MoveComponent::class, PhysicComponent::class)).forEach { localPlayer ->
                    val physic = physicCmps[localPlayer]
                    val move = moveCmps[localPlayer]
                    val attack = attackCmps.getOrNull(localPlayer)

                    val packet = PlayerStatePacket().apply {
                        this.id = myNetworkId
                        this.x = physic.body.position.x
                        this.y = physic.body.position.y
                        this.cos = move.cos
                        this.sin = move.sin
                        this.isAttacking = attack?.isAttacking ?: false
                        this.isReloading = attack?.isReloading ?: false
                        this.isThrowing = attack?.isThrowing ?: false
                    }
                    client.sendUDP(packet)
                }
            }
        }
    }

    private fun spawnRemotePlayer(netId: Int, startX: Float, startY: Float) {
        val spawnEntity = world.entity {
            add<SpawnComponent> {
                type = "RemotePlayer"
                location.set(startX, startY)
            }
        }
        remoteEntities[netId] = spawnEntity
        println("Remote player entity requested for ID $netId at ($startX, $startY)")
    }

    override fun onDispose() {
        if (client.isConnected) {
            client.stop()
        }
    }
}
