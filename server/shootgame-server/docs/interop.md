# Kotlin client and Rust server integration

The Kotlin client and the Rust server use Protocol Buffers with a one-byte packet type tag. TCP adds a four-byte little-endian length prefix. See the [wire protocol](wire-protocol.md) for the framing and packet types.

The schema lives in both [the Kotlin module](../../../core/src/main/proto/shootgame.proto) and [the Rust crate](../proto/shootgame.proto). When changing fields or messages, update both copies together. Keep existing field numbers stable so different versions can continue to decode those fields.

- Rust generates message types with `prost-build` and `protoc-bin-vendored` in [`build.rs`](../build.rs).
- The Kotlin module uses the `com.google.protobuf` Gradle plugin in [`core/build.gradle`](../../../core/build.gradle) to generate JVM message classes.
- The active client lives in [`core/src/main/kotlin/io/github/shootgame/network`](../../../core/src/main/kotlin/io/github/shootgame/network). `Wire.kt` handles packet framing and `GameClient.kt` handles the TCP and UDP connections.

Follow the [server README](../README.md) to run the server and bundled smoke-test client.
