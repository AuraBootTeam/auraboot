#!/usr/bin/env bash

# Remove scheduler definitions that were registered by older AuraBoot releases
# but never had executable Spring beans.
#
# Safety contract:
#   - read-only by default;
#   - --apply is required for deletion;
#   - only the three exact system PIDs with tenant_id IS NULL are eligible;
#   - inbox cleanup candidates are reported, never changed by this script.

set -euo pipefail

MODE="check"

usage() {
    cat <<'EOF'
Usage:
  scripts/db/cleanup-scheduler-residue.sh [--check|--apply]

Modes:
  --check  Report the three legacy scheduler rows and the first-run inbox
           cleanup impact. This is the default and never mutates data.
  --apply  Delete only the exact legacy system scheduler rows, then verify.

Connection environment:
  PG_HOST / PGHOST          default: localhost
  PG_PORT / PGPORT          default: 5432
  PG_USER / PGUSER          default: current OS user
  PG_DB / PGDATABASE /
  POSTGRES_DB               default: aura_boot
  PG_PASSWORD / PGPASSWORD  optional; never printed
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)
            MODE="check"
            shift
            ;;
        --apply)
            MODE="apply"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument '$1'" >&2
            usage >&2
            exit 2
            ;;
    esac
done

PG_HOST="${PG_HOST:-${PGHOST:-localhost}}"
PG_PORT="${PG_PORT:-${PGPORT:-5432}}"
PG_USER="${PG_USER:-${PGUSER:-${USER:-}}}"
PG_DB="${PG_DB:-${PGDATABASE:-${POSTGRES_DB:-aura_boot}}}"
PG_PASSWORD="${PG_PASSWORD:-${PGPASSWORD:-}}"

psql_run() {
    local args=(
        -X
        -h "$PG_HOST"
        -p "$PG_PORT"
        -d "$PG_DB"
        -v ON_ERROR_STOP=1
        -P pager=off
        -At
    )
    if [[ -n "$PG_USER" ]]; then
        args+=(-U "$PG_USER")
    fi
    if [[ -n "$PG_PASSWORD" ]]; then
        PGPASSWORD="$PG_PASSWORD" psql "${args[@]}" "$@"
    else
        psql "${args[@]}" "$@"
    fi
}

report_impact() {
    psql_run <<'SQL'
SELECT format('phantom_task_count=%s', COUNT(*))
FROM ab_scheduled_task
WHERE tenant_id IS NULL
  AND pid IN (
      'sys-marketplace-upgrade',
      'sys-ai-suggestion',
      'sys-license-validation'
  );

SELECT format(
    'phantom_task=%s|enabled=%s|handler=%s.%s',
    pid,
    enabled,
    handler_bean,
    handler_method
)
FROM ab_scheduled_task
WHERE tenant_id IS NULL
  AND pid IN (
      'sys-marketplace-upgrade',
      'sys-ai-suggestion',
      'sys-license-validation'
  )
ORDER BY pid;

SELECT format('inbox_mark_expired_candidates=%s', COUNT(*))
FROM ab_inbox_item
WHERE status = 'pending'
  AND expires_at IS NOT NULL
  AND expires_at < NOW();

SELECT format('inbox_delete_after_first_run_candidates=%s', COUNT(*))
FROM ab_inbox_item
WHERE status IN ('acted', 'dismissed', 'expired')
  AND created_at < NOW() - INTERVAL '90 days';
SQL
}

echo "scheduler_residue_mode=$MODE"
echo "database_target=$PG_HOST:$PG_PORT/$PG_DB"
report_impact

if [[ "$MODE" == "check" ]]; then
    echo "result=read_only_check_complete"
    exit 0
fi

psql_run <<'SQL'
BEGIN;

WITH deleted AS (
    DELETE FROM ab_scheduled_task
    WHERE tenant_id IS NULL
      AND pid IN (
          'sys-marketplace-upgrade',
          'sys-ai-suggestion',
          'sys-license-validation'
      )
    RETURNING pid
)
SELECT format('deleted_phantom_task=%s', pid)
FROM deleted
ORDER BY pid;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ab_scheduled_task
        WHERE tenant_id IS NULL
          AND pid IN (
              'sys-marketplace-upgrade',
              'sys-ai-suggestion',
              'sys-license-validation'
          )
    ) THEN
        RAISE EXCEPTION 'legacy scheduler residue remains after cleanup';
    END IF;
END
$$;

COMMIT;
SQL

report_impact
echo "result=apply_complete"
