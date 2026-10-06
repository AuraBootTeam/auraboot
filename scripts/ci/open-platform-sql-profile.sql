-- Constants are normalized by pg_stat_statements; bound token/credential values
-- are never exported. The reset immediately before k6 fixes the time denominator.
SELECT COALESCE(json_agg(row_to_json(profile)), '[]'::json)
FROM (
  SELECT queryid::text AS query_id, query, calls, rows,
         total_exec_time, mean_exec_time, max_exec_time,
         shared_blks_hit, shared_blks_read, shared_blks_dirtied, wal_bytes
  FROM pg_stat_statements
  WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
    AND query NOT ILIKE '%pg_stat_statements%'
  ORDER BY total_exec_time DESC
  LIMIT 40
) profile;
