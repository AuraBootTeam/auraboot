-- Behavior ingest quarantine sink (SoT §2.7 aura.behavior.quarantine.v1).
-- The Kafka decoupling layer (aura.behavior.events.v1) routes events that cannot be
-- durably stored — malformed (missing event_id/event_name) or constraint-violating
-- (e.g. an over-long client field on the public keyed endpoint) — to the quarantine
-- topic with a reason, instead of silently dropping them. This table is the durable,
-- queryable sink behind that topic: observable (DLQ age SLO, SoT §5.7), replayable
-- (raw_event retained). Written by an async consumer with no MetaContext, so tenant_id
-- is always set explicitly (the table is in the tenant-line ignore list).
-- Additive + idempotent.
CREATE TABLE IF NOT EXISTS ab_behavior_quarantine (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,              -- resolved at the endpoint before enqueue
    user_id       BIGINT,
    anon_id       TEXT,                         -- bad client fields must not break quarantine
    event_id      TEXT,                         -- nullable: malformed/over-long events may lack/violate one
    event_name    TEXT,                         -- nullable: malformed/over-long events may lack/violate one
    reason        VARCHAR(64) NOT NULL,         -- malformed_missing_event_id|..._event_name|constraint_violation
    detail        TEXT,                         -- failure detail (e.g. truncated exception message)
    raw_event     JSONB,                        -- original event payload, retained for replay
    quarantined_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    created_at    TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ab_behavior_quarantine IS 'Behavior ingest DLQ sink (SoT §2.7 quarantine.v1) — observable + replayable bad-event store';

-- DLQ-age SLO scans + per-reason triage
CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_tenant_time ON ab_behavior_quarantine (tenant_id, quarantined_at);
CREATE INDEX IF NOT EXISTS idx_ab_behavior_quarantine_reason ON ab_behavior_quarantine (reason);
