package io.github.shootgame.network

import com.badlogic.gdx.Gdx
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

    private val sendLock = Any()
    @Volatile var isConnected: Boolean = false
        private set

    @Volatile var playerId: Int = -1
        private set

    @Volatile var sessionToken: String = ""
        private set

    val packetQueue = ConcurrentLinkedQueue<Any>()

    fun connect(name: String, timeoutMs: Int = 4000) {
        Thread {
            try {
                println("Connecting to ShootGame server at $host: TCP=$tcpPort, UDP=$udpPort...")
                val socket = Socket()
                socket.connect(InetSocketAddress(host, tcpPort), timeoutMs)
                socket.tcpNoDelay = true

                tcpSocket = socket
                tcpIn = socket.getInputStream()
                tcpOut = socket.getOutputStream()
                isConnected = true

                // Retrieve saved session token if available for seamless reconnect
                val savedToken = try {
                    Gdx.app?.getPreferences("shootgame")?.getString("session_token", "") ?: ""
                } catch (_: Exception) {
                    ""
                }

                // Send login packet over TCP
                val login = LoginPacket.newBuilder()
                    .setPlayerName(name)
                    .setSessionToken(savedToken)
                    .build()
                sendTcp(Wire.TYPE_LOGIN, login)

                // Start TCP read loop
                Thread { runTcpLoop() }.apply {
                    isDaemon = true
                    setName("ShootGame-TCP-Worker")
                }.start()

                // Setup UDP socket and start UDP receive loop
                val udp = DatagramSocket()
                udpSocket = udp
                Thread { runUdpLoop(udp) }.apply {
                    isDaemon = true
                    setName("ShootGame-UDP-Worker")
                }.start()

                println("TCP connection established to $host:$tcpPort")
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
                        try {
                            Gdx.app?.getPreferences("shootgame")?.putString("session_token", sessionToken)?.flush()
                        } catch (_: Exception) {}

                        // Send UDP registration datagram so server learns our UDP address
                        sendUdpRegister(resp.assignedId)
                        packetQueue.add(resp)
                        println("Logged into server: assignedId=${resp.assignedId}, token=${resp.sessionToken}")
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

    private fun sendUdpRegister(assignedId: Int) {
        try {
            val udp = udpSocket ?: return
            val regBytes = Wire.registerDatagram(assignedId)
            val packet = DatagramPacket(
                regBytes,
                regBytes.size,
                InetAddress.getByName(host),
                udpPort
            )
            udp.send(packet)
            println("Sent UDP registration packet for player $assignedId to $host:$udpPort")
        } catch (e: Exception) {
            println("Failed to send UDP registration: ${e.message}")
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
        isThrowing: Boolean = false
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
