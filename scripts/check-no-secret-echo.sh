#!/usr/bin/env bash
# Wrapper so this gate shows up in `ls scripts/check-*.sh` — the repo's local-gate inventory.
# Implementation lives in the .mjs next to it.
set -euo pipefail
exec node "$(dirname "$0")/check-no-secret-echo.mjs" "$@"
