ALTER TABLE ab_drt_log
    ADD COLUMN IF NOT EXISTS trace_snapshot JSONB;

COMMENT ON COLUMN ab_drt_log.trace_snapshot IS 'Decision Runtime trace diagnostics such as virtual source resolution evidence';
