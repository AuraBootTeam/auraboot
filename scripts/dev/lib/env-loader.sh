#!/bin/bash
# shellcheck shell=bash
#
# env-loader.sh — shell counterpart to web-admin/tests/helpers/environments.ts.
#
# Sourceable library exposing two helpers:
#
#   aura_env_load <profile> [slug]
#       Export BE_PORT / VITE_PORT / BFF_PORT / PG_PORT / REDIS_PORT /
#       PG_HOST / PG_USER / PG_DB / BACKEND_URL / PLAYWRIGHT_BASE_URL /
#       BFF_URL based on the named profile. process-env wins over defaults
#       (so a previously-sourced env stays in effect — same contract as
#       loadEnv() in TypeScript).
#       Profiles: host | r2 | ga-e2e | ci | enterprise.
#       For r2, an optional `slug` reads `.aura-stack/<slug>.env`.
#
#   aura_env_to_json
#       Print a single-line JSON summary of the currently-exported env
#       (handy for debugging or piping into jq).
#
# Source — never exec — this file. The wrappers (host-env-export.sh,
# r2-env-export.sh, etc.) detect mis-invocation themselves.

aura_env__defaults() {
    local profile="$1"
    case "$profile" in
        host)
            __AURA_BE_PORT_DEF=6443
            __AURA_VITE_PORT_DEF=5173
            __AURA_BFF_PORT_DEF=3500
            __AURA_PG_PORT_DEF=5432
            __AURA_REDIS_PORT_DEF=6379
            __AURA_PG_HOST_DEF=localhost
            __AURA_PG_USER_DEF=auraboot
            __AURA_PG_DB_DEF=aura_boot
            ;;
        r2)
            __AURA_BE_PORT_DEF=6443
            __AURA_VITE_PORT_DEF=5173
            __AURA_BFF_PORT_DEF=3500
            __AURA_PG_PORT_DEF=5432
            __AURA_REDIS_PORT_DEF=6379
            __AURA_PG_HOST_DEF=localhost
            __AURA_PG_USER_DEF=auraboot
            __AURA_PG_DB_DEF=aura_boot
            ;;
        ga-e2e)
            __AURA_BE_PORT_DEF=6443
            __AURA_VITE_PORT_DEF=5173
            __AURA_BFF_PORT_DEF=3500
            __AURA_PG_PORT_DEF=5432
            __AURA_REDIS_PORT_DEF=6379
            __AURA_PG_HOST_DEF=postgres
            __AURA_PG_USER_DEF=auraboot
            __AURA_PG_DB_DEF=aura_boot
            ;;
        ci)
            __AURA_BE_PORT_DEF=6443
            __AURA_VITE_PORT_DEF=5173
            __AURA_BFF_PORT_DEF=3500
            __AURA_PG_PORT_DEF=5432
            __AURA_REDIS_PORT_DEF=6379
            __AURA_PG_HOST_DEF=localhost
            __AURA_PG_USER_DEF=auraboot
            __AURA_PG_DB_DEF=aura_boot
            ;;
        enterprise)
            __AURA_BE_PORT_DEF=6444
            __AURA_VITE_PORT_DEF=5174
            __AURA_BFF_PORT_DEF=3501
            __AURA_PG_PORT_DEF=5433
            __AURA_REDIS_PORT_DEF=6380
            __AURA_PG_HOST_DEF=localhost
            __AURA_PG_USER_DEF=auraboot
            __AURA_PG_DB_DEF=aura_boot
            ;;
        *)
            echo "aura_env_load: unknown profile '$profile' (expected host|r2|ga-e2e|ci|enterprise)" >&2
            return 2
            ;;
    esac
}

aura_env_load() {
    local profile="${1-host}"
    local slug="${2-}"

    aura_env__defaults "$profile" || return $?

    # r2 profile: pull ports from per-stack env file (auto-derive slug from
    # branch when not given, mirroring r2-env-export.sh).
    if [ "$profile" = "r2" ]; then
        if [ -z "$slug" ]; then
            local branch
            branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo)"
            if [ -n "$branch" ]; then
                slug="$(printf '%s' "$branch" \
                    | tr '[:upper:]' '[:lower:]' \
                    | tr '/_' '--' \
                    | sed -E 's/[^a-z0-9-]/-/g; s/-+/-/g; s/^-//; s/-$//' \
                    | cut -c1-24)"
            fi
        fi
        if [ -z "$slug" ]; then
            echo "aura_env_load(r2): slug not provided and could not auto-derive" >&2
            return 1
        fi
        local repo_root
        repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
        local env_file="$repo_root/.aura-stack/${slug}.env"
        if [ ! -f "$env_file" ]; then
            echo "aura_env_load(r2): missing $env_file — bring stack up first" >&2
            return 1
        fi
        # shellcheck disable=SC1090
        . "$env_file"
    fi

    export BE_PORT="${BE_PORT:-$__AURA_BE_PORT_DEF}"
    export VITE_PORT="${VITE_PORT:-$__AURA_VITE_PORT_DEF}"
    export BFF_PORT="${BFF_PORT:-$__AURA_BFF_PORT_DEF}"
    export PG_PORT="${PG_PORT:-$__AURA_PG_PORT_DEF}"
    export REDIS_PORT="${REDIS_PORT:-$__AURA_REDIS_PORT_DEF}"
    export PG_HOST="${PG_HOST:-$__AURA_PG_HOST_DEF}"
    export PG_USER="${PG_USER:-$__AURA_PG_USER_DEF}"
    export PG_DB="${PG_DB:-$__AURA_PG_DB_DEF}"
    export PGPASSWORD="${PGPASSWORD:-auraboot_dev}"
    export BACKEND_URL="${BACKEND_URL:-http://localhost:$BE_PORT}"
    export PLAYWRIGHT_BASE_URL="${PLAYWRIGHT_BASE_URL:-http://localhost:$VITE_PORT}"
    export BFF_URL="${BFF_URL:-http://localhost:$BFF_PORT}"

    export AURA_ENV_PROFILE="$profile"

    unset __AURA_BE_PORT_DEF __AURA_VITE_PORT_DEF __AURA_BFF_PORT_DEF \
        __AURA_PG_PORT_DEF __AURA_REDIS_PORT_DEF \
        __AURA_PG_HOST_DEF __AURA_PG_USER_DEF __AURA_PG_DB_DEF
}

aura_env_to_json() {
    cat <<JSON
{"profile":"${AURA_ENV_PROFILE:-unset}","ports":{"be":"${BE_PORT:-}","vite":"${VITE_PORT:-}","bff":"${BFF_PORT:-}","pg":"${PG_PORT:-}","redis":"${REDIS_PORT:-}"},"urls":{"backend":"${BACKEND_URL:-}","base":"${PLAYWRIGHT_BASE_URL:-}","bff":"${BFF_URL:-}"},"pg":{"host":"${PG_HOST:-}","port":"${PG_PORT:-}","user":"${PG_USER:-}","db":"${PG_DB:-}"}}
JSON
}
