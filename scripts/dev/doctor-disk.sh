#!/bin/bash
#
# Read-only disk pressure report for AuraBoot Docker/worktree artifacts.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROJECT_PARENT="$(cd "$PROJECT_ROOT/.." && pwd)"
if [ "$(basename "$PROJECT_PARENT")" = ".worktrees" ]; then
    WORKSPACE_ROOT="$(cd "$PROJECT_PARENT/.." && pwd)"
else
    WORKSPACE_ROOT="$PROJECT_PARENT"
fi
AURA_CACHE_ROOT="${AURA_CACHE_ROOT:-$HOME/.cache/auraboot}"
AURA_CONTAINER_CACHE_KEY="${AURA_CONTAINER_CACHE_KEY:-linux}"
AURA_CONTAINER_CACHE_ROOT="${AURA_CONTAINER_CACHE_ROOT:-$AURA_CACHE_ROOT/container-$AURA_CONTAINER_CACHE_KEY}"

run_with_timeout() {
    local seconds="$1"
    shift
    local tmp
    tmp="$(mktemp)"
    "$@" >"$tmp" 2>&1 &
    local pid=$!
    local elapsed=0
    while kill -0 "$pid" >/dev/null 2>&1; do
        if [ "$elapsed" -ge "$seconds" ]; then
            kill "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
            cat "$tmp"
            rm -f "$tmp"
            echo "WARN: command timed out after ${seconds}s: $*" >&2
            return 124
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    local status=0
    wait "$pid" || status=$?
    cat "$tmp"
    rm -f "$tmp"
    return "$status"
}

section() {
    echo ""
    echo "== $1 =="
}

docker_available() {
    command -v docker >/dev/null 2>&1
}

aura_volume_names() {
    docker volume ls --format '{{.Name}}' \
        | grep -E '(^aura|auraboot|ga_e2e|isolated|pnpm|gradle|m2|postgres|redis)' \
        || true
}

compose_project_names() {
    docker compose ls --all --format json 2>/dev/null \
        | python3 -c '
import json, sys
try:
    data = json.loads(sys.stdin.read() or "[]")
except Exception:
    data = []
for entry in data:
    name = entry.get("Name", "")
    if name:
        print(name)
' 2>/dev/null || true
}

section "Workspace"
echo "project: $PROJECT_ROOT"
if [ -d "$WORKSPACE_ROOT/.worktrees" ]; then
    du -sh "$WORKSPACE_ROOT/.worktrees" 2>/dev/null || true
else
    echo "no workspace .worktrees directory"
fi
du -sh "$PROJECT_ROOT/.aura-stack" 2>/dev/null || true
du -sh "$PROJECT_ROOT/test-results" 2>/dev/null || true
du -sh "$PROJECT_ROOT/web-admin/test-results" 2>/dev/null || true
du -sh "$PROJECT_ROOT/web-admin/tests/storage" 2>/dev/null || true

section "E2E Artifacts"
RUNS_ROOT="$PROJECT_ROOT/web-admin/test-results/runs"
STORAGE_ROOT="$PROJECT_ROOT/web-admin/tests/storage"
if [ -d "$RUNS_ROOT" ]; then
    echo "run root: $RUNS_ROOT"
    du -sh "$RUNS_ROOT" 2>/dev/null || true
    find "$RUNS_ROOT" -mindepth 2 -maxdepth 2 -type d -print0 2>/dev/null \
        | xargs -0 du -sh 2>/dev/null \
        | sort -hr \
        | head -20 || true
else
    echo "run root does not exist yet: $RUNS_ROOT"
fi
if [ -d "$STORAGE_ROOT" ]; then
    echo "storage root: $STORAGE_ROOT"
    du -sh "$STORAGE_ROOT" 2>/dev/null || true
    find "$STORAGE_ROOT" -mindepth 2 -maxdepth 2 -type d -print0 2>/dev/null \
        | xargs -0 du -sh 2>/dev/null \
        | sort -hr \
        | head -20 || true
else
    echo "storage root does not exist yet: $STORAGE_ROOT"
fi
echo "artifact cleanup dry-run:"
"$SCRIPT_DIR/cleanup-artifacts.sh" --days=14 2>/dev/null | sed -n '1,24p' || true

section "Host Shared Caches"
echo "cache root: $AURA_CACHE_ROOT"
echo "container cache root: $AURA_CONTAINER_CACHE_ROOT"
if [ -d "$AURA_CACHE_ROOT" ]; then
    du -sh "$AURA_CACHE_ROOT" 2>/dev/null || true
else
    echo "host cache root does not exist yet"
fi
if [ -d "$AURA_CONTAINER_CACHE_ROOT" ]; then
    for dir in gradle m2 pnpm-store ms-playwright; do
        du -sh "$AURA_CONTAINER_CACHE_ROOT/$dir" 2>/dev/null || true
    done
else
    echo "container cache root does not exist yet"
fi

section "Docker System"
if docker_available; then
    run_with_timeout 8 docker system df || true
else
    echo "docker not found"
fi

section "Docker VM Filesystem"
if docker_available; then
    if docker image inspect redis:7-alpine >/dev/null 2>&1; then
        run_with_timeout 8 docker run --rm --pull=never redis:7-alpine sh -c 'df -h /data 2>/dev/null || df -h /' || true
    else
        echo "redis:7-alpine image not cached; skipping Docker VM df probe"
    fi
else
    echo "docker not found"
fi

section "Aura Docker Volumes"
if docker_available; then
    run_with_timeout 8 aura_volume_names || true
else
    echo "docker not found"
fi

section "Aura Docker Volume Sizes"
if docker_available; then
    if run_with_timeout 12 docker system df -v >/tmp/aura-docker-df-v.$$ 2>/dev/null; then
        awk '
            BEGIN { in_volumes=0 }
            /^Local Volumes space usage:/ { in_volumes=1; next }
            /^Build cache usage:/ { in_volumes=0 }
            in_volumes && ($1 ~ /^(aura|auraboot|ga_e2e|isolated|pnpm|gradle|m2|postgres|redis)/) {
                print
            }
        ' /tmp/aura-docker-df-v.$$ || true
    else
        echo "docker system df -v unavailable or timed out"
    fi
    rm -f /tmp/aura-docker-df-v.$$
else
    echo "docker not found"
fi

section "Stale Stack Candidates"
if docker_available; then
    volumes_file="$(mktemp)"
    projects_file="$(mktemp)"
    aura_volume_names > "$volumes_file" || true
    compose_project_names > "$projects_file" || true
    awk '
        FNR == NR { projects[$1]=1; next }
        {
            name=$1
            project=""
            if (name ~ /^auraboot-/) {
                project=name
                sub(/_(backend_data|postgres_data|redis_data|minio_data|isolated_node_modules|isolated_web_admin_node_modules|mobile_e2e_postgres_data|mobile_e2e_redis_data)$/, "", project)
            }
            if (project != "" && !(project in projects)) {
                stale[project]=1
            }
        }
        END {
            count=0
            for (project in stale) {
                print project
                count++
            }
            if (count == 0) {
                print "none"
            }
        }
    ' "$projects_file" "$volumes_file"
    rm -f "$volumes_file" "$projects_file"
else
    echo "docker not found"
fi

section "Aura Docker Images"
if docker_available; then
    run_with_timeout 8 docker image ls --format '{{.Repository}}:{{.Tag}}\t{{.Size}}\t{{.ID}}' \
        | grep -E '(^aura|auraboot|pgvector|redis|playwright|eclipse-temurin)' \
        || true
else
    echo "docker not found"
fi

section "Notes"
cat <<'NOTES'
This script is read-only. It does not prune images, builder cache, or volumes.
Use targeted cleanup for stale stack volumes; avoid docker system prune -a --volumes
unless you intentionally want to drop warm caches.
NOTES
