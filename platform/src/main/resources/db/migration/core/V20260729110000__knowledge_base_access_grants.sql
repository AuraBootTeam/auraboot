ALTER TABLE ab_knowledge_base
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'tenant';

ALTER TABLE ab_knowledge_base
    DROP CONSTRAINT IF EXISTS chk_kb_visibility;

ALTER TABLE ab_knowledge_base
    ADD CONSTRAINT chk_kb_visibility
        CHECK (visibility IN ('tenant', 'restricted', 'private'));

CREATE TABLE IF NOT EXISTS ab_kb_access_grant (
    id              BIGSERIAL PRIMARY KEY,
    pid             VARCHAR(26) UNIQUE NOT NULL,
    tenant_id       BIGINT NOT NULL,
    kb_pid          VARCHAR(26) NOT NULL,
    subject_type    VARCHAR(30) NOT NULL,
    subject_id      VARCHAR(100) NOT NULL,
    permission      VARCHAR(20) NOT NULL DEFAULT 'read',
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    CONSTRAINT chk_kb_access_subject_type
        CHECK (subject_type IN ('user', 'member', 'role', 'digital_employee')),
    CONSTRAINT chk_kb_access_permission
        CHECK (permission IN ('read', 'manage')),
    CONSTRAINT uq_kb_access_grant
        UNIQUE (tenant_id, kb_pid, subject_type, subject_id, permission)
);

CREATE INDEX IF NOT EXISTS idx_kb_access_grant_lookup
    ON ab_kb_access_grant (tenant_id, kb_pid, subject_type, subject_id);

COMMENT ON TABLE ab_kb_access_grant IS
    'Explicit resource grants for restricted/private knowledge bases';
