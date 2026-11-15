# Kotlin and libGDX client

The Android game already includes the Protobuf client in the `core` module.

- [GameClient.kt](../../../../core/src/main/kotlin/io/github/shootgame/network/GameClient.kt) handles TCP login, UDP registration, and the inbound packet queue.
- [Wire.kt](../../../../core/src/main/kotlin/io/github/shootgame/network/Wire.kt) encodes and decodes TCP frames and UDP datagrams.
- [shootgame.proto](../../../../core/src/main/proto/shootgame.proto) defines the shared messages. Keep it synchronized with the [server schema](../../proto/shootgame.proto).

The [core Gradle build](../../../../core/build.gradle) downloads `protoc` and generates the Java message classes under `io.github.shootgame.proto`.

See the [wire protocol](../wire-protocol.md) for packet types and the [server README](../../README.md) for local startup commands.
