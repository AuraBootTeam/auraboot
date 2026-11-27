-- Migration: 2026-05-28 connector CDC SPI (PRD 18 §B.3.3)
-- - ab_connector_sync_run: per-run history for all SyncStrategy executions
-- - ab_connector_cdc_engine: single-instance lease + position for CDC engines
-- - ab_connector: add sync_strategy / cdc_config_json / schedule_cron columns
--
-- Safe to replay: every DDL is IF NOT EXISTS / ADD COLUMN IF NOT EXISTS.

CREATE TABLE IF NOT EXISTS ab_connector_sync_run (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(32) UNIQUE NOT NULL,
    tenant_id BIGINT NOT NULL,
    connector_pid VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,        -- SCHEDULED / MANUAL / CDC / WEBHOOK
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL,              -- RUNNING / SUCCESS / FAILED / PARTIAL
    records_read INTEGER,
    records_written INTEGER,
    records_failed INTEGER,
    error_message TEXT,
    cursor_state JSONB,
    duration_ms INTEGER
);

CREATE INDEX IF NOT EXISTS idx_sync_connector
    ON ab_connector_sync_run (connector_pid, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_sync_status
    ON ab_connector_sync_run (status, started_at DESC);

CREATE TABLE IF NOT EXISTS ab_connector_cdc_engine (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(32) UNIQUE NOT NULL,
    connector_pid VARCHAR(32) NOT NULL,
    status VARCHAR(16),                       -- IDLE / RUNNING / PAUSED / FAILED
    last_position JSONB,
    last_event_at TIMESTAMPTZ,
    worker_node VARCHAR(64),
    heartbeat_at TIMESTAMPTZ,
    meta JSONB
);

CREATE INDEX IF NOT EXISTS idx_cdc_engine_connector
    ON ab_connector_cdc_engine (connector_pid);

-- Extend existing ab_connector with sync strategy + CDC config + schedule.
-- (ab_connector pre-exists from earlier migrations; only add new columns.)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'ab_connector') THEN
        EXECUTE 'ALTER TABLE ab_connector ADD COLUMN IF NOT EXISTS sync_strategy VARCHAR(32)';
        EXECUTE 'ALTER TABLE ab_connector ADD COLUMN IF NOT EXISTS cdc_config_json JSONB';
        EXECUTE 'ALTER TABLE ab_connector ADD COLUMN IF NOT EXISTS schedule_cron VARCHAR(64)';
    END IF;
END $$;
