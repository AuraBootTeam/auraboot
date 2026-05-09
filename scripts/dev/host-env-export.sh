#!/bin/bash
#
# host-env-export.sh — symmetric counterpart to r2-env-export.sh.
# Delegates to lib/env-loader.sh::aura_env_load host so all five
# profile scripts share one canonical default table.
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

# ---------- load via env-loader ----------

__host_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$__host_script_dir/lib/env-loader.sh"
aura_env_load host || return $?
unset __host_script_dir

# ---------- summary ----------

cat <<SUMMARY
✓ host-mode env loaded (canonical defaults)
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY
