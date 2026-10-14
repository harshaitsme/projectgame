#!/usr/bin/env bash
#
# deploy.sh — build the release binary, install it to a target directory, and
# restart the systemd service if it is present.
#
# Usage:
#   ./deploy.sh [INSTALL_DIR] [--no-build]
#
# Examples:
#   ./deploy.sh /opt/shootgame
#   ./deploy.sh /opt/shootgame --no-build   # reuse existing target/release binary
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${1:-/opt/shootgame}"
SERVICE_NAME="shootgame-server"
NO_BUILD=0

shift || true
for arg in "${@:-}"; do
  case "$arg" in
    --no-build) NO_BUILD=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

BIN="$SCRIPT_DIR/target/release/shootgame-server"

if [[ "$NO_BUILD" -eq 0 ]]; then
  echo "==> Building release binary"
  cargo build --release --manifest-path "$SCRIPT_DIR/Cargo.toml"
fi

[[ -f "$BIN" ]] || { echo "Binary not found: $BIN (build first)" >&2; exit 1; }

echo "==> Installing to $INSTALL_DIR"
sudo install -Dm 0755 "$BIN" "$INSTALL_DIR/shootgame-server"

# Copy the systemd unit if it exists and we can write there.
UNIT_SRC="$SCRIPT_DIR/deploy/systemd/$SERVICE_NAME.service"
if [[ -f "$UNIT_SRC" ]] && [[ -d /etc/systemd/system ]]; then
  echo "==> Installing systemd unit"
  sudo cp "$UNIT_SRC" "/etc/systemd/system/$SERVICE_NAME.service"
  sudo systemctl daemon-reload
  sudo systemctl restart "$SERVICE_NAME" \
    && echo "==> Service restarted: $SERVICE_NAME" \
    || echo "==> Service installed but not started (enable with: sudo systemctl enable --now $SERVICE_NAME)"
else
  echo "==> No systemd unit installed; run manually:"
  echo "    $INSTALL_DIR/shootgame-server"
fi

echo "==> Done."
