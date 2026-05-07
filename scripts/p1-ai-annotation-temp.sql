-- P1' ACP platformization — temporary acp_ai_annotation table
--
-- Run manually once per dev DB:
--   psql -d auraboot_dev -f scripts/p1-ai-annotation-temp.sql
--
-- This table is the P1 vertical-slice version of the §4.3 design model.
-- It will be replaced by a properly governed model (with multi-tenant
-- interceptor, scope hierarchy, and 90-day archive policy) in P2'.
--
-- DO NOT add this to platform/schema.sql until P2' field freeze.

CREATE TABLE IF NOT EXISTS acp_ai_annotation (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                BIGINT NOT NULL,
    target_model_code        VARCHAR(64) NOT NULL,
    target_id                BIGINT NOT NULL,
    turn_id                  VARCHAR(64) NOT NULL,
    grounding_input          TEXT,
    grounding_intent         JSONB,
    grounding_at             TIMESTAMP,
    planning_steps           JSONB,
    planning_recommendation  TEXT,
    planning_at              TIMESTAMP,
    executing_started_at     TIMESTAMP,
    completed_at             TIMESTAMP,
    total_tokens             BIGINT NOT NULL DEFAULT 0,
    total_dollars            DECIMAL(12, 6) NOT NULL DEFAULT 0,
    safety_triggers          JSONB NOT NULL DEFAULT '[]'::jsonb,
    final_status             VARCHAR(32),
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_acp_ai_annotation_target_turn
        UNIQUE (tenant_id, target_model_code, target_id, turn_id)
);

CREATE INDEX IF NOT EXISTS idx_acp_ai_annotation_target
    ON acp_ai_annotation (tenant_id, target_model_code, target_id);

CREATE INDEX IF NOT EXISTS idx_acp_ai_annotation_turn
    ON acp_ai_annotation (tenant_id, turn_id);

COMMENT ON TABLE acp_ai_annotation IS
    'P1 vertical slice — polymorphic AI metadata for any business object. '
    'Replaces inline ai_* columns on business models. Will be governed by '
    'platform service in P2 with multi-tenant interceptor + 90d archive.';
