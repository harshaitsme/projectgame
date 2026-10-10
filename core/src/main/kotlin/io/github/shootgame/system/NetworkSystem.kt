package io.github.shootgame.system

import com.badlogic.gdx.physics.box2d.World as PhWorld
import com.github.quillraven.fleks.ComponentMapper
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.IntervalSystem
import io.github.shootgame.component.*
import io.github.shootgame.network.GameClient
import io.github.shootgame.network.NetworkConfig
import io.github.shootgame.proto.*
import io.github.shootgame.screen.GameConfig
import kotlin.math.abs

data class PlayerScoreEntry(
    val id: Int,
    val name: String,
    val health: Float,
    val maxHealth: Float,
    val kills: Int,
    val deaths: Int,
    val isLocal: Boolean
)

class NetworkSystem(
    private val phWorld: PhWorld,
    private val config: GameConfig,
    private val netCmps: ComponentMapper<NetworkComponent>,
    private val moveCmps: ComponentMapper<MoveComponent>,
    private val physicCmps: ComponentMapper<PhysicComponent>,
    private val attackCmps: ComponentMapper<AttackComponent>,
    private val animationCmps: ComponentMapper<AnimationComponent>,
    private val imageCmps: ComponentMapper<ImageComponent>,
    private val healthCmps: ComponentMapper<HealthComponent>,
    private val deadCmps: ComponentMapper<DeadComponent>,
    private val weaponCmps: ComponentMapper<WeaponComponent>,
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

    private var udpKeepAliveTimer = 0f
    private var lastSentCos = 0f
    private var lastSentAttacking = false
    private var lastSentReloading = false
    private var lastSentThrowing = false

    var combatFeedMessage: String = ""
        private set
    var combatFeedTimer: Float = 0f
        private set

    private val healthSystem: HealthSystem by lazy { world.system<HealthSystem>() }
    private val combatSystem: CombatSystem by lazy { world.system<CombatSystem>() }

    val activePlayerCount: Int
        get() = remoteEntities.size + (if (myNetworkId != -1) 1 else 0)

    fun postFeed(msg: String, duration: Float = 3.5f) {
        combatFeedMessage = msg
        combatFeedTimer = duration
    }

    fun getScoreboardEntries(): List<PlayerScoreEntry> {
        val list = mutableListOf<PlayerScoreEntry>()

        // Local player
        world.family(allOf = arrayOf(PlayerComponent::class, HealthComponent::class)).forEach { entity ->
            if (entity !in remotePlayerCmps) {
                val p = playerCmps[entity]
                val h = healthCmps[entity]
                list.add(
                    PlayerScoreEntry(
                        id = if (myNetworkId != -1) myNetworkId else 0,
                        name = p.playerName.ifEmpty { config.playerName },
                        health = h.currentHealth,
                        maxHealth = h.maxHealth,
                        kills = p.kills,
                        deaths = p.deaths,
                        isLocal = true
                    )
                )
            }
        }

        // Remote players
        remoteEntities.forEach { (id, entity) ->
            if (entity in netCmps && entity in healthCmps) {
                val net = netCmps[entity]
                val h = healthCmps[entity]
                val p = playerCmps.getOrNull(entity)
                list.add(
                    PlayerScoreEntry(
                        id = id,
                        name = net.playerName.ifEmpty { p?.playerName ?: "Player_$id" },
                        health = h.currentHealth,
                        maxHealth = h.maxHealth,
                        kills = net.kills.coerceAtLeast(p?.kills ?: 0),
                        deaths = net.deaths.coerceAtLeast(p?.deaths ?: 0),
                        isLocal = false
                    )
                )
            }
        }

        return list.sortedWith(compareByDescending<PlayerScoreEntry> { it.kills }.thenBy { it.id })
    }

    fun connect(host: String = NetworkConfig.serverHost, playerName: String = "Player", timeoutMs: Int = 4000) {
        val gameClient = GameClient(host, NetworkConfig.TCP_PORT, NetworkConfig.UDP_PORT)
        client = gameClient
        gameClient.connect(playerName, timeoutMs)
    }

    fun disconnect() {
        client?.close()
        client = null
        myNetworkId = -1
    }

    fun sendShoot(originX: Float, originY: Float, dirX: Float, dirY: Float, weaponIndex: Int = 0) {
        client?.sendShoot(originX, originY, dirX, dirY, weaponIndex)
    }

    override fun onTick() {
        if (combatFeedTimer > 0f) {
            combatFeedTimer = (combatFeedTimer - deltaTime).coerceAtLeast(0f)
            if (combatFeedTimer <= 0f) {
                combatFeedMessage = ""
            }
        }

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
                    postFeed("Connected as ${config.playerName} (ID #$myNetworkId)")
                    println("Assigned player network ID: $myNetworkId at spawn (${packet.spawnX}, ${packet.spawnY})")

                    world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                        if (localPlayer !in remotePlayerCmps) {
                            playerCmps[localPlayer].playerName = config.playerName
                            if (localPlayer !in netCmps) {
                                world.configureEntity(localPlayer) {
                                    netCmps.add(localPlayer) {
                                        networkId = myNetworkId
                                        isLocal = true
                                        playerName = config.playerName
                                    }
                                }
                            } else {
                                netCmps[localPlayer].networkId = myNetworkId
                                netCmps[localPlayer].playerName = config.playerName
                            }
                        }
                    }
                }

                is PlayerConnectedPacket -> {
                    val id = packet.id
                    if (id != myNetworkId && !remoteEntities.containsKey(id) && !pendingSpawnNetIds.contains(id)) {
                        spawnRemotePlayer(id, packet.x, packet.y)
                        postFeed("Player #$id joined the game")
                    }
                }

                is PlayerDisconnectedPacket -> {
                    val id = packet.id
                    val remote = remoteEntities.remove(id)
                    pendingSpawnNetIds.remove(id)
                    if (remote != null) {
                        val name = netCmps.getOrNull(remote)?.playerName ?: "Player #$id"
                        world.remove(remote)
                        postFeed("$name disconnected")
                        println("Removed remote player: $id")
                    }
                }

                is PlayerStatePacket -> {
                    val id = packet.id
                    if (id == myNetworkId) {
                        // Sync authoritative kills/deaths for local player if provided by server
                        world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                            if (localPlayer !in remotePlayerCmps) {
                                val pCmp = playerCmps[localPlayer]
                                if (packet.kills > pCmp.kills) {
                                    pCmp.kills = packet.kills
                                    pCmp.score = pCmp.kills * 100
                                }
                                if (packet.deaths > pCmp.deaths) {
                                    pCmp.deaths = packet.deaths
                                }
                            }
                        }
                    } else if (id > 0) {
                        val remote = remoteEntities[id]
                        if (remote != null && remote in netCmps) {
                            val netCmp = netCmps[remote]
                            val dx = packet.x - netCmp.targetX

                            // Detect horizontal movement either from explicit cos or position delta
                            val movingByCos = abs(packet.cos) > 0.01f
                            val movingByDelta = netCmp.hasInitializedPos && abs(dx) > 0.012f && abs(dx) < 4.0f

                            if (movingByCos || movingByDelta) {
                                netCmp.moveHoldTimer = 0.18f
                            }

                            // Determine facing sign (-1 left, +1 right)
                            when {
                                movingByCos -> netCmp.targetSin = if (packet.cos < 0f) -1f else 1f
                                abs(packet.sin) > 0.01f -> netCmp.targetSin = if (packet.sin < 0f) -1f else 1f
                                movingByDelta -> netCmp.targetSin = if (dx < 0f) -1f else 1f
                            }

                            val safeTargetX = if (packet.x in 2.5f..29.5f) packet.x else netCmp.targetX.coerceIn(4.0f, 27.0f)
                            val safeTargetY = if (packet.y in 2.5f..9.2f) packet.y else 5.34f

                            netCmp.targetX = safeTargetX
                            netCmp.targetY = safeTargetY
                            netCmp.targetCos = packet.cos

                            if (!netCmp.hasInitializedPos && remote in physicCmps) {
                                physicCmps[remote].body.setTransform(safeTargetX, safeTargetY, 0f)
                                netCmp.hasInitializedPos = true
                            }

                            // Update player name if sent
                            if (packet.playerName.isNotEmpty()) {
                                netCmp.playerName = packet.playerName
                                playerCmps.getOrNull(remote)?.playerName = packet.playerName
                            }

                            // Update kills / deaths
                            if (packet.kills > netCmp.kills) {
                                netCmp.kills = packet.kills
                                playerCmps.getOrNull(remote)?.kills = packet.kills
                            }
                            if (packet.deaths > netCmp.deaths) {
                                netCmp.deaths = packet.deaths
                                playerCmps.getOrNull(remote)?.deaths = packet.deaths
                            }

                            // Update weapon
                            val weapons = WeaponType.values()
                            if (packet.weaponIndex in weapons.indices) {
                                netCmp.weaponIndex = packet.weaponIndex
                                weaponCmps.getOrNull(remote)?.selectWeapon(weapons[packet.weaponIndex])
                            }

                            // Sync authoritative health if server provides it (> 0)
                            if (packet.health > 0f) {
                                healthSystem.setAuthoritativeHealth(remote, packet.health)
                            }

                            // Sync combat action timers
                            if (packet.isAttacking) {
                                if (netCmp.attackHoldTimer <= 0f) {
                                    // Fallback visual shot if server doesn't broadcast PlayerShootPacket
                                    val w = weaponCmps.getOrNull(remote)?.currentWeapon ?: WeaponType.PISTOL
                                    combatSystem.spawnRemoteShot(remote, w, netCmp.targetSin)
                                }
                                netCmp.attackHoldTimer = 0.35f
                            }
                            if (packet.isReloading) {
                                netCmp.reloadHoldTimer = 0.45f
                            }
                            if (packet.isThrowing) {
                                netCmp.throwHoldTimer = 0.45f
                            }
                        } else if (!remoteEntities.containsKey(id) && !pendingSpawnNetIds.contains(id)) {
                            spawnRemotePlayer(id, packet.x, packet.y)
                        }
                    }
                }

                is PlayerShootPacket -> {
                    val id = packet.id
                    if (id != myNetworkId) {
                        val remote = remoteEntities[id]
                        if (remote != null && remote in netCmps) {
                            val netCmp = netCmps[remote]
                            netCmp.attackHoldTimer = 0.35f
                            if (packet.directionX != 0f) {
                                netCmp.targetSin = if (packet.directionX < 0f) -1f else 1f
                            }
                            val weapons = WeaponType.values()
                            val weapon = if (packet.weaponIndex in weapons.indices) {
                                weapons[packet.weaponIndex]
                            } else {
                                WeaponType.PISTOL
                            }
                            weaponCmps.getOrNull(remote)?.selectWeapon(weapon)
                            combatSystem.spawnRemoteShot(remote, weapon, netCmp.targetSin)
                        }
                    }
                }

                is PlayerHitPacket -> {
                    println("Server authoritative HIT: from=${packet.fromId} to=${packet.toId} dmg=${packet.damage} kill=${packet.isKill}")
                    val attackerName = if (packet.fromId == myNetworkId) {
                        "You"
                    } else {
                        remoteEntities[packet.fromId]?.let { netCmps.getOrNull(it)?.playerName } ?: "Player_${packet.fromId}"
                    }
                    val victimName = if (packet.toId == myNetworkId) {
                        "You"
                    } else {
                        remoteEntities[packet.toId]?.let { netCmps.getOrNull(it)?.playerName } ?: "Player_${packet.toId}"
                    }

                    if (packet.toId == myNetworkId) {
                        // Local player took damage
                        world.family(allOf = arrayOf(PlayerComponent::class, HealthComponent::class)).forEach { localPlayer ->
                            if (localPlayer !in remotePlayerCmps) {
                                val h = healthCmps[localPlayer]
                                val p = playerCmps[localPlayer]
                                val died = if (packet.isKill) {
                                    healthSystem.forceKill(localPlayer)
                                    true
                                } else {
                                    healthSystem.damage(localPlayer, packet.damage, fromNetwork = true)
                                    h.currentHealth <= 0f
                                }
                                if (died) {
                                    p.deaths = if (packet.victimDeaths > p.deaths) packet.victimDeaths else p.deaths + 1
                                    postFeed("Eliminated by $attackerName! Respawning...")
                                } else {
                                    postFeed("Hit by $attackerName (-${packet.damage.toInt()} HP)")
                                }
                            }
                        }
                        // Also update remote attacker's kill count if they eliminated us
                        val attackerRemote = remoteEntities[packet.fromId]
                        if (attackerRemote != null && attackerRemote in netCmps) {
                            if (packet.isKill) {
                                val net = netCmps[attackerRemote]
                                net.kills = if (packet.attackerKills > net.kills) packet.attackerKills else net.kills + 1
                            }
                        }
                    } else {
                        // Remote player took damage
                        val targetRemote = remoteEntities[packet.toId]
                        var remoteDied = packet.isKill
                        if (targetRemote != null && targetRemote in healthCmps) {
                            val h = healthCmps[targetRemote]
                            if (packet.isKill) {
                                healthSystem.forceKill(targetRemote)
                                remoteDied = true
                            } else {
                                healthSystem.damage(targetRemote, packet.damage, fromNetwork = true)
                                if (h.currentHealth <= 0f) {
                                    remoteDied = true
                                }
                            }
                            if (remoteDied && targetRemote in netCmps) {
                                val net = netCmps[targetRemote]
                                net.deaths = if (packet.victimDeaths > net.deaths) packet.victimDeaths else net.deaths + 1
                            }
                        }

                        if (packet.fromId == myNetworkId) {
                            world.family(allOf = arrayOf(PlayerComponent::class)).forEach { localPlayer ->
                                if (localPlayer !in remotePlayerCmps) {
                                    val p = playerCmps[localPlayer]
                                    if (remoteDied) {
                                        p.kills = if (packet.attackerKills > p.kills) packet.attackerKills else p.kills + 1
                                        p.score = p.kills * 100
                                        postFeed("Eliminated $victimName! (+100 Score)")
                                    } else {
                                        postFeed("Hit $victimName (-${packet.damage.toInt()} HP)")
                                    }
                                }
                            }
                        } else if (remoteDied) {
                            postFeed("$attackerName eliminated $victimName!")
                        }
                    }
                }
            }
        }

        // 3. Interpolate position & drive animations smoothly for remote player entities
        remoteEntities.values.forEach { remote ->
            if (remote in netCmps && remote in physicCmps) {
                val net = netCmps[remote]
                val physic = physicCmps[remote]
                val body = physic.body

                // Tick down animation hold timers
                if (net.attackHoldTimer > 0f) net.attackHoldTimer = (net.attackHoldTimer - deltaTime).coerceAtLeast(0f)
                if (net.reloadHoldTimer > 0f) net.reloadHoldTimer = (net.reloadHoldTimer - deltaTime).coerceAtLeast(0f)
                if (net.throwHoldTimer > 0f) net.throwHoldTimer = (net.throwHoldTimer - deltaTime).coerceAtLeast(0f)
                if (net.moveHoldTimer > 0f) net.moveHoldTimer = (net.moveHoldTimer - deltaTime).coerceAtLeast(0f)

                val dx = net.targetX - body.position.x
                val dy = net.targetY - body.position.y

                // Keep kinematic velocity zero so only our smooth interpolation moves the body
                body.setLinearVelocity(0f, 0f)

                if (abs(dx) > 4f || abs(dy) > 4f) {
                    body.setTransform(net.targetX, net.targetY, 0f)
                } else if (abs(dx) > 0.005f || abs(dy) > 0.005f) {
                    val alpha = (deltaTime * 18f).coerceIn(0.15f, 0.65f)
                    body.setTransform(
                        body.position.x + dx * alpha,
                        body.position.y + dy * alpha,
                        0f
                    )
                }

                // Update facing direction
                val img = imageCmps.getOrNull(remote)
                if (img != null && net.targetSin != 0f) {
                    img.image.originX = img.image.width * 0.5f
                    img.image.scaleX = if (net.targetSin < 0f) -1f else 1f
                }

                // Update move component for reference
                val move = moveCmps.getOrNull(remote)
                if (move != null) {
                    move.cos = net.targetCos
                    move.lastCos = if (net.targetSin < 0f) -1f else 1f
                }

                // Drive remote player animation if not currently in DEAD state
                if (remote !in deadCmps) {
                    val anim = animationCmps.getOrNull(remote)
                    if (anim != null) {
                        anim.playMode = com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP
                        when {
                            net.reloadHoldTimer > 0f -> anim.nextAnimation(anim.model, AnimationType.RECHARGE)
                            net.throwHoldTimer > 0f -> anim.nextAnimation(anim.model, AnimationType.GRENADE)
                            net.attackHoldTimer > 0f -> anim.nextAnimation(anim.model, AnimationType.SHOT1)
                            net.moveHoldTimer > 0f || abs(dx) > 0.03f -> anim.nextAnimation(anim.model, AnimationType.RUN)
                            else -> anim.nextAnimation(anim.model, AnimationType.IDLE)
                        }
                    }
                }
            }
        }

        // 4. Send local player state & UDP NAT keepalive to server
        if (currentClient.isConnected && myNetworkId != -1) {
            udpKeepAliveTimer += deltaTime
            if (udpKeepAliveTimer >= 1.5f) {
                udpKeepAliveTimer = 0f
                currentClient.sendUdpRegister(myNetworkId)
            }

            syncTimer += deltaTime
            world.family(allOf = arrayOf(PlayerComponent::class, MoveComponent::class, PhysicComponent::class)).forEach { localPlayer ->
                if (localPlayer !in remotePlayerCmps) {
                    val physic = physicCmps[localPlayer]
                    val move = moveCmps[localPlayer]
                    val attack = attackCmps.getOrNull(localPlayer)
                    val img = imageCmps.getOrNull(localPlayer)
                    val health = healthCmps.getOrNull(localPlayer)
                    val weapon = weaponCmps.getOrNull(localPlayer)

                    val isAttacking = attack?.isAttacking ?: false
                    val isReloading = attack?.isReloading ?: false
                    val isThrowing = attack?.isThrowing ?: false
                    val stateChanged = move.cos != lastSentCos ||
                        isAttacking != lastSentAttacking ||
                        isReloading != lastSentReloading ||
                        isThrowing != lastSentThrowing

                    if (syncTimer >= syncRate || stateChanged) {
                        syncTimer = 0f
                        lastSentCos = move.cos
                        lastSentAttacking = isAttacking
                        lastSentReloading = isReloading
                        lastSentThrowing = isThrowing

                        val facingSign = if ((img?.image?.scaleX ?: 1f) < 0f) -1f else 1f
                        currentClient.sendState(
                            x = physic.body.position.x,
                            y = physic.body.position.y,
                            cos = move.cos,
                            sin = facingSign,
                            isAttacking = isAttacking,
                            isReloading = isReloading,
                            isThrowing = isThrowing,
                            health = health?.currentHealth ?: 100f,
                            weaponIndex = weapon?.currentWeapon?.ordinal ?: 0
                        )
                    }
                }
            }
        }
    }

    private fun spawnRemotePlayer(netId: Int, startX: Float, startY: Float) {
        val safeX = if (startX in 3.5f..28.5f) startX else 12.47f
        val safeY = if (startY in 3.5f..8.5f) startY else 5.34f
        world.entity {
            add<SpawnComponent> {
                type = "RemotePlayer"
                location.set(safeX, safeY)
                networkId = netId
            }
        }
        pendingSpawnNetIds.add(netId)
        println("Remote player spawn requested for network ID $netId at ($safeX, $safeY)")
    }

    override fun onDispose() {
        client?.close()
        client = null
        remoteEntities.clear()
        pendingSpawnNetIds.clear()
    }
}
