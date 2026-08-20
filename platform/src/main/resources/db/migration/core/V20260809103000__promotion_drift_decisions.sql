-- Governed production-drift decisions for environment promotion.
--
-- A promotion that meets an active contextual/tenant release must not silently overwrite it.
-- The unit keeps the current decision pointer while the append-only event table preserves every
-- detected fingerprint, stale transition, human decision, and applied overwrite.

ALTER TABLE ab_promotion_unit
    ADD COLUMN target_resource_pid VARCHAR(32),
    ADD COLUMN drift_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN drift_fingerprint VARCHAR(64),
    ADD COLUMN drift_decision VARCHAR(24),
    ADD COLUMN drift_decision_pid VARCHAR(26),
    ADD CONSTRAINT chk_promotion_unit_drift_status
        CHECK (drift_status IN ('NONE', 'PENDING', 'RESOLVED', 'STALE', 'APPLIED')),
    ADD CONSTRAINT chk_promotion_unit_drift_decision
        CHECK (drift_decision IS NULL OR drift_decision IN (
            'REBASE', 'BACKPORT', 'KEEP_OVERRIDE', 'OVERWRITE')),
    ADD CONSTRAINT chk_promotion_unit_drift_resolution
        CHECK ((drift_status IN ('NONE', 'PENDING', 'STALE')
                    AND drift_decision IS NULL AND drift_decision_pid IS NULL)
            OR (drift_status IN ('RESOLVED', 'APPLIED')
                    AND drift_fingerprint IS NOT NULL
                    AND drift_decision IS NOT NULL
                    AND drift_decision_pid IS NOT NULL));

CREATE TABLE ab_promotion_drift_event (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    promotion_id        BIGINT NOT NULL,
    promotion_unit_id   BIGINT NOT NULL,
    event_type          VARCHAR(16) NOT NULL,
    drift_kind          VARCHAR(32) NOT NULL,
    drift_fingerprint   VARCHAR(64) NOT NULL,
    decision            VARCHAR(24),
    reason_code         VARCHAR(80) NOT NULL,
    reason              VARCHAR(500),
    actor_user_id       BIGINT,
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_promotion_drift_event_promotion
        FOREIGN KEY (promotion_id) REFERENCES ab_promotion(id),
    CONSTRAINT fk_promotion_drift_event_unit
        FOREIGN KEY (promotion_unit_id) REFERENCES ab_promotion_unit(id),
    CONSTRAINT chk_promotion_drift_event_type
        CHECK (event_type IN ('DETECTED', 'DECIDED', 'STALE', 'APPLIED')),
    CONSTRAINT chk_promotion_drift_event_decision
        CHECK (decision IS NULL OR decision IN (
            'REBASE', 'BACKPORT', 'KEEP_OVERRIDE', 'OVERWRITE')),
    CONSTRAINT chk_promotion_drift_event_reason
        CHECK (reason IS NULL OR char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT chk_promotion_drift_event_evidence
        CHECK (jsonb_typeof(evidence) = 'object')
);

CREATE INDEX idx_promotion_drift_event_unit
    ON ab_promotion_drift_event
       (tenant_id, promotion_unit_id, created_at DESC, id DESC);

CREATE TRIGGER trg_promotion_drift_event_append_only
    BEFORE UPDATE OR DELETE ON ab_promotion_drift_event
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

COMMENT ON TABLE ab_promotion_drift_event IS
    'Append-only production drift detection and human resolution evidence';
COMMENT ON COLUMN ab_promotion_unit.drift_fingerprint IS
    'Hash of incoming source, target baseline, active target release, and channel version';
COMMENT ON COLUMN ab_promotion_unit.drift_decision IS
    'Explicit fate: REBASE, BACKPORT, KEEP_OVERRIDE, or OVERWRITE';
