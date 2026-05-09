#!/bin/bash
#
# ga-e2e-env-export.sh — env contract for the GA (GitHub Actions) E2E
# docker stack started via `docker-compose.ga-e2e.override.yml`.
#
# Usage:
#
#     source scripts/dev/ga-e2e-env-export.sh
#
# Identical port layout to host but PG_HOST=postgres so commands run
# inside the docker network resolve the postgres service name. CI jobs
# that exec on the host (not inside a container) can pre-export
# PG_HOST=localhost to override.

__ga_sourced=0
if [ -n "${BASH_SOURCE-}" ] && [ "${BASH_SOURCE[0]-}" != "${0-}" ]; then
    __ga_sourced=1
elif [ -n "${ZSH_EVAL_CONTEXT-}" ] && [[ "$ZSH_EVAL_CONTEXT" == *:file ]]; then
    __ga_sourced=1
fi

if [ "$__ga_sourced" -ne 1 ]; then
    cat <<'HINT'
ERROR: ga-e2e-env-export.sh must be sourced, not executed.

  Use:    source scripts/dev/ga-e2e-env-export.sh
HINT
    exit 2
fi

unset __ga_sourced

__ga_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
# shellcheck disable=SC1091
. "$__ga_script_dir/lib/env-loader.sh"
aura_env_load ga-e2e || return $?
unset __ga_script_dir

cat <<SUMMARY
✓ ga-e2e env loaded
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY
