package io.github.shootgame.system

import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.component.*
import io.github.shootgame.network.GameClient
import io.github.shootgame.network.NetworkConfig
import io.github.shootgame.proto.*
import kotlin.math.abs

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

    private var client: GameClient? = null
    private val remoteEntities = mutableMapOf<Int, Entity>()

    var myNetworkId: Int = -1
        private set

    val isConnected: Boolean
        get() = client?.isConnected == true

    private var syncTimer = 0f
    private val syncRate = 0.033f // ~30 updates/sec to server

    private val healthSystem: HealthSystem by lazy { world.system<HealthSystem>() }

    val activePlayerCount: Int
        get() = remoteEntities.size + (if (myNetworkId != -1) 1 else 0)

    fun connect(host: String = NetworkConfig.serverHost, playerName: String = "Player", timeoutMs: Int = 4000) {
        val gameClient = GameClient(host, NetworkConfig.TCP_PORT, NetworkConfig.UDP_PORT)
        client = gameClient
        gameClient.connect(playerName, timeoutMs)
    }

    fun sendShoot(originX: Float, originY: Float, dirX: Float, dirY: Float, weaponIndex: Int = 0) {
        client?.sendShoot(originX, originY, dirX, dirY, weaponIndex)
    }

    override fun onTick() {
        val currentClient = client ?: return

        // 1. Process received network packets on the main render thread
        while (currentClient.packetQueue.isNotEmpty()) {
            when (val packet = currentClient.packetQueue.poll()) {
                is LoginResponsePacket -> {
                    myNetworkId = packet.assignedId
                    println("Assigned player network ID: $myNetworkId at spawn (${packet.spawnX}, ${packet.spawnY})")

                    // Tag local player entity with its assigned network ID
                    world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                        if (localPlayer !in remotePlayerCmps && localPlayer !in netCmps) {
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
                    val id = packet.id
                    if (id != myNetworkId && !remoteEntities.containsKey(id)) {
                        spawnRemotePlayer(id, packet.x, packet.y)
                    }
                }

                is PlayerDisconnectedPacket -> {
                    val id = packet.id
                    val remote = remoteEntities.remove(id)
                    if (remote != null) {
                        world.remove(remote)
                        println("Removed remote player: $id")
                    }
                }

                is PlayerStatePacket -> {
                    val id = packet.id
                    if (id != myNetworkId) {
                        val remote = remoteEntities[id]
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
                        } else if (!remoteEntities.containsKey(id)) {
                            spawnRemotePlayer(id, packet.x, packet.y)
                        }
                    }
                }

                is PlayerHitPacket -> {
                    println("Server authoritative HIT: from=${packet.fromId} to=${packet.toId} dmg=${packet.damage}")
                    if (packet.toId == myNetworkId) {
                        // Local player took damage
                        world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                            if (localPlayer !in remotePlayerCmps) {
                                healthSystem.damage(localPlayer, packet.damage)
                            }
                        }
                    } else {
                        // Remote player took damage
                        val targetRemote = remoteEntities[packet.toId]
                        if (targetRemote != null) {
                            healthSystem.damage(targetRemote, packet.damage)
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
                if (abs(dx) > 0.05f || abs(dy) > 0.1f) {
                    body.setTransform(
                        body.position.x + dx * 0.25f,
                        body.position.y + dy * 0.25f,
                        body.angle
                    )
                }
            }
        }

        // 3. Send local player state to server periodically
        if (currentClient.isConnected && myNetworkId != -1) {
            syncTimer += deltaTime
            if (syncTimer >= syncRate) {
                syncTimer = 0f
                world.family(allOf = arrayOf(PlayerComponent::class, MoveComponent::class, PhysicComponent::class)).forEach { localPlayer ->
                    if (localPlayer !in remotePlayerCmps) {
                        val physic = physicCmps[localPlayer]
                        val move = moveCmps[localPlayer]
                        val attack = attackCmps.getOrNull(localPlayer)

                        currentClient.sendState(
                            x = physic.body.position.x,
                            y = physic.body.position.y,
                            cos = move.cos,
                            sin = move.sin,
                            isAttacking = attack?.isAttacking ?: false,
                            isReloading = attack?.isReloading ?: false,
                            isThrowing = attack?.isThrowing ?: false
                        )
                    }
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
        println("Remote player entity spawned for network ID $netId at ($startX, $startY)")
    }

    override fun onDispose() {
        client?.close()
        client = null
    }
}
