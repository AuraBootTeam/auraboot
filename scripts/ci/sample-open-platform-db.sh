#!/usr/bin/env bash
set -Eeuo pipefail
[[ $# == 2 ]] || exit 2
pg="$1"
stop_file="$2"
# Observe only this job's database. Never export query text, parameters or users.
for sample in $(seq 1 180); do
  [[ ! -e "$stop_file" ]] || exit 0
  timeout 5 docker exec "$pg" psql -U auraboot -d open_platform_ci -At -v ON_ERROR_STOP=1 -c "
    SELECT json_build_object('sampled_at', clock_timestamp(), 'waits',
      COALESCE(json_agg(row_to_json(w)), '[]'::json))
    FROM (
      SELECT state, wait_event_type, wait_event, count(*) AS sessions,
             count(*) FILTER (WHERE cardinality(pg_blocking_pids(pid)) > 0) AS blocked
      FROM pg_stat_activity
      WHERE datname = current_database() AND pid <> pg_backend_pid()
      GROUP BY state, wait_event_type, wait_event
    ) w;"
  sleep 1
done
# Exceeding the diagnostic window must not silently become a green receipt.
exit 2
