#!/bin/bash
#
# r2-env-export.sh — single-line `source` to set up the env for an
# isolated docker stack. Delegates to lib/env-loader.sh::aura_env_load r2,
# which resolves ports from .aura-stack/<slug>.env.
#
# Usage:
#
#     source scripts/dev/r2-env-export.sh r2     # named slug
#     source scripts/dev/r2-env-export.sh        # auto-detect from
#                                                # current branch
#
# After sourcing, every subsequent command in this shell sees:
#   - BE_PORT / VITE_PORT / BFF_PORT / PG_PORT / REDIS_PORT
#     (from .aura-stack/<slug>.env)
#   - PG_HOST=localhost / PG_USER=auraboot / PG_DB=aura_boot
#   - PGPASSWORD=auraboot_dev (only if not already set)
#   - BACKEND_URL=http://localhost:$BE_PORT
#   - PLAYWRIGHT_BASE_URL=http://localhost:$VITE_PORT
#   - BFF_URL=http://localhost:$BFF_PORT
#
# Defaults preserve already-set values; pre-export PGPASSWORD or BACKEND_URL
# to override.

# ---------- detect source vs exec ----------

__r2_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __r2_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __r2_sourced=1
fi

if [ "$__r2_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: r2-env-export.sh must be sourced, not executed.

  Use:    source scripts/dev/r2-env-export.sh [slug]
  Not:    bash scripts/dev/r2-env-export.sh [slug]
HINT
    exit 2
fi

unset __r2_sourced

# ---------- load via env-loader ----------

__r2_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$__r2_script_dir/lib/env-loader.sh"
aura_env_load r2 "${1-}" || return $?

# ---------- summary ----------

cat <<SUMMARY
✓ r2 stack env loaded (slug=${AURA_ENV_PROFILE:-r2})
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY

unset __r2_script_dir
