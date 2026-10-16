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
INSTALL_DIR="/opt/shootgame"
SERVICE_NAME="shootgame-server"
NO_BUILD=0

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      NO_BUILD=1
      shift
      ;;
    -*)
      printf "Unknown option: %s\n" "$1" >&2
      exit 2
      ;;
    *)
      INSTALL_DIR="$1"
      shift
      ;;
  esac
done

BIN="$SCRIPT_DIR/target/release/shootgame-server"

if [[ "$NO_BUILD" -eq 0 ]]; then
  printf "==> Building release binary\n"
  cargo build --release --manifest-path "$SCRIPT_DIR/Cargo.toml"
fi

if [[ ! -f "$BIN" ]]; then
  printf "Binary not found: %s (build first)\n" "$BIN" >&2
  exit 1
fi

printf "==> Installing to %s\n" "$INSTALL_DIR"
sudo install -Dm 0755 "$BIN" "$INSTALL_DIR/shootgame-server"

# Copy the systemd unit if it exists and we can write there.
UNIT_SRC="$SCRIPT_DIR/deploy/systemd/$SERVICE_NAME.service"
if [[ -f "$UNIT_SRC" ]] && [[ -d /etc/systemd/system ]]; then
  printf "==> Installing systemd unit\n"
  sudo cp "$UNIT_SRC" "/etc/systemd/system/$SERVICE_NAME.service"
  sudo systemctl daemon-reload
  
  if sudo systemctl restart "$SERVICE_NAME"; then
    printf "==> Service restarted: %s\n" "$SERVICE_NAME"
  else
    printf "==> Service installed but not started (enable with: sudo systemctl enable --now %s)\n" "$SERVICE_NAME"
  fi
else
  printf "==> No systemd unit installed; run manually:\n"
  printf "    %s/shootgame-server\n" "$INSTALL_DIR"
fi

printf "==> Done.\n"
