-- ============================================================================
-- 2026-06-10 — Billing Metering Foundation (M1 slice-3)
-- ============================================================================
--
-- Creates 2 tables for the metering subsystem:
--   ab_billing_usage_event          — idempotent usage event record
--   ab_billing_usage_dedupe_conflict — conflict log for same-key different-payload events
--
-- Enum values MUST match Java source verbatim:
--   RatingStatus  : com.auraboot.framework.billing.metering.model.RatingStatus
--   DedupeStatus  : com.auraboot.framework.billing.metering.model.DedupeStatus
--
-- Naming convention: prefix ab_billing_
-- ID strategy: BIGINT PRIMARY KEY (snowflake via IdType.ASSIGN_ID — NOT SERIAL)
-- ============================================================================

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. Usage Event
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_usage_event (
    id                  BIGINT          PRIMARY KEY,

    -- Stable external code assigned by the server (e.g. "UE-<UUID>")
    event_code          VARCHAR(128)    NOT NULL,

    -- Caller-supplied idempotency key; combined with source_service forms the dedup key
    idempotency_key     VARCHAR(256)    NOT NULL,

    -- Owning account
    account_id          BIGINT          NOT NULL,

    -- Optional workspace scoping
    workspace_id        BIGINT,

    -- Optional user who triggered the event
    user_id             BIGINT,

    -- Optional linked subscription
    subscription_id     BIGINT,

    -- Resource type — must be registered in ab_billing_resource_catalog
    resource_code       VARCHAR(64)     NOT NULL,

    -- Optional sub-classification (e.g. model variant for AI_TOKEN)
    resource_subtype    VARCHAR(64),

    -- Usage quantity in the resource's native unit
    quantity            DECIMAL(24, 6)  NOT NULL,

    -- Unit of measure (must match resource catalog)
    unit                VARCHAR(32)     NOT NULL,

    -- When the usage actually occurred (caller-supplied)
    occurred_at         TIMESTAMPTZ     NOT NULL,

    -- When this event was received by the billing system
    received_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Service that produced this event (e.g. "acp-worker", "automation-engine")
    source_service      VARCHAR(128)    NOT NULL,

    -- Optional reference into the producing service (e.g. agent_run_id)
    source_ref          VARCHAR(256),

    -- com.auraboot.framework.billing.metering.model.RatingStatus
    -- PENDING → RATED → BILLED | SKIPPED
    rating_status       VARCHAR(32)     NOT NULL DEFAULT 'PENDING',

    -- com.auraboot.framework.billing.metering.model.DedupeStatus
    -- UNIQUE | DUPLICATE | CONFLICT
    dedupe_status       VARCHAR(32)     NOT NULL DEFAULT 'UNIQUE',

    -- Arbitrary extra metadata as JSONB (caller-supplied)
    metadata_json       TEXT,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- ── Constraints ───────────────────────────────────────────────────────────

    -- event_code is the stable external reference
    CONSTRAINT uq_billing_ue_event_code UNIQUE (event_code),

    -- Dedup key: same source + idempotency_key must not produce two events
    CONSTRAINT uq_billing_ue_source_idem UNIQUE (source_service, idempotency_key),

    -- com.auraboot.framework.billing.metering.model.RatingStatus
    CONSTRAINT chk_billing_ue_rating_status CHECK (rating_status IN (
        'PENDING', 'RATED', 'BILLED', 'SKIPPED'
    )),

    -- com.auraboot.framework.billing.metering.model.DedupeStatus
    CONSTRAINT chk_billing_ue_dedupe_status CHECK (dedupe_status IN (
        'UNIQUE', 'DUPLICATE', 'CONFLICT'
    )),

    -- Quantity must be non-negative
    CONSTRAINT chk_billing_ue_quantity_nonneg CHECK (quantity >= 0)
);

-- Usage lookup by account + resource + time range (primary query pattern for rating/billing)
CREATE INDEX IF NOT EXISTS idx_billing_ue_account_resource_time
    ON ab_billing_usage_event (account_id, resource_code, occurred_at);

-- Rating pipeline: find all PENDING events
CREATE INDEX IF NOT EXISTS idx_billing_ue_rating_status
    ON ab_billing_usage_event (rating_status)
    WHERE rating_status = 'PENDING';

COMMENT ON TABLE  ab_billing_usage_event IS 'Idempotent usage event record — dedup via (source_service, idempotency_key)';
COMMENT ON COLUMN ab_billing_usage_event.event_code
    IS 'Server-assigned stable external identifier for this event';
COMMENT ON COLUMN ab_billing_usage_event.idempotency_key
    IS 'Caller-supplied dedup key; combined with source_service enforces at-most-once semantics';
COMMENT ON COLUMN ab_billing_usage_event.rating_status
    IS 'Lifecycle: PENDING (unrated) → RATED (priced) → BILLED (invoiced) | SKIPPED (exempt)';
COMMENT ON COLUMN ab_billing_usage_event.dedupe_status
    IS 'UNIQUE = first occurrence; DUPLICATE = repeat with matching payload (not stored again); CONFLICT = same key, different payload (see dedupe_conflict table)';

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. Dedup Conflict Log
--    P2-9: same idempotency_key with a different payload cannot enter usage_event
--    (would corrupt billing accuracy) — must be logged separately for investigation.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ab_billing_usage_dedupe_conflict (
    id                      BIGINT          PRIMARY KEY,

    -- The same (source_service, idempotency_key) that already exists in usage_event
    source_service          VARCHAR(128)    NOT NULL,
    idempotency_key         VARCHAR(256)    NOT NULL,

    -- event_code of the original (already-accepted) usage_event
    existing_event_code     VARCHAR(128)    NOT NULL,

    -- Full payload of the conflicting request (JSON-serialized UsageEventRequest)
    conflicting_payload_json TEXT           NOT NULL,

    -- When this conflict was detected
    detected_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Lookup by (source_service, idempotency_key) for investigation
CREATE INDEX IF NOT EXISTS idx_billing_udc_source_idem
    ON ab_billing_usage_dedupe_conflict (source_service, idempotency_key);

COMMENT ON TABLE  ab_billing_usage_dedupe_conflict IS 'Conflict log: same idempotency_key with different payload — not charged, but preserved for investigation';
COMMENT ON COLUMN ab_billing_usage_dedupe_conflict.existing_event_code
    IS 'References ab_billing_usage_event.event_code of the original accepted event';
COMMENT ON COLUMN ab_billing_usage_dedupe_conflict.conflicting_payload_json
    IS 'Full JSON payload of the rejected conflicting request for manual investigation';
