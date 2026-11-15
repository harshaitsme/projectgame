# ShootGame multiplayer server

The Rust server hosts two-player Duo and four-player Squad rooms for the Android client.

## Run locally

From `server/shootgame-server/`, use a Rust toolchain that supports edition 2024:

```sh
cargo run --locked --bin shootgame-server
```

| Environment variable | Default | Service |
| --- | --- | --- |
| `SHOOTGAME_TCP_PORT` | `54555` | Login and reliable gameplay packets |
| `SHOOTGAME_UDP_PORT` | `54777` | UDP registration and gameplay updates |
| `SHOOTGAME_HTTP_PORT` | `54556` | Dashboard at `/dashboard` and JSON at `/api/analytics` |

With the server running on the default ports, run the bundled client from a second terminal in the same directory:

```sh
cargo run --locked --bin test_client
```

## Run with Docker Compose

Create a local `.env` file in this directory. Set `FIREBASE_DB_URL` to an empty value for local play without Firebase sync. For a Firebase deployment, set it to your Realtime Database URL and supply `FIREBASE_DB_SECRET` when required.

```sh
docker compose up --build
```

See the [wire protocol](docs/wire-protocol.md) and [client integration notes](docs/interop.md) for the packet format and schema locations.
