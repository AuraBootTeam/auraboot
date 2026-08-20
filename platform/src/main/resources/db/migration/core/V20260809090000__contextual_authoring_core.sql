-- Contextual Authoring governance core.
--
-- Editable PageSchema rows remain legacy resource definitions. Draft proposals live in
-- ChangeSet-owned resource snapshots; production runtime activation is represented only by an
-- immutable Release plus an atomic release-channel pointer.

CREATE TABLE IF NOT EXISTS ab_authoring_change_set (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    owner_user_id       BIGINT NOT NULL,
    title               VARCHAR(200) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    revision            BIGINT NOT NULL DEFAULT 1,
    base_release_pid    VARCHAR(26),
    manifest_checksum   VARCHAR(64),
    risk_level          VARCHAR(2) NOT NULL DEFAULT 'L0',
    route               VARCHAR(32) NOT NULL DEFAULT 'INLINE',
    publish_policy      VARCHAR(32) NOT NULL DEFAULT 'DIRECT_ALLOWED',
    validation_state    VARCHAR(24) NOT NULL DEFAULT 'UNVALIDATED',
    approval_state      VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED',
    publish_state       VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    stale_reason        VARCHAR(80),
    submitted_at        TIMESTAMPTZ,
    approved_at         TIMESTAMPTZ,
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_authoring_change_set_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_change_set_status
        CHECK (status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED',
                          'REJECTED', 'WITHDRAWN')),
    CONSTRAINT chk_authoring_change_set_risk
        CHECK (risk_level IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT chk_authoring_change_set_route
        CHECK (route IN ('PERSONALIZE', 'INLINE', 'GUIDED_INLINE',
                         'HANDOFF_STUDIO', 'DENY')),
    CONSTRAINT chk_authoring_change_set_publish_policy
        CHECK (publish_policy IN ('DIRECT_ALLOWED', 'DEFAULT_REVIEW', 'REQUIRED_REVIEW',
                                  'STUDIO_APPROVAL', 'DENIED')),
    CONSTRAINT chk_authoring_change_set_validation
        CHECK (validation_state IN ('UNVALIDATED', 'VALID', 'INVALID', 'STALE')),
    CONSTRAINT chk_authoring_change_set_approval
        CHECK (approval_state IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED', 'STALE')),
    CONSTRAINT chk_authoring_change_set_publish
        CHECK (publish_state IN ('DRAFT', 'READY', 'PUBLISHING', 'PUBLISHED',
                                 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT chk_authoring_change_set_revision CHECK (revision > 0)
);

CREATE INDEX IF NOT EXISTS idx_authoring_change_set_owner
    ON ab_authoring_change_set (tenant_id, env_id, owner_user_id, updated_at DESC)
    WHERE deleted_flag = FALSE;
CREATE INDEX IF NOT EXISTS idx_authoring_change_set_status
    ON ab_authoring_change_set (tenant_id, env_id, status, updated_at DESC)
    WHERE deleted_flag = FALSE;

CREATE TABLE IF NOT EXISTS ab_authoring_resource_draft (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    resource_type       VARCHAR(40) NOT NULL,
    resource_pid        VARCHAR(64) NOT NULL,
    base_version        BIGINT NOT NULL,
    base_checksum       VARCHAR(64) NOT NULL,
    manifest_checksum   VARCHAR(64) NOT NULL,
    snapshot            JSONB NOT NULL,
    revision            BIGINT NOT NULL DEFAULT 1,
    validation_state    VARCHAR(24) NOT NULL DEFAULT 'UNVALIDATED',
    stale_reason        VARCHAR(80),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_resource_draft_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_resource_draft_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_resource_draft
        UNIQUE (change_set_id, resource_type, resource_pid),
    CONSTRAINT chk_authoring_resource_draft_revision CHECK (revision > 0),
    CONSTRAINT chk_authoring_resource_draft_validation
        CHECK (validation_state IN ('UNVALIDATED', 'VALID', 'INVALID', 'STALE'))
);

CREATE INDEX IF NOT EXISTS idx_authoring_resource_draft_resource
    ON ab_authoring_resource_draft (tenant_id, env_id, resource_type, resource_pid);

CREATE TABLE IF NOT EXISTS ab_authoring_change_item (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    resource_draft_id   BIGINT NOT NULL,
    block_id            VARCHAR(128) NOT NULL,
    property_path       VARCHAR(512) NOT NULL,
    operation           VARCHAR(16) NOT NULL,
    old_value           JSONB,
    new_value           JSONB,
    effect_tags         JSONB NOT NULL DEFAULT '[]'::jsonb,
    risk_level          VARCHAR(2) NOT NULL,
    route               VARCHAR(32) NOT NULL,
    publish_policy      VARCHAR(32) NOT NULL,
    reversibility       VARCHAR(24) NOT NULL,
    manifest_checksum   VARCHAR(64) NOT NULL,
    base_revision       BIGINT NOT NULL,
    result_revision     BIGINT NOT NULL,
    actor_user_id       BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_change_item_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_change_item_resource_draft
        FOREIGN KEY (resource_draft_id) REFERENCES ab_authoring_resource_draft(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_change_item_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_change_item_operation
        CHECK (operation IN ('ADD', 'REPLACE', 'REMOVE', 'MOVE', 'COPY')),
    CONSTRAINT chk_authoring_change_item_risk
        CHECK (risk_level IN ('L0', 'L1', 'L2', 'L3')),
    CONSTRAINT chk_authoring_change_item_route
        CHECK (route IN ('PERSONALIZE', 'INLINE', 'GUIDED_INLINE',
                         'HANDOFF_STUDIO', 'DENY')),
    CONSTRAINT chk_authoring_change_item_publish_policy
        CHECK (publish_policy IN ('DIRECT_ALLOWED', 'DEFAULT_REVIEW', 'REQUIRED_REVIEW',
                                  'STUDIO_APPROVAL', 'DENIED')),
    CONSTRAINT chk_authoring_change_item_reversibility
        CHECK (reversibility IN ('REVERSIBLE', 'COMPENSATABLE', 'FORWARD_ONLY')),
    CONSTRAINT chk_authoring_change_item_revision
        CHECK (base_revision > 0 AND result_revision = base_revision + 1)
);

CREATE INDEX IF NOT EXISTS idx_authoring_change_item_set
    ON ab_authoring_change_item (change_set_id, result_revision, id);
CREATE INDEX IF NOT EXISTS idx_authoring_change_item_block
    ON ab_authoring_change_item (tenant_id, env_id, block_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ab_authoring_config_session (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    actor_user_id       BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    page_pid            VARCHAR(64) NOT NULL,
    state               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    interaction_context JSONB NOT NULL DEFAULT '{}'::jsonb,
    revision            BIGINT NOT NULL DEFAULT 1,
    expires_at          TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_config_session_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_config_session_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_config_session_state
        CHECK (state IN ('ACTIVE', 'READ_ONLY', 'CLOSED', 'EXPIRED')),
    CONSTRAINT chk_authoring_config_session_revision CHECK (revision > 0)
);

CREATE INDEX IF NOT EXISTS idx_authoring_config_session_actor
    ON ab_authoring_config_session (tenant_id, env_id, actor_user_id, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_authoring_config_session_page
    ON ab_authoring_config_session (tenant_id, env_id, page_pid, state);

CREATE TABLE IF NOT EXISTS ab_authoring_writer_lease (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    change_set_id       BIGINT UNIQUE NOT NULL,
    session_id          BIGINT UNIQUE NOT NULL,
    holder_user_id      BIGINT NOT NULL,
    lease_revision      BIGINT NOT NULL DEFAULT 1,
    acquired_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leased_until        TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_writer_lease_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_writer_lease_session
        FOREIGN KEY (session_id) REFERENCES ab_authoring_config_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_writer_lease_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_writer_lease_revision CHECK (lease_revision > 0)
);

CREATE TABLE IF NOT EXISTS ab_authoring_handoff_context (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    actor_user_id       BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    nonce_hash          VARCHAR(64) UNIQUE NOT NULL,
    target_route        VARCHAR(240) NOT NULL,
    context_payload     JSONB NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_handoff_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_handoff_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id)
);

CREATE INDEX IF NOT EXISTS idx_authoring_handoff_actor
    ON ab_authoring_handoff_context (tenant_id, env_id, actor_user_id, expires_at DESC);

CREATE TABLE IF NOT EXISTS ab_authoring_approval (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    change_set_revision BIGINT NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_user_id    BIGINT,
    reason              VARCHAR(1000),
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_approval_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_approval_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_approval_revision
        UNIQUE (change_set_id, change_set_revision),
    CONSTRAINT chk_authoring_approval_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'STALE')),
    CONSTRAINT chk_authoring_approval_revision CHECK (change_set_revision > 0)
);

CREATE TABLE IF NOT EXISTS ab_authoring_release (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    change_set_id       BIGINT NOT NULL,
    change_set_revision BIGINT NOT NULL,
    previous_release_pid VARCHAR(26),
    status              VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    manifest            JSONB NOT NULL,
    manifest_checksum   VARCHAR(64) NOT NULL,
    failure_reason      VARCHAR(1000),
    created_by          BIGINT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at        TIMESTAMPTZ,
    CONSTRAINT fk_authoring_release_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id),
    CONSTRAINT fk_authoring_release_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_release_revision
        UNIQUE (change_set_id, change_set_revision),
    CONSTRAINT chk_authoring_release_status
        CHECK (status IN ('PREPARING', 'ACTIVE', 'SUPERSEDED', 'FAILED', 'ROLLED_BACK')),
    CONSTRAINT chk_authoring_release_revision CHECK (change_set_revision > 0)
);

CREATE INDEX IF NOT EXISTS idx_authoring_release_active
    ON ab_authoring_release (tenant_id, env_id, activated_at DESC)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS ab_authoring_release_item (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    release_id          BIGINT NOT NULL,
    resource_type       VARCHAR(40) NOT NULL,
    resource_pid        VARCHAR(64) NOT NULL,
    source_version      BIGINT NOT NULL,
    snapshot            JSONB NOT NULL,
    snapshot_checksum   VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_release_item_release
        FOREIGN KEY (release_id) REFERENCES ab_authoring_release(id) ON DELETE CASCADE,
    CONSTRAINT fk_authoring_release_item_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_release_item_resource
        UNIQUE (release_id, resource_type, resource_pid)
);

CREATE INDEX IF NOT EXISTS idx_authoring_release_item_resource
    ON ab_authoring_release_item (tenant_id, env_id, resource_type, resource_pid, release_id);

CREATE TABLE IF NOT EXISTS ab_authoring_release_channel (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    resource_type       VARCHAR(40) NOT NULL,
    resource_pid        VARCHAR(64) NOT NULL,
    active_release_id   BIGINT NOT NULL,
    previous_release_id BIGINT,
    row_version         BIGINT NOT NULL DEFAULT 1,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT NOT NULL,
    CONSTRAINT fk_authoring_release_channel_active
        FOREIGN KEY (active_release_id) REFERENCES ab_authoring_release(id),
    CONSTRAINT fk_authoring_release_channel_previous
        FOREIGN KEY (previous_release_id) REFERENCES ab_authoring_release(id),
    CONSTRAINT fk_authoring_release_channel_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT uq_authoring_release_channel_resource
        UNIQUE (tenant_id, env_id, resource_type, resource_pid),
    CONSTRAINT chk_authoring_release_channel_version CHECK (row_version > 0)
);

CREATE TABLE IF NOT EXISTS ab_authoring_audit_event (
    id                  BIGSERIAL PRIMARY KEY,
    pid                 VARCHAR(26) UNIQUE NOT NULL,
    tenant_id           BIGINT NOT NULL,
    env_id              BIGINT NOT NULL,
    actor_user_id       BIGINT,
    change_set_pid      VARCHAR(26),
    session_pid         VARCHAR(26),
    event_type          VARCHAR(48) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    reason_code         VARCHAR(80),
    resource_type       VARCHAR(40),
    resource_pid        VARCHAR(64),
    block_id            VARCHAR(128),
    property_path       VARCHAR(512),
    trace_id            VARCHAR(64),
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_audit_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_audit_result
        CHECK (result IN ('ALLOW', 'DENY', 'FAIL'))
);

CREATE INDEX IF NOT EXISTS idx_authoring_audit_change_set
    ON ab_authoring_audit_event (tenant_id, env_id, change_set_pid, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_authoring_audit_actor
    ON ab_authoring_audit_event (tenant_id, env_id, actor_user_id, created_at DESC);

COMMENT ON TABLE ab_authoring_change_set IS
    'Contextual Authoring aggregate with orthogonal validation, approval and publish states';
COMMENT ON TABLE ab_authoring_resource_draft IS
    'ChangeSet-owned resource snapshot; never read by normal runtime';
COMMENT ON TABLE ab_authoring_change_item IS
    'Typed, policy-resolved semantic patch history; no executable business command payload';
COMMENT ON TABLE ab_authoring_release IS
    'Immutable release manifest prepared from one approved ChangeSet revision';
COMMENT ON TABLE ab_authoring_release_channel IS
    'Atomic runtime pointer for a tenant/environment/resource; cache keys include active release pid';
COMMENT ON TABLE ab_authoring_handoff_context IS
    'Short-lived, actor/tenant-bound Studio handoff context; URLs contain only its pid';
COMMENT ON TABLE ab_authoring_audit_event IS
    'Secret-free append-only authoring decision and lifecycle audit';
