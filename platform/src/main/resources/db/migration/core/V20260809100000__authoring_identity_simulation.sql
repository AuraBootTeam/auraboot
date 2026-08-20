-- Audited, actor-bound, short-lived identity simulation sessions for professional Studio.
CREATE TABLE IF NOT EXISTS ab_authoring_identity_simulation (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    actor_user_id       BIGINT NOT NULL,
    source_session_pid  VARCHAR(26) NOT NULL,
    change_set_pid      VARCHAR(26) NOT NULL,
    page_pid            VARCHAR(64) NOT NULL,
    target_role_pid     VARCHAR(26) NOT NULL,
    target_role_code    VARCHAR(128) NOT NULL,
    target_role_name    VARCHAR(255) NOT NULL,
    reason              VARCHAR(1000) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    started_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at          TIMESTAMPTZ NOT NULL,
    ended_at            TIMESTAMPTZ,
    last_accessed_at    TIMESTAMPTZ,
    row_version         BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_identity_simulation_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_identity_simulation_status
        CHECK (status IN ('ACTIVE', 'ENDED', 'EXPIRED')),
    CONSTRAINT chk_authoring_identity_simulation_expiry
        CHECK (expires_at > started_at)
);

CREATE INDEX IF NOT EXISTS idx_authoring_identity_simulation_actor
    ON ab_authoring_identity_simulation (
        tenant_id, env_id, actor_user_id, status, expires_at DESC);

CREATE INDEX IF NOT EXISTS idx_authoring_identity_simulation_session
    ON ab_authoring_identity_simulation (
        tenant_id, env_id, source_session_pid, created_at DESC);

COMMENT ON TABLE ab_authoring_identity_simulation IS
    'Actor-bound, read-only, short-lived role simulation lifecycle; every transition is audited';
