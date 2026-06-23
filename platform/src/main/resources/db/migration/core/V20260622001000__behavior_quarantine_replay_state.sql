-- Behavior quarantine replay state.
-- Adds operator-visible replay/redrive result fields without changing the ingest sink contract.
-- Additive + idempotent.
ALTER TABLE ab_behavior_quarantine
    ADD COLUMN IF NOT EXISTS replay_status VARCHAR(24) NOT NULL DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS replay_detail TEXT,
    ADD COLUMN IF NOT EXISTS replayed_behavior_event_id BIGINT,
    ADD COLUMN IF NOT EXISTS replayed_at TIMESTAMPTZ;

COMMENT ON COLUMN ab_behavior_quarantine.replay_status IS 'Replay status: pending|replayed|duplicate|failed';
COMMENT ON COLUMN ab_behavior_quarantine.replayed_behavior_event_id IS 'ab_behavior_event.id produced or matched by replay';

CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_tenant_replay
    ON ab_behavior_quarantine (tenant_id, replay_status, quarantined_at);
