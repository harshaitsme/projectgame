ShootGame Server — Deployment Guide (Ubuntu)
Rust authoritative dedicated server for the 2D shooting game.
Transport: TCP (54555) for login/packets + session control; UDP (54777) for low-latency state broadcasts.
Simulation: Fixed 60 Hz tick, specs ECS, server-authoritative combat.
Persistence: In-memory + session tokens (no database yet).
Prerequisites
Rust 1.70+ (for native builds)
Docker & Docker Compose (for containerized deployment)
sudo privileges (for systemd installation)
Quick Start (Native Binary)
To build and run the server locally:
cargo build --release
./target/release/shootgame-server
To configure custom ports using environment variables:
SHOOTGAME_TCP_PORT=54555 SHOOTGAME_UDP_PORT=54777 ./target/release/shootgame-server
Option A — Docker
Build and run the server using Docker Compose:
# Start the server in detached mode
docker compose up -d --build

# View logs
docker compose logs -f

# Stop the server
docker compose down
Note: UDP port mapping is critical for state delivery. If you are deploying on a cloud VM, ensure 54777/udp is open in your security group or firewall.
MANUAL DOCKER BUILD
If you prefer not to use Compose, you can build and run the image directly:
docker build -t shootgame-server:latest .
docker run --rm -p 54555:54555 -p 54777:54777/udp shootgame-server:latest
Option B — systemd on Bare Ubuntu
For production environments, running the server as a systemd service is recommended.
Create a dedicated service user:
sudo useradd --system --home /opt/shootgame --create-home shootgame
Deploy the application: Use the provided deployment script to install the binary and configure the service.
./deploy.sh /opt/shootgame
Manage the service:
sudo systemctl status shootgame-server
journalctl -u shootgame-server -f
Firewall Configuration (UFW)
Ensure your firewall allows traffic on the required ports:
sudo ufw allow 54555/tcp
sudo ufw allow 54777/udp
Environment Variables

VARIABLE


DEFAULT


PURPOSE


SHOOTGAME_TCP_PORT


54555


TCP control/login port


SHOOTGAME_UDP_PORT


54777


UDP state broadcast port


Client Compatibility
The Rust test client is located in src/bin/<unsafe_url>test_client.rs</unsafe_url>.
To run the test client (ensure the server is running first):
cargo run --release --bin test_client
Note for Kotlin/libGDX Clients: The current wire protocol uses bincode, which is incompatible with Kryo. Moving to a language-neutral format (Protobuf/FlatBuffers) is planned. See docs/interop.md for the migration path.
