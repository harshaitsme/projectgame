#!/usr/bin/env bash
#
# deploy.sh — build the release binary, install it to a target directory, and
# restart the systemd service if it is present.
#
# Usage:
#   ./deploy.sh [INSTALL_DIR] [--no-build] [--dry-run]
#
# Examples:
#   ./deploy.sh /opt/shootgame
#   ./deploy.sh /opt/shootgame --no-build
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="/opt/shootgame"
SERVICE_NAME="shootgame-server"
NO_BUILD=0
DRY_RUN=0

# Trap for unexpected errors
cleanup() {
  if [[ $? -ne 0 ]]; then
    printf "\n==> [ERROR] Deployment failed unexpectedly.\n" >&2
  fi
}
trap cleanup EXIT

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) NO_BUILD=1; shift ;;
    --dry-run)  DRY_RUN=1; shift ;;
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

# Helper for dry-run execution
run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf "[DRY RUN] %s\n" "$*"
  else
    "$@"
  fi
}

BIN="$SCRIPT_DIR/target/release/shootgame-server"

# Check for sudo privileges early
if [[ "$DRY_RUN" -eq 0 ]] && ! sudo -v; then
  printf "==> [ERROR] Sudo privileges required.\n" >&2
  exit 1
fi

if [[ "$NO_BUILD" -eq 0 ]]; then
  printf "==> Building release binary\n"
  run_cmd cargo build --release --manifest-path "$SCRIPT_DIR/Cargo.toml"
fi

if [[ "$DRY_RUN" -eq 0 ]] && [[ ! -f "$BIN" ]]; then
  printf "Binary not found: %s (build first)\n" "$BIN" >&2
  exit 1
fi

printf "==> Installing to %s\n" "$INSTALL_DIR"
run_cmd sudo install -Dm 0755 "$BIN" "$INSTALL_DIR/shootgame-server"

# Copy the systemd unit if it exists
UNIT_SRC="$SCRIPT_DIR/deploy/systemd/$SERVICE_NAME.service"
if [[ -f "$UNIT_SRC" ]] && [[ -d /etc/systemd/system ]]; then
  printf "==> Installing systemd unit\n"
  run_cmd sudo cp "$UNIT_SRC" "/etc/systemd/system/$SERVICE_NAME.service"
  run_cmd sudo systemctl daemon-reload
  
  if run_cmd sudo systemctl restart "$SERVICE_NAME"; then
    printf "==> Service restarted: %s\n" "$SERVICE_NAME"
  else
    printf "==> Service installed but not started (enable with: sudo systemctl enable --now %s)\n" "$SERVICE_NAME"
  fi
else
  printf "==> No systemd unit installed; run manually:\n"
  printf "    %s/shootgame-server\n" "$INSTALL_DIR"
fi

printf "==> Done.\n"
