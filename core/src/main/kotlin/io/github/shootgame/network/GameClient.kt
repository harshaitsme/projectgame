package io.github.shootgame.network

import io.github.shootgame.proto.*
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

class GameClient(
    val host: String = NetworkConfig.serverHost,
    val tcpPort: Int = NetworkConfig.TCP_PORT,
    val udpPort: Int = NetworkConfig.UDP_PORT
) {
    private var tcpSocket: Socket? = null
    private var tcpIn: InputStream? = null
    private var tcpOut: OutputStream? = null
    private var udpSocket: DatagramSocket? = null
    private var serverInetAddress: InetAddress? = null

    private val sendLock = Any()
    @Volatile var isConnected: Boolean = false
        private set

    @Volatile var playerId: Int = -1
        private set

    @Volatile var sessionToken: String = ""
        private set

    @Volatile var localPlayerName: String = "Player"
        private set

    @Volatile var activeRoomCode: String = ""
        private set

    @Volatile var activeGameMode: String = "DUO"
        private set

    val packetQueue = ConcurrentLinkedQueue<Any>()

    fun connect(
        name: String,
        roomCode: String = "",
        gameMode: String = "DUO",
        createRoom: Boolean = false,
        timeoutMs: Int = 4000
    ) {
        localPlayerName = name
        activeRoomCode = roomCode.trim().uppercase()
        activeGameMode = gameMode.trim().uppercase()
        Thread {
            try {
                println("Connecting to ShootGame server at $host: TCP=$tcpPort, UDP=$udpPort (mode=$activeGameMode, room=$activeRoomCode, create=$createRoom)...")
                val inetAddr = InetAddress.getByName(host)
                serverInetAddress = inetAddr

                // 1. Setup UDP socket first so it is ready before LoginResponse arrives
                val udp = DatagramSocket()
                udpSocket = udp

                // 2. Connect TCP socket
                val socket = Socket()
                socket.connect(InetSocketAddress(inetAddr, tcpPort), timeoutMs)
                socket.tcpNoDelay = true

                tcpSocket = socket
                tcpIn = socket.getInputStream()
                tcpOut = socket.getOutputStream()
                isConnected = true

                // 3. Start UDP receive loop
                Thread { runUdpLoop(udp) }.apply {
                    isDaemon = true
                    setName("ShootGame-UDP-Worker")
                }.start()

                // 4. Start TCP read loop
                Thread { runTcpLoop() }.apply {
                    isDaemon = true
                    setName("ShootGame-TCP-Worker")
                }.start()

                // 5. Send login packet over TCP with room code & mode
                val login = LoginPacket.newBuilder()
                    .setPlayerName(name)
                    .setSessionToken(sessionToken)
                    .setRoomCode(activeRoomCode)
                    .setGameMode(activeGameMode)
                    .setCreateRoom(createRoom)
                    .build()
                sendTcp(Wire.TYPE_LOGIN, login)

                println("TCP connection established to $host:$tcpPort as '$name' [Room=$activeRoomCode, Mode=$activeGameMode]")
            } catch (e: Exception) {
                println("Could not connect to multiplayer server at $host: ${e.message}. Running in offline mode.")
                close()
            }
        }.apply {
            isDaemon = true
            setName("ShootGame-Connect-Thread")
        }.start()
    }

    private fun runTcpLoop() {
        val input = tcpIn ?: return
        try {
            while (isConnected && !Thread.currentThread().isInterrupted) {
                val frame = Wire.readFrame(input) ?: break
                val type = frame.first
                val body = frame.second
                when (type) {
                    Wire.TYPE_LOGIN_RESPONSE -> {
                        val resp = LoginResponsePacket.parseFrom(body)
                        playerId = resp.assignedId
                        sessionToken = resp.sessionToken
                        if (resp.roomCode.isNotBlank()) {
                            activeRoomCode = resp.roomCode.trim().uppercase()
                        }
                        if (resp.gameMode.isNotBlank()) {
                            activeGameMode = resp.gameMode.trim().uppercase()
                        }

                        // Send UDP registration datagram immediately (twice for UDP reliability)
                        sendUdpRegister(resp.assignedId)
                        sendUdpRegister(resp.assignedId)
                        packetQueue.add(resp)
                        println("Logged into server: assignedId=${resp.assignedId}, room=$activeRoomCode, spawn=(${resp.spawnX}, ${resp.spawnY})")
                    }
                    Wire.TYPE_PLAYER_CONNECTED -> {
                        packetQueue.add(PlayerConnectedPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_DISCONNECTED -> {
                        packetQueue.add(PlayerDisconnectedPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_STATE -> {
                        packetQueue.add(PlayerStatePacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_HIT -> {
                        packetQueue.add(PlayerHitPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_SHOOT -> {
                        packetQueue.add(PlayerShootPacket.parseFrom(body))
                    }
                }
            }
        } catch (e: Exception) {
            if (isConnected) {
                println("TCP connection lost: ${e.message}")
            }
        } finally {
            close()
        }
    }

    private fun runUdpLoop(udp: DatagramSocket) {
        val buffer = ByteArray(4096)
        try {
            while (isConnected && !udp.isClosed && !Thread.currentThread().isInterrupted) {
                val datagram = DatagramPacket(buffer, buffer.size)
                udp.receive(datagram)
                val (type, body) = Wire.readDatagram(datagram)
                when (type) {
                    Wire.TYPE_PLAYER_STATE -> {
                        packetQueue.add(PlayerStatePacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_HIT -> {
                        packetQueue.add(PlayerHitPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_SHOOT -> {
                        packetQueue.add(PlayerShootPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_CONNECTED -> {
                        packetQueue.add(PlayerConnectedPacket.parseFrom(body))
                    }
                    Wire.TYPE_PLAYER_DISCONNECTED -> {
                        packetQueue.add(PlayerDisconnectedPacket.parseFrom(body))
                    }
                }
            }
        } catch (e: Exception) {
            if (isConnected && !udp.isClosed) {
                println("UDP receive error: ${e.message}")
            }
        }
    }

    fun sendUdpRegister(assignedId: Int = playerId) {
        if (assignedId < 0) return
        try {
            val udp = udpSocket ?: return
            val addr = serverInetAddress ?: InetAddress.getByName(host)
            val regBytes = Wire.registerDatagram(assignedId)
            val packet = DatagramPacket(regBytes, regBytes.size, addr, udpPort)
            udp.send(packet)
        } catch (_: Exception) {
        }
    }

    private fun sendTcp(type: Byte, message: com.google.protobuf.MessageLite) {
        if (!isConnected) return
        try {
            val frame = Wire.tcpFrame(type, message)
            synchronized(sendLock) {
                tcpOut?.write(frame)
                tcpOut?.flush()
            }
        } catch (e: Exception) {
            println("TCP send error: ${e.message}")
            close()
        }
    }

    fun sendState(
        x: Float,
        y: Float,
        cos: Float,
        sin: Float,
        isAttacking: Boolean = false,
        isReloading: Boolean = false,
        isThrowing: Boolean = false,
        health: Float = 100f,
        weaponIndex: Int = 0
    ) {
        if (!isConnected || playerId < 0) return
        val packet = PlayerStatePacket.newBuilder()
            .setId(playerId)
            .setX(x)
            .setY(y)
            .setCos(cos)
            .setSin(sin)
            .setIsAttacking(isAttacking)
            .setIsReloading(isReloading)
            .setIsThrowing(isThrowing)
            .setHealth(health)
            .setPlayerName(localPlayerName)
            .setWeaponIndex(weaponIndex)
            .setRoomCode(activeRoomCode)
            .build()
        sendTcp(Wire.TYPE_PLAYER_STATE, packet)
    }

    fun sendShoot(
        originX: Float,
        originY: Float,
        directionX: Float,
        directionY: Float,
        weaponIndex: Int = 0
    ) {
        if (!isConnected || playerId < 0) return
        val packet = PlayerShootPacket.newBuilder()
            .setId(playerId)
            .setWeaponIndex(weaponIndex)
            .setOriginX(originX)
            .setOriginY(originY)
            .setDirectionX(directionX)
            .setDirectionY(directionY)
            .setRoomCode(activeRoomCode)
            .build()
        sendTcp(Wire.TYPE_PLAYER_SHOOT, packet)
    }

    fun close() {
        isConnected = false
        playerId = -1
        try {
            tcpSocket?.close()
        } catch (_: Exception) {}
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        tcpSocket = null
        tcpIn = null
        tcpOut = null
        udpSocket = null
    }
}
