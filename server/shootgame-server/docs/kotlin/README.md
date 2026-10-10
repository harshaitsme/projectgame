# Connecting the Kotlin/libGDX client

The Rust server now speaks **Protobuf over a length-prefixed, type-tagged wire**
(see `../wire-protocol.md`). The Rust side is done (`prost` + vendored protoc —
no system protoc anywhere). Here's how to wire your game client.

## 1. Share the schema

Copy the schema into your Kotlin project:

```
core/src/main/proto/shootgame.proto
```

(from `server/shootgame-server/proto/shootgame.proto` — it already carries the
`java_package = io.github.shootgame.proto` option.)

## 2. Gradle setup (protobuf plugin, no system protoc)

`settings.gradle` (root) — pluginManagement:

```gradle
pluginManagement {
    plugins {
        id 'com.google.protobuf' version '0.9.4'
    }
}
```

`core/build.gradle`:

```gradle
plugins {
    id 'com.google.protobuf'
}

dependencies {
    implementation 'com.google.protobuf:protobuf-java:3.25.3'
}

protobuf {
    protoc {
        // The plugin fetches this from Maven; NO system protoc needed.
        artifact = 'com.google.protobuf:protoc:3.25.3'
    }
    generateProtoTasks {
        all().each { task ->
            task.builtins { java {} }
        }
    }
}
```

This generates Java classes (`io.github.shootgame.proto.LoginPacket`, …) that
Kotlin uses directly.

## 3. Wire codec

Add `Wire.kt` (in this folder) to `core/src/main/kotlin/io/github/shootgame/network/`.
It implements the exact framing the Rust server uses.

## 4. Client skeleton

```kotlin
package io.github.shootgame.network

import com.badlogic.gdx.Gdx
import io.github.shootgame.proto.*

class GameClient(private val host: String, private val tcpPort: Int, private val udpPort: Int) {
    private lateinit var tcp: java.net.Socket
    private lateinit var udp: java.net.DatagramSocket
    private var playerId = -1
    private var sessionToken = ""
    private val prefs = Gdx.app.getPreferences("shootgame")

    @Volatile var onState: ((PlayerStatePacket) -> Unit)? = null
    @Volatile var onHit: ((PlayerHitPacket) -> Unit)? = null

    fun connect(name: String) {
        val token = prefs.getString("session_token", "")
        tcp = java.net.Socket(host, tcpPort)

        // Login over TCP.
        val login = LoginPacket.newBuilder()
            .setPlayerName(name)
            .setSessionToken(token)
            .build()
        tcp.getOutputStream().write(Wire.tcpFrame(Wire.TYPE_LOGIN, login))

        // Read framed LoginResponse.
        val (type, body) = Wire.readFrame(tcp.getInputStream())!!
        require(type == Wire.TYPE_LOGIN_RESPONSE)
        val resp = LoginResponsePacket.parseFrom(body)
        playerId = resp.assignedId
        sessionToken = resp.sessionToken
        prefs.putString("session_token", sessionToken).flush()
        println("Logged in: id=$playerId spawn=(${resp.spawnX},${resp.spawnY})")

        // UDP for low-latency state.
        udp = java.net.DatagramSocket()
        udp.send(java.net.DatagramPacket(
            Wire.registerDatagram(playerId),
            5,
            java.net.InetAddress.getByName(host),
            udpPort,
        ))

        // UDP receive loop on a background thread.
        Thread { udpLoop() }.apply { isDaemon = true }.start()
    }

    /** Client-authoritative input the server will re-stamp with our id. */
    fun sendState(x: Float, y: Float, cos: Float, sin: Float) {
        val state = PlayerStatePacket.newBuilder()
            .setId(playerId).setX(x).setY(y).setCos(cos).setSin(sin).build()
        tcp.getOutputStream().write(Wire.tcpFrame(Wire.TYPE_PLAYER_STATE, state))
    }

    fun sendShoot(originX: Float, originY: Float, dirX: Float, dirY: Float) {
        val shoot = PlayerShootPacket.newBuilder()
            .setId(playerId).setWeaponIndex(0)
            .setOriginX(originX).setOriginY(originY)
            .setDirectionX(dirX).setDirectionY(dirY).build()
        tcp.getOutputStream().write(Wire.tcpFrame(Wire.TYPE_PLAYER_SHOOT, shoot))
    }

    private fun udpLoop() {
        val buf = ByteArray(4096)
        while (!udp.isClosed) {
            val p = java.net.DatagramPacket(buf, buf.size)
            udp.receive(p)
            val (type, body) = Wire.readDatagram(p)
            when (type) {
                Wire.TYPE_PLAYER_STATE ->
                    onState?.invoke(PlayerStatePacket.parseFrom(body))
                Wire.TYPE_PLAYER_HIT ->
                    onHit?.invoke(PlayerHitPacket.parseFrom(body))
            }
        }
    }

    fun close() { udp.close(); tcp.close() }
}
```

## 5. Swap out Kryo

Your existing `ServerLauncher.kt` and any Kryo client can be replaced by
`GameClient` above. The old `Packets.kt` Kryo packet classes are superseded by
the generated `io.github.shootgame.proto.*` classes — delete them once the new
client is wired in.

## Interop rules to keep in sync

- Type tags in `NETWORK_CONFIG.kt` are now unused by the wire; keep them only
  if other subsystems reference them. The authoritative tags live in `Wire.kt`.
- The server stamps every packet's `id` with the connection's assigned id, so
  the client can send any value in `setId(...)` and the server ignores it.
