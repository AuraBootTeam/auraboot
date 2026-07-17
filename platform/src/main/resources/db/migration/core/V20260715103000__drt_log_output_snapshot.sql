ALTER TABLE ab_drt_log
    ADD COLUMN IF NOT EXISTS output_snapshot JSONB;

COMMENT ON COLUMN ab_drt_log.output_snapshot IS 'Decision Runtime output snapshot for execution-log trace UI';
