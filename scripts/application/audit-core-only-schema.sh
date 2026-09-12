#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_HOST:?POSTGRES_HOST is required}"
: "${POSTGRES_PORT:?POSTGRES_PORT is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD is required}"

export PGPASSWORD="$POSTGRES_PASSWORD"
PSQL=(psql -X -q -v ON_ERROR_STOP=1 -h "$POSTGRES_HOST" -p "$POSTGRES_PORT" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At)

owned_tables="$(${PSQL[@]} <<'SQL'
SELECT tablename
FROM pg_tables
WHERE schemaname = 'public'
  AND (
    tablename ~ '(^|_)(bpm|crm)(_|$)'
    OR tablename LIKE 'se_%'
    OR tablename LIKE 'ab_sla_%'
    OR tablename IN (
      'ab_calendar_event_map',
      'ab_node_interceptor',
      'ab_event_log',
      'ab_chain_execution',
      'ab_saga_execution'
    )
  )
ORDER BY tablename;
SQL
)"

if [[ -n "$owned_tables" ]]; then
  echo "[core-only-schema] FAIL: product-owned tables are present" >&2
  printf '%s\n' "$owned_tables" >&2
  exit 1
fi

persisted_signals="$(${PSQL[@]} <<'SQL'
CREATE TEMP TABLE core_only_product_signals (
  table_name text NOT NULL,
  matching_rows bigint NOT NULL
);
DO $audit$
DECLARE
  candidate record;
  matched bigint;
BEGIN
  FOR candidate IN
    SELECT tablename
    FROM pg_tables
    WHERE schemaname = 'public'
      AND tablename NOT IN (
        'ab_flyway_schema_history',
        -- Operational provenance may legitimately contain an artifact path whose
        -- directory name mentions the extraction project. It is not product data.
        'ab_plugin_import_history'
      )
    ORDER BY tablename
  LOOP
    EXECUTE format(
      'SELECT count(*) FROM %I row_value WHERE row_to_json(row_value)::text ~* %L',
      candidate.tablename,
      '(^|[^a-z0-9])(crm|bpm|smartengine)([^a-z0-9]|$)'
    ) INTO matched;
    IF matched > 0 THEN
      INSERT INTO core_only_product_signals VALUES (candidate.tablename, matched);
    END IF;
  END LOOP;
END
$audit$;
SELECT table_name || E'\t' || matching_rows
FROM core_only_product_signals
ORDER BY table_name;
SQL
)"

if [[ -n "$persisted_signals" ]]; then
  echo "[core-only-schema] FAIL: persisted product literals are present" >&2
  printf '%s\n' "$persisted_signals" >&2
  exit 1
fi

table_count="$(${PSQL[@]} -c "SELECT count(*) FROM pg_tables WHERE schemaname = 'public';")"
migration_count="$(${PSQL[@]} -c 'SELECT count(*) FROM ab_flyway_schema_history WHERE success = true;')"
echo "[core-only-schema] PASS database=$POSTGRES_DB tables=$table_count migrations=$migration_count product_tables=0 product_rows=0"
