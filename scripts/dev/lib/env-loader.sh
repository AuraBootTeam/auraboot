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
#       BFF_URL / PW_* artifact paths based on the named profile. process-env
#       wins over defaults (so a previously-sourced env stays in effect — same
#       contract as loadEnv() in TypeScript).
#       Profiles: host | r2 | ga-e2e | ci | enterprise.
#       For r2, an optional `slug` reads the global env registry export at
#       $AURA_ENV_REGISTRY_ROOT/envs/<slug>/exports.env.
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

aura_env__mono_root() {
    local repo_root="${1:-$(pwd)}"
    local common_dir
    common_dir="$(git -C "$repo_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    if [ -n "$common_dir" ]; then
        local git_parent
        git_parent="$(dirname "$common_dir")"
        dirname "$git_parent"
        return
    fi

    local top_level
    top_level="$(git -C "$repo_root" rev-parse --show-toplevel 2>/dev/null || pwd)"
    dirname "$top_level"
}

aura_env__registry_root() {
    local repo_root="${1:-$(pwd)}"
    if [ -n "${AURA_ENV_REGISTRY_ROOT:-}" ]; then
        echo "$AURA_ENV_REGISTRY_ROOT"
        return
    fi
    echo "$(aura_env__mono_root "$repo_root")/.aura"
}

aura_env_load() {
    local profile="${1-host}"
    local slug="${2-}"
    local __preset_AURA_ENV_REGISTRY_ROOT="${AURA_ENV_REGISTRY_ROOT-}"
    local __preset_AURA_ENV_ROOT="${AURA_ENV_ROOT-}"
    local __preset_AURA_ENV_AUTH_ROOT="${AURA_ENV_AUTH_ROOT-}"
    local __preset_PW_STORAGE_DIR="${PW_STORAGE_DIR-}"
    local __preset_PW_ADMIN_STORAGE_STATE="${PW_ADMIN_STORAGE_STATE-}"
    local __preset_PW_OPERATOR_STORAGE_STATE="${PW_OPERATOR_STORAGE_STATE-}"
    local __preset_PW_VIEWER_STORAGE_STATE="${PW_VIEWER_STORAGE_STATE-}"

    aura_env__defaults "$profile" || return $?

    # r2 profile: pull ports from the global registry (auto-derive slug from
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
        local registry_root
        registry_root="${__preset_AURA_ENV_REGISTRY_ROOT:-$(aura_env__registry_root "$repo_root")}"
        local registry_exports="$registry_root/envs/$slug/exports.env"
        if [ -f "$registry_exports" ]; then
            # shellcheck disable=SC1090
            . "$registry_exports"
        else
            echo "aura_env_load(r2): missing $registry_exports — start or register the env first" >&2
            return 1
        fi
        export AURA_ENV_SLUG="${SLUG:-$slug}"
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
    export AURA_ENV_SLUG="${AURA_ENV_SLUG:-$slug}"
    if [ -n "${AURA_ENV_SLUG:-}" ]; then
        local repo_root_for_registry
        repo_root_for_registry="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
        local default_registry_root
        default_registry_root="$(aura_env__registry_root "$repo_root_for_registry")"
        export AURA_ENV_REGISTRY_ROOT="${__preset_AURA_ENV_REGISTRY_ROOT:-${AURA_ENV_REGISTRY_ROOT:-$default_registry_root}}"
        export AURA_ENV_ROOT="${__preset_AURA_ENV_ROOT:-${AURA_ENV_ROOT:-$AURA_ENV_REGISTRY_ROOT/envs/$AURA_ENV_SLUG}}"
        export AURA_ENV_AUTH_ROOT="${__preset_AURA_ENV_AUTH_ROOT:-${AURA_ENV_AUTH_ROOT:-$AURA_ENV_ROOT/auth}}"
        export PW_E2E_RUN_ID="${PW_E2E_RUN_ID:-$(date -u +"%Y%m%dT%H%M%SZ")}"
        export PW_E2E_RUN_ROOT="${PW_E2E_RUN_ROOT:-test-results/runs/$AURA_ENV_SLUG/$PW_E2E_RUN_ID}"
        export PW_ARTIFACT_DIR="${PW_ARTIFACT_DIR:-$PW_E2E_RUN_ROOT/artifacts}"
        export PW_REPORT_DIR="${PW_REPORT_DIR:-$PW_E2E_RUN_ROOT/html-report}"
        export PW_RESULTS_JSON="${PW_RESULTS_JSON:-$PW_E2E_RUN_ROOT/results.json}"
        export PW_STORAGE_DIR="${__preset_PW_STORAGE_DIR:-$AURA_ENV_AUTH_ROOT}"
        export PW_ADMIN_STORAGE_STATE="${__preset_PW_ADMIN_STORAGE_STATE:-$PW_STORAGE_DIR/admin.json}"
        export PW_OPERATOR_STORAGE_STATE="${__preset_PW_OPERATOR_STORAGE_STATE:-$PW_STORAGE_DIR/operator.json}"
        export PW_VIEWER_STORAGE_STATE="${__preset_PW_VIEWER_STORAGE_STATE:-$PW_STORAGE_DIR/viewer.json}"
    fi

    unset __AURA_BE_PORT_DEF __AURA_VITE_PORT_DEF __AURA_BFF_PORT_DEF \
        __AURA_PG_PORT_DEF __AURA_REDIS_PORT_DEF \
        __AURA_PG_HOST_DEF __AURA_PG_USER_DEF __AURA_PG_DB_DEF
}

aura_env_to_json() {
    cat <<JSON
{"profile":"${AURA_ENV_PROFILE:-unset}","slug":"${AURA_ENV_SLUG:-}","ports":{"be":"${BE_PORT:-}","vite":"${VITE_PORT:-}","bff":"${BFF_PORT:-}","pg":"${PG_PORT:-}","redis":"${REDIS_PORT:-}"},"urls":{"backend":"${BACKEND_URL:-}","base":"${PLAYWRIGHT_BASE_URL:-}","bff":"${BFF_URL:-}"},"pg":{"host":"${PG_HOST:-}","port":"${PG_PORT:-}","user":"${PG_USER:-}","db":"${PG_DB:-}"},"registry":{"root":"${AURA_ENV_REGISTRY_ROOT:-}","envRoot":"${AURA_ENV_ROOT:-}","authRoot":"${AURA_ENV_AUTH_ROOT:-}"},"playwright":{"runRoot":"${PW_E2E_RUN_ROOT:-}","artifacts":"${PW_ARTIFACT_DIR:-}","report":"${PW_REPORT_DIR:-}","results":"${PW_RESULTS_JSON:-}","storage":"${PW_STORAGE_DIR:-}","adminStorage":"${PW_ADMIN_STORAGE_STATE:-}","operatorStorage":"${PW_OPERATOR_STORAGE_STATE:-}","viewerStorage":"${PW_VIEWER_STORAGE_STATE:-}"}}
JSON
}
