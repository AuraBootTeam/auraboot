-- Forward-only completion for installations whose snapshot/version watermark
-- already passed V20260924100000 before that migration reached main.
-- Preserve existing session and user data; the original DDL is idempotent.
ALTER TABLE ab_user_session
    ADD COLUMN IF NOT EXISTS session_kind VARCHAR(24) NOT NULL DEFAULT 'user',
    ADD COLUMN IF NOT EXISTS initiated_by_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS impersonation_expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS impersonation_authorization_method VARCHAR(24),
    ADD COLUMN IF NOT EXISTS impersonation_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS impersonation_reference VARCHAR(200),
    ADD COLUMN IF NOT EXISTS client_type VARCHAR(24);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_user_session_initiated_by') THEN
        ALTER TABLE ab_user_session
            ADD CONSTRAINT fk_user_session_initiated_by
            FOREIGN KEY (initiated_by_user_id) REFERENCES ab_user (id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_session_kind') THEN
        ALTER TABLE ab_user_session
            ADD CONSTRAINT ck_user_session_kind
            CHECK (session_kind IN ('user', 'impersonation'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_user_session_impersonation_metadata') THEN
        ALTER TABLE ab_user_session
            ADD CONSTRAINT ck_user_session_impersonation_metadata CHECK (
                session_kind <> 'impersonation'
                OR (initiated_by_user_id IS NOT NULL AND impersonation_expires_at IS NOT NULL
                    AND impersonation_authorization_method IS NOT NULL
                    AND impersonation_reason IS NOT NULL AND client_type IS NOT NULL)
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_session_impersonation_operator
    ON ab_user_session (tenant_id, initiated_by_user_id, revoked, impersonation_expires_at)
    WHERE session_kind = 'impersonation';

-- Email is an optional verified login identifier. Case-only duplicates are not distinct accounts.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ab_user
         WHERE deleted_flag = FALSE AND email IS NOT NULL AND BTRIM(email) <> ''
         GROUP BY LOWER(BTRIM(email))
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot enforce normalized email uniqueness: active users contain duplicate email addresses';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_email_normalized_active
    ON ab_user (LOWER(BTRIM(email)))
    WHERE deleted_flag = FALSE AND email IS NOT NULL AND BTRIM(email) <> '';

INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, resource_type, resource_code,
    action, source, source_ref, status
)
SELECT substring(md5(t.id::text || ':admin.customer.impersonate') from 1 for 26),
       t.id, 'admin.customer.impersonate', 'Customer impersonation',
       'Create short-lived audited sessions that execute as another active member',
       'function', '/api/impersonation-sessions', 'execute',
       'system', 'customer-impersonation-v1', 'active'
  FROM ab_tenant t
 WHERE t.deleted_flag = FALSE
ON CONFLICT (tenant_id, code) DO NOTHING;
