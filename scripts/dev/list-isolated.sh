#!/usr/bin/env bash
#
# list-isolated.sh — list all per-worktree isolated docker stacks.
#
# Walks .aura-stack/*.env files and cross-references with `docker
# compose ls --filter name=auraboot-` to print a status table:
#
#   SLUG           OFFSET  PG    BE    VITE  BFF   REDIS  STATUS    CREATED
#   <name>         <N>     <p>   <p>   <p>   <p>   <p>    running   <utc>
#
# Spec: docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md
# (P0 #4)
#
# Note: avoids bash 4-only features (associative arrays) so it runs on
# macOS default bash 3.2.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENV_DIR="${REPO_ROOT}/.aura-stack"

usage() {
    cat <<'EOF'
Usage: list-isolated.sh [--help]

Lists all isolated docker stacks tracked under .aura-stack/. Status is
"running" when `docker compose ls` shows the project active, "stopped"
when the env file exists but compose reports nothing for that project.

Stacks running without an env file (e.g. ga-e2e via the legacy override
file) are listed as a separate group below.
EOF
}

for arg in "$@"; do
    case "$arg" in
        --help|-h) usage; exit 0 ;;
        *)
            printf 'ERROR: unknown argument: %s\n' "$arg" >&2
            usage >&2
            exit 2
            ;;
    esac
done

# ─── Collect running compose projects (newline-separated list) ──────────
running_projects=""
if compose_ls_json="$(docker compose ls --filter name=auraboot- --format json 2>/dev/null)"; then
    if command -v jq >/dev/null 2>&1; then
        running_projects="$(printf '%s' "$compose_ls_json" | jq -r '.[].Name')"
    else
        running_projects="$(printf '%s' "$compose_ls_json" \
                            | grep -oE '"Name":"[^"]+"' \
                            | sed -E 's/"Name":"([^"]+)"/\1/')"
    fi
fi

is_running() {
    local p="$1"
    [ -n "$running_projects" ] || return 1
    printf '%s\n' "$running_projects" | grep -Fxq "$p"
}

# ─── Header ─────────────────────────────────────────────────────────────
printf '%-26s %-7s %-6s %-6s %-6s %-6s %-6s %-9s %s\n' \
    "SLUG" "OFFSET" "PG" "BE" "VITE" "BFF" "REDIS" "STATUS" "CREATED"
printf '%-26s %-7s %-6s %-6s %-6s %-6s %-6s %-9s %s\n' \
    "----" "------" "--" "--" "----" "---" "-----" "------" "-------"

# ─── Walk .aura-stack/*.env ─────────────────────────────────────────────
shopt -s nullglob
env_files=("$ENV_DIR"/*.env)
shopt -u nullglob

# Newline-separated list of projects we covered via env files.
tracked_projects=""

if [ "${#env_files[@]}" -eq 0 ]; then
    printf '(no .aura-stack/*.env files — start one via scripts/dev/start-isolated.sh)\n'
else
    for env_file in "${env_files[@]}"; do
        # Reset upfront so missing keys don't carry over from a previous file.
        unset COMPOSE_PROJECT_NAME AURA_STACK_SLUG AURA_STACK_OFFSET AURA_STACK_CREATED_AT
        unset PG_PORT BE_PORT VITE_PORT BFF_PORT REDIS_PORT
        # shellcheck source=/dev/null
        source <(grep -E '^(COMPOSE_PROJECT_NAME|AURA_STACK_SLUG|AURA_STACK_OFFSET|AURA_STACK_CREATED_AT|PG_PORT|BE_PORT|VITE_PORT|BFF_PORT|REDIS_PORT)=' "$env_file" || true)

        slug="${AURA_STACK_SLUG:-$(basename "$env_file" .env)}"
        offset="${AURA_STACK_OFFSET:-?}"
        project="${COMPOSE_PROJECT_NAME:-auraboot-${slug}}"
        pg="${PG_PORT:-?}"
        be="${BE_PORT:-?}"
        vite="${VITE_PORT:-?}"
        bff="${BFF_PORT:-?}"
        redis="${REDIS_PORT:-?}"
        created="${AURA_STACK_CREATED_AT:-?}"

        if is_running "$project"; then
            status="running"
            tracked_projects="${tracked_projects}${project}
"
        else
            status="stopped"
        fi

        printf '%-26s %-7s %-6s %-6s %-6s %-6s %-6s %-9s %s\n' \
            "$slug" "$offset" "$pg" "$be" "$vite" "$bff" "$redis" "$status" "$created"
    done
fi

# ─── Stacks running without an env file (e.g. legacy ga-e2e) ────────────
if [ -n "$running_projects" ]; then
    orphan=""
    while IFS= read -r p; do
        [ -z "$p" ] && continue
        if ! printf '%s' "$tracked_projects" | grep -Fxq "$p"; then
            orphan="${orphan}${p}
"
        fi
    done <<< "$running_projects"

    if [ -n "$orphan" ]; then
        echo
        echo "Untracked compose projects matching auraboot-* (no .aura-stack env file):"
        printf '%s' "$orphan" | sed '/^$/d; s/^/  /'
    fi
fi
