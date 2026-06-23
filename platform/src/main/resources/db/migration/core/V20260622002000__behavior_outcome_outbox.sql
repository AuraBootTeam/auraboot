-- Server-side behavior outcome transactional outbox.
-- Business services write here inside the same local transaction as the
-- authoritative state change, then the relay publishes to aura.behavior.events.v1.
CREATE TABLE IF NOT EXISTS ab_behavior_outcome_outbox (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    event_id          VARCHAR(40) NOT NULL,
    user_id           BIGINT,
    event_name        VARCHAR(120) NOT NULL,
    target_type       VARCHAR(64),
    target_key        VARCHAR(120),
    payload           JSONB NOT NULL,
    trace_id          VARCHAR(36),
    source_span_id    VARCHAR(36),
    run_id            VARCHAR(64),
    interaction_id    VARCHAR(64),
    caused_by_event_id VARCHAR(40),
    occurred_at       TIMESTAMPTZ NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'pending',
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error        TEXT,
    published_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ab_behavior_outcome_outbox IS
    'Transactional outbox for server-side behavior business outcome events';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_outcome_outbox_tenant_event
    ON ab_behavior_outcome_outbox (tenant_id, event_id);

CREATE INDEX IF NOT EXISTS idx_ab_behavior_outcome_outbox_pending
    ON ab_behavior_outcome_outbox (status, next_attempt_at, created_at)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_ab_behavior_outcome_outbox_tenant_time
    ON ab_behavior_outcome_outbox (tenant_id, created_at);
