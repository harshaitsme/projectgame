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
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val playerCmps: ComponentMapper<PlayerComponent>,
    private val remotePlayerCmps: ComponentMapper<RemotePlayerComponent>
) : IntervalSystem() {

    private var client: GameClient? = null
    private val remoteEntities = mutableMapOf<Int, Entity>()
    private val pendingSpawnNetIds = mutableSetOf<Int>()

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

        // 1. Sync remoteEntities map with actual live spawned entities
        world.family(allOf = arrayOf(RemotePlayerComponent::class, NetworkComponent::class)).forEach { remoteEntity ->
            val net = netCmps[remoteEntity]
            if (net.networkId != -1) {
                remoteEntities[net.networkId] = remoteEntity
                pendingSpawnNetIds.remove(net.networkId)
            }
        }

        // 2. Process received network packets on the main render thread
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
                    if (id != myNetworkId && !remoteEntities.containsKey(id) && !pendingSpawnNetIds.contains(id)) {
                        spawnRemotePlayer(id, packet.x, packet.y)
                    }
                }

                is PlayerDisconnectedPacket -> {
                    val id = packet.id
                    val remote = remoteEntities.remove(id)
                    pendingSpawnNetIds.remove(id)
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

                            val move = moveCmps.getOrNull(remote)
                            if (move != null) {
                                move.cos = packet.cos
                                move.sin = packet.sin
                                if (packet.cos != 0f) {
                                    move.lastCos = packet.cos
                                }
                            }

                            val attack = attackCmps.getOrNull(remote)
                            if (attack != null) {
                                attack.isAttacking = packet.isAttacking
                                attack.isReloading = packet.isReloading
                                attack.isThrowing = packet.isThrowing
                            }

                            // Sync animation state
                            val anim = animationCmps.getOrNull(remote)
                            if (anim != null) {
                                when {
                                    packet.isReloading -> anim.nextAnimation(anim.model, AnimationType.RECHARGE)
                                    packet.isThrowing -> anim.nextAnimation(anim.model, AnimationType.GRENADE)
                                    packet.isAttacking -> anim.nextAnimation(anim.model, AnimationType.SHOT1)
                                    abs(packet.cos) > 0.01f || abs(packet.sin) > 0.01f -> anim.nextAnimation(anim.model, AnimationType.RUN)
                                    else -> anim.nextAnimation(anim.model, AnimationType.IDLE)
                                }
                            }

                            // Flip sprite facing direction
                            val img = imageCmps.getOrNull(remote)
                            if (img != null && packet.cos != 0f) {
                                img.image.originX = img.image.width * 0.5f
                                img.image.scaleX = if (packet.cos < 0f) -1f else 1f
                            }
                        } else if (!remoteEntities.containsKey(id) && !pendingSpawnNetIds.contains(id)) {
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

        // 3. Interpolate position smoothly for remote player entities
        remoteEntities.values.forEach { remote ->
            if (remote in netCmps && remote in physicCmps) {
                val net = netCmps[remote]
                val physic = physicCmps[remote]
                val body = physic.body

                val dx = net.targetX - body.position.x
                val dy = net.targetY - body.position.y

                // If teleport / large delta (e.g. spawn), snap instantly
                if (abs(dx) > 3f || abs(dy) > 3f) {
                    body.setTransform(net.targetX, net.targetY, body.angle)
                    body.setLinearVelocity(0f, 0f)
                } else if (abs(dx) > 0.02f || abs(dy) > 0.02f) {
                    val lerpFactor = 0.35f
                    body.setTransform(
                        body.position.x + dx * lerpFactor,
                        body.position.y + dy * lerpFactor,
                        body.angle
                    )
                }
            }
        }

        // 4. Send local player state to server periodically
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
        world.entity {
            add<SpawnComponent> {
                type = "RemotePlayer"
                location.set(startX, startY)
                networkId = netId
            }
        }
        pendingSpawnNetIds.add(netId)
        println("Remote player spawn requested for network ID $netId at ($startX, $startY)")
    }

    override fun onDispose() {
        client?.close()
        client = null
        remoteEntities.clear()
        pendingSpawnNetIds.clear()
    }
}
