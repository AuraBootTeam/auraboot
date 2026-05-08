#!/bin/bash
#
# r2-env-export.sh — single-line `source` to set up the env for an
# isolated docker stack. Resolves ports from .aura-stack/<slug>.env
# and exports the full env contract that today's tests + scripts
# read (see docs/guides/r2-isolated-stack-sop.md for the contract).
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
#
# Defaults preserve host-mode for any var that was already set; you
# can pre-export PGPASSWORD or BACKEND_URL to override.
#
# This is a `source` script — running it as a sub-process won't
# propagate the exports. The block below detects sub-process invocation
# and prints a hint instead of failing silently.

# ---------- detect source vs exec ----------

# In bash, BASH_SOURCE[0] != $0 implies we were sourced; in zsh, the
# variable doesn't exist when sourced. Best-effort cross-shell detect:
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

# ---------- resolve project root ----------

# When sourced, BASH_SOURCE[0] is this script's path (bash) or %x is the
# arg (zsh). Resolve to repo root either way.
__r2_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
__r2_project_root="$(cd "$__r2_script_dir/../.." && pwd)"

# ---------- locate the per-stack env file ----------

__r2_slug="${1-}"
if [ -z "$__r2_slug" ]; then
    # Auto-derive from current branch, same logic as start-isolated.sh.
    __r2_branch="$(git -C "$__r2_project_root" branch --show-current 2>/dev/null || echo)"
    if [ -n "$__r2_branch" ]; then
        # normalize: lowercase, /_→-, strip non [a-z0-9-], collapse runs, trim, ≤24 chars
        __r2_slug="$(printf '%s' "$__r2_branch" \
            | tr '[:upper:]' '[:lower:]' \
            | tr '/_' '--' \
            | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
            | cut -c1-24)"
    fi
fi

if [ -z "$__r2_slug" ]; then
    echo "r2-env-export: slug not provided and could not auto-derive — pass one explicitly." >&2
    return 1
fi

__r2_env_file="$__r2_project_root/.aura-stack/${__r2_slug}.env"
if [ ! -f "$__r2_env_file" ]; then
    cat >&2 <<HINT
r2-env-export: per-stack env file not found at
  $__r2_env_file

Bring the stack up first:
  cd $__r2_project_root
  scripts/dev/start-isolated.sh --slug=$__r2_slug
HINT
    return 1
fi

# ---------- load + export ----------

# shellcheck disable=SC1090
. "$__r2_env_file"

export PG_HOST="${PG_HOST:-localhost}"
export PG_USER="${PG_USER:-auraboot}"
export PG_DB="${PG_DB:-aura_boot}"
export PGPASSWORD="${PGPASSWORD:-auraboot_dev}"
export BE_PORT VITE_PORT BFF_PORT PG_PORT REDIS_PORT
export BACKEND_URL="${BACKEND_URL:-http://localhost:$BE_PORT}"
export PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:-http://localhost:$VITE_PORT}"
export BFF_URL="${BFF_URL:-http://localhost:$BFF_PORT}"

# ---------- summary ----------

cat <<SUMMARY
✓ r2 stack '$__r2_slug' env loaded (sourced from $__r2_env_file)
  BACKEND_URL          = $BACKEND_URL
  PLAYWRIGHT_BASE_URL  = $PLAYWRIGHT_BASE_URL
  BFF_URL              = $BFF_URL
  PG (host:port/db)    = $PG_HOST:$PG_PORT/$PG_DB (user $PG_USER)
SUMMARY

unset __r2_script_dir __r2_project_root __r2_slug __r2_branch __r2_env_file
