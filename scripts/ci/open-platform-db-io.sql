-- Whitelisted counters only: no queries, usernames, tokens or credentials.
SELECT json_build_object(
  'sampled_at', clock_timestamp(),
  'wal', (SELECT row_to_json(w) FROM pg_stat_wal w),
  'database', (SELECT json_build_object(
    'xact_commit', xact_commit, 'xact_rollback', xact_rollback,
    'blks_read', blks_read, 'blks_hit', blks_hit,
    'blk_read_time', blk_read_time, 'blk_write_time', blk_write_time,
    'deadlocks', deadlocks
  ) FROM pg_stat_database WHERE datname = current_database()),
  'background_writer', (SELECT row_to_json(b) FROM pg_stat_bgwriter b)
);
