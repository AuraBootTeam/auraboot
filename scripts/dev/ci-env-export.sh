#!/bin/bash
#
# ci-env-export.sh — env contract for generic CI runners. Defaults are
# host-equivalent; the CI workflow is expected to pre-export overrides
# (e.g. BACKEND_URL when running against a deployed environment).
#
# Usage:
#
#     source scripts/dev/ci-env-export.sh

__ci_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __ci_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __ci_sourced=1
fi

if [ "$__ci_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: ci-env-export.sh must be sourced, not executed.

  Use:    source scripts/dev/ci-env-export.sh
HINT
    exit 2
fi

unset __ci_sourced

__ci_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$__ci_script_dir/lib/env-loader.sh"
aura_env_load ci || return $?
unset __ci_script_dir

cat <<SUMMARY
✓ ci env loaded
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY
