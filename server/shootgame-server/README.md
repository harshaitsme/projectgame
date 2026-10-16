# ShootGame Server

![Rust](<unsafe_url>https://img.shields.io/badge/rust-%23000000.svg?style=for-the-badge&logo=rust&logoColor=white</unsafe_url>)
![Docker](<unsafe_url>https://img.shields.io/badge/docker-%230db7ed.svg?style=for-the-badge&logo=docker&logoColor=white</unsafe_url>)
![Ubuntu](<unsafe_url>https://img.shields.io/badge/Ubuntu-E95420?style=for-the-badge&logo=ubuntu&logoColor=white</unsafe_url>)

Rust authoritative dedicated server for the 2D shooting game.

- **Transport:** TCP (`54555`) for login/packets + session control; UDP (`54777`) for low-latency state broadcasts.
- **Simulation:** Fixed 60 Hz tick, specs ECS, server-authoritative combat.
- **Persistence:** In-memory + session tokens (no database yet).

---

## 📖 Table of Contents
- [Quick Start](#-quick-start)
- [Deployment Options](#-deployment-options)
  - [Option A: Docker](#option-a--docker)
  - [Option B: systemd (Bare Metal)](#option-b--systemd-on-bare-ubuntu)
- [Firewall Configuration](#-firewall-configuration)
- [Environment Variables](#-environment-variables)
- [Client Compatibility](#-client-compatibility)

---

## 🚀 Quick Start

To build and run the server locally:

```bash
cargo build --release
./target/release/shootgame-server
To configure custom ports:
SHOOTGAME_TCP_PORT=54555 SHOOTGAME_UDP_PORT=54777 ./target/release/shootgame-server
📦 Deployment Options
OPTION A — DOCKER
The recommended way to run in production.
# Start the server in detached mode
docker compose up -d --build

# View logs
docker compose logs -f
[!NOTE] UDP port mapping is critical for state delivery. If you are deploying on a cloud VM, ensure 54777/udp is open in your security group.
Manual Docker Build
If you prefer not to use Compose, you can build and run the image directly:
docker build -t shootgame-server:latest .
docker run --rm -p 54555:54555 -p 54777:54777/udp shootgame-server:latest
OPTION B — SYSTEMD ON BARE UBUNTU
For running directly on the host OS as a background service.
Create a dedicated service user:
sudo useradd --system --home /opt/shootgame --create-home shootgame
Deploy the application:
./deploy.sh /opt/shootgame
Useful systemd Commands
# Check status
sudo systemctl status shootgame-server

# View live logs
journalctl -u shootgame-server -f
🔒 Firewall Configuration (UFW)
Ensure your firewall allows traffic on the required ports:
sudo ufw allow 54555/tcp
sudo ufw allow 54777/udp
⚙️ Environment Variables

VARIABLE


DEFAULT


PURPOSE


SHOOTGAME_TCP_PORT


54555


TCP control/login port


SHOOTGAME_UDP_PORT


54777


UDP state broadcast port


# 🎮 Client Compatibility
The Rust test client is located in src/bin/<unsafe_url>test_client.rs</unsafe_url>.
cargo run --release --bin test_client
[!WARNING] Kotlin/libGDX Clients: The current wire protocol uses bincode, which is incompatible with Kryo. Moving to a language-neutral format (Protobuf/FlatBuffers) is planned. See docs/interop.md for the migration path.
