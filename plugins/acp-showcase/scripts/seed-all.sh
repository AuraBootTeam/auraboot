#!/usr/bin/env bash
# ACP Showcase — Seed all demo data (safety rules + demo requests)
# Run after plugin import: bash plugins/acp-showcase/scripts/seed-all.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

bash "$SCRIPT_DIR/seed-safety-rules.sh"
echo ""
bash "$SCRIPT_DIR/seed-demo-requests.sh"
