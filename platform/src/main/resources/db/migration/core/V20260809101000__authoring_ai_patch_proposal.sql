-- Durable, actor-bound AI patch proposals. AI output cannot mutate a draft directly.
CREATE TABLE IF NOT EXISTS ab_authoring_ai_patch_proposal (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    actor_user_id       BIGINT NOT NULL,
    source_session_id   BIGINT NOT NULL,
    source_session_pid  VARCHAR(26) NOT NULL,
    change_set_id       BIGINT NOT NULL,
    change_set_pid      VARCHAR(26) NOT NULL,
    page_pid            VARCHAR(64) NOT NULL,
    base_revision       BIGINT NOT NULL,
    registry_checksum   VARCHAR(64) NOT NULL,
    proposal_hash       VARCHAR(64) NOT NULL,
    item_count          INTEGER NOT NULL,
    items               JSONB NOT NULL,
    decisions           JSONB NOT NULL,
    aggregate_risk      VARCHAR(2) NOT NULL,
    aggregate_route     VARCHAR(32) NOT NULL,
    publish_policy      VARCHAR(32) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    result_revision     BIGINT,
    rejection_reason    VARCHAR(1000),
    applied_at          TIMESTAMPTZ,
    rejected_at         TIMESTAMPTZ,
    row_version         BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_ai_proposal_session
        FOREIGN KEY (source_session_id) REFERENCES ab_authoring_config_session(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_authoring_ai_proposal_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id)
        ON DELETE CASCADE,
    CONSTRAINT fk_authoring_ai_proposal_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_ai_proposal_status
        CHECK (status IN ('PROPOSED', 'APPLIED', 'REJECTED')),
    CONSTRAINT chk_authoring_ai_proposal_item_count
        CHECK (item_count BETWEEN 1 AND 50 AND jsonb_array_length(items) = item_count
               AND jsonb_array_length(decisions) = item_count),
    CONSTRAINT chk_authoring_ai_proposal_revision
        CHECK (base_revision > 0
               AND (result_revision IS NULL OR result_revision >= base_revision)),
    CONSTRAINT chk_authoring_ai_proposal_risk
        CHECK (aggregate_risk IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT chk_authoring_ai_proposal_route
        CHECK (aggregate_route IN ('PERSONALIZE', 'INLINE', 'GUIDED_INLINE',
                                   'HANDOFF_STUDIO', 'DENY')),
    CONSTRAINT chk_authoring_ai_proposal_publish_policy
        CHECK (publish_policy IN ('DIRECT_ALLOWED', 'DEFAULT_REVIEW', 'REQUIRED_REVIEW',
                                  'STUDIO_APPROVAL', 'DENIED'))
);

CREATE INDEX IF NOT EXISTS idx_authoring_ai_proposal_actor
    ON ab_authoring_ai_patch_proposal (
        tenant_id, env_id, actor_user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_authoring_ai_proposal_session
    ON ab_authoring_ai_patch_proposal (
        tenant_id, env_id, source_session_pid, created_at DESC);

COMMENT ON TABLE ab_authoring_ai_patch_proposal IS
    'Typed AI patch proposals validated by the same policy engine as manual Studio edits';
