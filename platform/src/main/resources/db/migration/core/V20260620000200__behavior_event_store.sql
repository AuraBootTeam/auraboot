-- Behavior analytics durable event store (M1; SoT §5.5 frozen envelope + §5.4 ui_element).
-- Event-first model: stable join key is ui_element_id (NOT a 4-segment path); causality
-- via interaction_id / caused_by_event_id; cross-domain correlation via trace_id (1:N).
-- The /api/collect endpoint server-enriches tenant/user then persists here. The Kafka
-- decoupling layer (aura.behavior.events.v1) is the production ingestion path (follow-up).
-- Additive + idempotent.
CREATE TABLE IF NOT EXISTS ab_behavior_event (
    id                   BIGSERIAL PRIMARY KEY,
    event_id             VARCHAR(40) NOT NULL,         -- client ULID; idempotency key
    schema_version       VARCHAR(16),
    event_name           VARCHAR(120) NOT NULL,
    event_category       VARCHAR(32),                  -- ui_interaction|navigation|business_intent|business_outcome|experiment
    source               VARCHAR(24),                  -- autocapture|declared|server
    identity_quality     VARCHAR(16),                  -- heuristic|declared|stable
    occurred_at          TIMESTAMPTZ,
    received_at          TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    tenant_id            BIGINT NOT NULL,              -- server-enriched
    user_id              BIGINT,
    anon_id              VARCHAR(64),
    client_session_id    VARCHAR(64),
    interaction_id       VARCHAR(64),
    caused_by_event_id   VARCHAR(40),
    trace_id             VARCHAR(36),
    source_span_id       VARCHAR(36),
    run_id               VARCHAR(64),
    -- flattened ui_element (§5.4): stable id is the join key, path is derived/non-key
    ui_element_id        VARCHAR(80),
    app_id               VARCHAR(64),
    page_id              VARCHAR(64),
    block_id             VARCHAR(64),
    element_code         VARCHAR(64),
    props                JSONB,
    consent_state        VARCHAR(24),
    consent_version      VARCHAR(16),
    sampling_unit        VARCHAR(16),                  -- event|session|user|element
    sampling_probability NUMERIC(6,5),
    producer_name        VARCHAR(48),
    producer_version     VARCHAR(24),
    created_at           TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE ab_behavior_event IS 'Behavior analytics event store (M1, SoT §5.5) — event-first, ui_element_id stable join key';

-- idempotency: a client event id is unique within a tenant (retries do not duplicate)
CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_event_tenant_eventid ON ab_behavior_event (tenant_id, event_id);
CREATE INDEX IF NOT EXISTS idx_ab_behavior_event_tenant_session ON ab_behavior_event (tenant_id, client_session_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ab_behavior_event_tenant_name ON ab_behavior_event (tenant_id, event_name, occurred_at);
CREATE INDEX IF NOT EXISTS idx_ab_behavior_event_interaction ON ab_behavior_event (interaction_id) WHERE interaction_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ab_behavior_event_trace ON ab_behavior_event (trace_id) WHERE trace_id IS NOT NULL;
