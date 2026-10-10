# Client Interop: connecting the Kotlin/libGDX client — STATUS: Rust side DONE ✅

The Rust server now speaks **Protocol Buffers** over a length-prefixed,
type-tagged wire. No system `protoc` is required anywhere:
- Rust uses `prost` + `protoc-bin-vendored` (protoc ships as a build-dep).
- Kotlin uses protobuf-java via the Gradle plugin (protoc fetched from Maven).

See:
- `wire-protocol.md` — the exact wire format + type tags.
- `kotlin/README.md` — Gradle setup + a drop-in `GameClient` + `Wire.kt`.

## What changed from the bootstrap plan

- Wire format moved **bincode → Protobuf** (schema in `proto/shootgame.proto`).
- TCP now uses **length-prefix framing** `[u32 len][u8 type][body]`; UDP uses
  `[u8 type][body]`. Handled in `src/network/mod.rs` (`tcp_frame`,
  `udp_datagram`, `parse_tcp`, `parse_*`).
- The 1-byte type tag is preserved so both directions disambiguate packets.
- `edition = "2024"` note: call `rand::random()` (not `rng.gen()`, `gen` is a
  reserved keyword).

## Remaining (Kotlin side, outside this repo)

1. Copy `proto/shootgame.proto` into `core/src/main/proto/`.
2. Apply the `com.google.protobuf` Gradle plugin in `core/build.gradle`
   (see `kotlin/README.md` §2).
3. Add `Wire.kt` + the `GameClient` skeleton.
4. Remove the Kryo `ServerLauncher.kt` / Kryo packet classes from `Packets.kt`.

## Validation

- `cargo build` (native) ✅
- Docker image `shootgame-server:protobuf` builds and serves ✅
- `test_client` passes combat + reconnect over the Protobuf wire ✅
