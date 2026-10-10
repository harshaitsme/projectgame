# ShootGame Server — Deployment Guide (Ubuntu)

Rust authoritative dedicated server for the 2D shooting game.

- Transport: TCP (`54555`) for login/packets + session control; UDP (`54777`) for low-latency state broadcasts.
- Simulation: fixed 60 Hz tick, specs ECS, server-authoritative combat.
- Persistence: in-memory + session tokens (no database yet).

## Quick start (native binary)

```bash
cargo build --release
./target/release/shootgame-server
```

Configure ports with env vars:

```bash
SHOOTGAME_TCP_PORT=54555 SHOOTGAME_UDP_PORT=54777 ./target/release/shootgame-server
```

---

## Option A — Docker

```bash
# Build and run with compose:
docker compose up -d --build

# Logs:
docker compose logs -f

# Stop:
docker compose down
```

Ports are mapped in `docker-compose.yml`:
- `54555/tcp`
- `54777/udp`

> Note: UDP port mapping is important for state delivery. If you run on a cloud VM, also open `54777/udp` in the security group / firewall.

Build the image manually:

```bash
docker build -t shootgame-server:latest .
docker run --rm -p 54555:54555 -p 54777:54777/udp shootgame-server:latest
```

---

## Option B — systemd on bare Ubuntu

1. Install the binary + a service user:

   ```bash
   sudo useradd --system --home /opt/shootgame --create-home shootgame
   sudo mkdir -p /opt/shootgame
   sudo cp target/release/shootgame-server /opt/shootgame/shootgame-server
   sudo cp deploy/systemd/shootgame-server.service /etc/systemd/system/
   ```

2. Enable + start:

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now shootgame-server
   sudo systemctl status shootgame-server
   journalctl -u shootgame-server -f
   ```

3. Update workflow (after rebuilding):

   ```bash
   ./deploy.sh /opt/shootgame   # copies binary + restarts the service
   ```

---

## Firewall (UFW example)

```bash
sudo ufw allow 54555/tcp
sudo ufw allow 54777/udp
```

---

## Environment variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `SHOOTGAME_TCP_PORT` | `54555` | TCP control/login port |
| `SHOOTGAME_UDP_PORT` | `54777` | UDP state broadcast port |

---

## Client compatibility

The Rust test client lives in `src/bin/test_client.rs`:

```bash
# start server first, then:
cargo run --release --bin test_client
# Passes combat + reconnect tests when id=1 & id=2 are available.
```

Bringing the Kotlin/libGDX client requires moving the wire protocol to a
language-neutral format (bincode ↔ Kryo are incompatible). See
`docs/interop.md` (to come) for the Protobuf/FlatBuffers migration path.
