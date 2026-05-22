#!/bin/bash
# shellcheck shell=bash
#
# Sourceable health helpers for per-worktree dev stacks.

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    echo "Usage: source $0" >&2
    exit 2
fi

aura_dev_health_targets() {
    printf '%s\n' \
        "backend ${BACKEND_URL:-http://localhost:${BE_PORT:-6443}}/actuator/health" \
        "frontend ${PLAYWRIGHT_BASE_URL:-http://localhost:${VITE_PORT:-5173}}" \
        "bff ${BFF_URL:-http://localhost:${BFF_PORT:-3500}}/health"
}

aura_dev_check_http() {
    local name="$1"
    local url="$2"
    if curl -fsS "$url" >/dev/null 2>&1; then
        echo "ok $name $url"
    else
        echo "fail $name $url" >&2
        return 1
    fi
}
