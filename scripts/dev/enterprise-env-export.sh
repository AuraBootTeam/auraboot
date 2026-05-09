#!/bin/bash
#
# enterprise-env-export.sh — env contract for the enterprise overlay
# stack. Ports are offset +1 from host to allow side-by-side runs:
#   BE 6444 / vite 5174 / BFF 3501 / pg 5433 / redis 6380.
#
# Usage:
#
#     source scripts/dev/enterprise-env-export.sh

__ent_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __ent_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __ent_sourced=1
fi

if [ "$__ent_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: enterprise-env-export.sh must be sourced, not executed.

  Use:    source scripts/dev/enterprise-env-export.sh
HINT
    exit 2
fi

unset __ent_sourced

__ent_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$__ent_script_dir/lib/env-loader.sh"
aura_env_load enterprise || return $?
unset __ent_script_dir

cat <<SUMMARY
✓ enterprise env loaded (ports +1 vs host)
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY
