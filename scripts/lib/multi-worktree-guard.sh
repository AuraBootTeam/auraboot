#!/bin/bash
#
# Multi-worktree pre-flight guard
# (P0 #5 of docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md)
#
# Purpose:
#   Refuse to run destructive / shared-singleton operations (reset-db.sh,
#   oss-reset-and-init.sh, publishToMavenLocal, bootRun, pnpm dev:full,
#   full Playwright E2E) when ≥ 2 git worktrees of this repo are present and
#   the current command is still targeting the shared host runtime.
#
# Why:
#   Triggered by 2026-05-07 incident — feat/env-layering-poc worktree ran
#   reset-db.sh against the shared `aura_boot` DB, rebuilt the schema with
#   env_id NOT NULL, host backend still running with stale m2 jar (no envId
#   field) → 104 POST /api/pages failures. The fundamental issue: 5 shared
#   singletons (Postgres / ~/.m2 / :6443 / :5174:3501 / Redis) cannot be
#   safely shared between concurrent worktrees.
#
# Decision:
#   Default: refuse with message pointing at isolated docker stack workflow.
#   Allow: commands that already target an isolated runtime (PG_PORT != 5432
#          or AURA_ENV_PROFILE=r2) pass, because they are not writing the
#          shared host DB. publishToMavenLocal has a matching Gradle-side
#          allow path when -Dmaven.repo.local points outside ~/.m2/repository.
#   Escape: FORCE_HOST=1 bypasses the guard but appends one line to
#           ~/.aura/host-override.log so owner can audit which scenarios
#           rely on the escape hatch.
#
# Usage from a script:
#   source "$(dirname "$0")/lib/multi-worktree-guard.sh"
#   aura_multi_worktree_guard "reset-db.sh"
#
# Function returns 0 on pass / silent-pass-via-FORCE_HOST, non-zero on refuse.

aura_multi_worktree_guard() {
    local operation_name="${1:-unknown-operation}"
    local worktree_count

    # `git worktree list` prints one line per worktree; the main checkout
    # plus any `git worktree add` paths. Count them.
    worktree_count=$(git worktree list 2>/dev/null | wc -l | tr -d ' ')

    if [ -z "$worktree_count" ] || [ "$worktree_count" -lt 2 ]; then
        # Single-worktree case — host stack is safe.
        return 0
    fi

    case "$operation_name" in
        reset-db.sh|oss-reset-and-init.sh)
            if [ "${AURA_ENV_PROFILE:-}" = "r2" ] || [ "${PG_PORT:-5432}" != "5432" ] || [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then
                return 0
            fi
            ;;
    esac

    if [ "${FORCE_HOST:-}" = "1" ]; then
        # Escape hatch: caller has explicitly opted in. Log + warn + pass.
        local log_dir="${HOME}/.aura"
        local log_file="${log_dir}/host-override.log"
        mkdir -p "$log_dir"
        local timestamp
        timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
        local current_branch
        current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "detached")
        local current_worktree
        current_worktree=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
        printf '%s\toperation=%s\tbranch=%s\tworktree=%s\tworktree_count=%d\n' \
            "$timestamp" \
            "$operation_name" \
            "$current_branch" \
            "$current_worktree" \
            "$worktree_count" \
            >> "$log_file"
        echo "" >&2
        echo "WARN: FORCE_HOST=1 — bypassing multi-worktree guard for '$operation_name'." >&2
        echo "      Detected $worktree_count active worktrees; logged to $log_file" >&2
        echo "" >&2
        return 0
    fi

    # Refuse path: print actionable error to stderr, exit non-zero.
    cat >&2 <<EOF

ERROR: refusing to run '$operation_name' — detected $worktree_count active git worktrees.

Multi-worktree mode requires an isolated runtime so shared singletons
(Postgres / m2 publish output / :6443 / :5174:3501 / Redis) don't collide.

Options:
  1) Use isolated infra for daily host-mode development:
       scripts/dev/start-dev-infra.sh --slug=<topic>
       source scripts/dev/r2-env-export.sh <topic>

  2) Use the full isolated stack for E2E / merge verification:
       scripts/dev/start-isolated.sh --slug=<topic>

  3) Stop the other worktrees' host stacks first, then re-run.

  4) Override (only if you know other worktrees are dormant):
       FORCE_HOST=1 $operation_name
     This appends one audit line to ~/.aura/host-override.log.

Active worktrees:
EOF
    git worktree list 2>/dev/null | sed 's/^/  /' >&2
    echo "" >&2
    return 1
}

# Allow this script to be invoked directly for ad-hoc checking:
#   bash scripts/lib/multi-worktree-guard.sh check <op-name>
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    case "${1:-}" in
        check)
            aura_multi_worktree_guard "${2:-manual-check}"
            ;;
        *)
            echo "Usage: $0 check <operation-name>" >&2
            echo "       (or 'source' this file and call aura_multi_worktree_guard)" >&2
            exit 2
            ;;
    esac
fi
