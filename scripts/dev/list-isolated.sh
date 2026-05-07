#!/bin/bash
#
# List all isolated dev stacks currently running.
#
# Implements P0 #4 of docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md.
#
# Output: tabular summary of every running compose project whose name
# starts with `auraboot-`. For each, shows slug + uptime + the host port
# mappings looked up from the .aura-stack/<slug>.env file (if present).
#
# Usage:
#   scripts/dev/list-isolated.sh
#   scripts/dev/list-isolated.sh --quiet   # just slug names, machine-readable

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

QUIET=0

for arg in "$@"; do
    case "$arg" in
        --quiet|-q) QUIET=1 ;;
        --help|-h)
            cat <<USAGE
Usage: $0 [--quiet] [--help]

Lists running isolated stacks (compose projects matching auraboot-*).
With --quiet, prints just the slug per line — handy for scripting.
USAGE
            exit 0
            ;;
        *) echo "ERROR: unknown argument: $arg" >&2; exit 2 ;;
    esac
done

# `docker compose ls --filter` doesn't accept name filters in all versions, so
# pull the full list and filter in shell. JSON output keeps things parseable
# without requiring jq (we use awk/grep so first-time devs aren't blocked).
RAW="$(docker compose ls --all --format json 2>/dev/null || echo "[]")"

# Quick sanity: empty array → nothing to show.
if [ -z "$RAW" ] || [ "$RAW" = "[]" ]; then
    if [ "$QUIET" != "1" ]; then
        echo "No isolated stacks running."
    fi
    exit 0
fi

# Parse JSON without jq: each entry is on its own line in --format json's
# pretty-printed output, but compose may emit single-line JSON. Use a
# minimal Python fallback if available; otherwise fall back to grep/sed.
parse_projects() {
    if command -v python3 >/dev/null 2>&1; then
        python3 -c '
import json, sys
data = json.loads(sys.stdin.read())
for entry in data:
    name = entry.get("Name", "")
    status = entry.get("Status", "")
    if name.startswith("auraboot-"):
        print(f"{name}\t{status}")
'
    else
        # Fallback: grep Name + Status pairs (best-effort).
        echo "$RAW" | tr ',' '\n' | grep -E '"(Name|Status)"' \
            | sed -E 's/.*"(Name|Status)":"([^"]+)".*/\1=\2/' \
            | awk '
                /^Name=auraboot-/ { name=substr($0,6) }
                /^Status=/ { status=substr($0,8); if (name) { printf "%s\t%s\n", name, status; name="" } }
            '
    fi
}

ROWS="$(printf '%s' "$RAW" | parse_projects | sort)"

if [ -z "$ROWS" ]; then
    if [ "$QUIET" != "1" ]; then
        echo "No isolated stacks running."
    fi
    exit 0
fi

if [ "$QUIET" = "1" ]; then
    printf '%s\n' "$ROWS" | awk -F'\t' '{ sub(/^auraboot-/,"",$1); print $1 }'
    exit 0
fi

# ---------- pretty-print ----------

printf '%-26s %-12s %-8s %-7s %-7s %-7s %-9s %-7s\n' \
    "STACK" "STATUS" "PG" "BE" "VITE" "BFF" "REDIS" "OFFSET"
printf '%-26s %-12s %-8s %-7s %-7s %-7s %-9s %-7s\n' \
    "--------------------------" "------------" "--------" "-------" "-------" "-------" "---------" "-------"

while IFS=$'\t' read -r project status; do
    slug="${project#auraboot-}"
    pg="-" be="-" vite="-" bff="-" redis="-" offset="-"
    env_file="$STACK_DIR/${slug}.env"
    if [ -f "$env_file" ]; then
        # shellcheck disable=SC1090
        source "$env_file"
        pg="${PG_PORT:-?}"
        be="${BE_PORT:-?}"
        vite="${VITE_PORT:-?}"
        bff="${BFF_PORT:-?}"
        redis="${REDIS_PORT:-?}"
        offset="${OFFSET:-?}"
        # Reset for next iteration so a missing file doesn't leak previous values.
        unset PG_PORT BE_PORT VITE_PORT BFF_PORT REDIS_PORT OFFSET
    fi
    # Truncate status string to fit (first word usually carries the signal).
    short_status="${status%% *}"
    printf '%-26s %-12s %-8s %-7s %-7s %-7s %-9s %-7s\n' \
        "$slug" "$short_status" "$pg" "$be" "$vite" "$bff" "$redis" "$offset"
done <<< "$ROWS"
