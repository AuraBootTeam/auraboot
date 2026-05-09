#!/bin/bash
#
# host-env-export.sh — symmetric counterpart to r2-env-export.sh.
# Exports the canonical host-mode env contract so every shell that
# runs Playwright / psql / curl picks up identical defaults.
#
# Usage:
#
#     source scripts/dev/host-env-export.sh
#
# After sourcing, the shell sees:
#   - BE_PORT=6443 / VITE_PORT=5173 / BFF_PORT=3500
#   - PG_HOST=localhost / PG_PORT=5432 / PG_USER=auraboot / PG_DB=aura_boot
#   - PGPASSWORD=auraboot_dev (only if not already set)
#   - BACKEND_URL=http://localhost:$BE_PORT
#   - PLAYWRIGHT_BASE_URL=http://localhost:$VITE_PORT
#   - BFF_URL=http://localhost:$BFF_PORT
#
# Existing values are preserved — pre-export to override (e.g. for a
# per-developer custom port).
#
# Source-only: see r2-env-export.sh for the same source-vs-exec guard.

# ---------- detect source vs exec ----------

__host_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __host_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __host_sourced=1
fi

if [ "$__host_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: host-env-export.sh must be sourced, not executed.

  Use:    source scripts/dev/host-env-export.sh
  Not:    bash scripts/dev/host-env-export.sh
HINT
    exit 2
fi

unset __host_sourced

# ---------- export host defaults ----------

export BE_PORT="${BE_PORT:-6443}"
export VITE_PORT="${VITE_PORT:-5173}"
export BFF_PORT="${BFF_PORT:-3500}"
export PG_HOST="${PG_HOST:-localhost}"
export PG_PORT="${PG_PORT:-5432}"
export PG_USER="${PG_USER:-auraboot}"
export PG_DB="${PG_DB:-aura_boot}"
export PGPASSWORD="${PGPASSWORD:-auraboot_dev}"
export BACKEND_URL="${BACKEND_URL:-http://localhost:$BE_PORT}"
export PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:-http://localhost:$VITE_PORT}"
export BFF_URL="${BFF_URL:-http://localhost:$BFF_PORT}"

# ---------- summary ----------

cat <<SUMMARY
✓ host-mode env loaded (canonical defaults)
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY
