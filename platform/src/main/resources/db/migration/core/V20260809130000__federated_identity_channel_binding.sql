-- Bind federated authentication methods to concrete identity-provider instances.
-- Local methods (password/OTP) keep a NULL provider reference. Federated methods
-- can coexist on one login channel because their uniqueness is instance-scoped.

ALTER TABLE ab_login_channel_auth_method
    ADD COLUMN IF NOT EXISTS identity_provider_instance_id BIGINT;

ALTER TABLE ab_login_channel_auth_method
    DROP CONSTRAINT IF EXISTS uq_login_auth_method;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'fk_login_auth_method_identity_provider'
    ) THEN
        ALTER TABLE ab_login_channel_auth_method
            ADD CONSTRAINT fk_login_auth_method_identity_provider
            FOREIGN KEY (application_id, identity_provider_instance_id)
            REFERENCES ab_identity_provider_instance (application_id, id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_login_auth_method_local
    ON ab_login_channel_auth_method (login_channel_id, auth_method)
    WHERE identity_provider_instance_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_login_auth_method_federated
    ON ab_login_channel_auth_method (login_channel_id, identity_provider_instance_id)
    WHERE identity_provider_instance_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_login_auth_method_identity_provider
    ON ab_login_channel_auth_method (identity_provider_instance_id)
    WHERE identity_provider_instance_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_external_identity_user_instance_active
    ON ab_external_identity_link (user_id, identity_provider_instance_id)
    WHERE unlinked_at IS NULL;

COMMENT ON COLUMN ab_login_channel_auth_method.identity_provider_instance_id IS
    'Concrete IdP instance for OAuth/OIDC/LDAP/SAML; NULL for local password/OTP methods';
