#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[seed-marketplace] deprecated name; delegating to sync-marketplace-catalog.sh" >&2
exec "$SCRIPT_DIR/sync-marketplace-catalog.sh" "$@"
