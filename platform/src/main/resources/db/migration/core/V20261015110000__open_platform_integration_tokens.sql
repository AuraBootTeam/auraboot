-- Integration tokens reuse the public Bearer pipeline without OAuth credentials.
ALTER TABLE ab_application_access_token
    ALTER COLUMN credential_id DROP NOT NULL,
    ALTER COLUMN expires_at DROP NOT NULL,
    ADD COLUMN token_kind VARCHAR(16) NOT NULL DEFAULT 'oauth',
    ADD COLUMN token_name VARCHAR(160),
    ADD COLUMN never_expires BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN accept_until TIMESTAMPTZ,
    ADD COLUMN rotated_at TIMESTAMPTZ,
    ADD COLUMN replaced_by_pid VARCHAR(26) REFERENCES ab_application_access_token(pid) ON DELETE SET NULL,
    ADD COLUMN management_key VARCHAR(160),
    ADD CONSTRAINT ck_application_token_kind CHECK (
        (token_kind = 'oauth' AND credential_id IS NOT NULL AND expires_at IS NOT NULL
            AND NOT never_expires AND token_name IS NULL AND management_key IS NULL)
        OR (token_kind = 'integration' AND credential_id IS NULL
            AND token_name IS NOT NULL AND length(trim(token_name)) BETWEEN 1 AND 160
            AND management_key IS NOT NULL
            AND ((never_expires AND expires_at IS NULL) OR (NOT never_expires AND expires_at IS NOT NULL AND expires_at > issued_at)))),
    ADD CONSTRAINT ck_application_token_rotation CHECK (
        (rotated_at IS NULL AND accept_until IS NULL AND replaced_by_pid IS NULL)
        OR (rotated_at IS NOT NULL AND accept_until IS NOT NULL AND accept_until >= rotated_at));
CREATE UNIQUE INDEX uq_application_token_management_key
    ON ab_application_access_token (tenant_id, installation_id, management_key)
    WHERE token_kind = 'integration';
CREATE INDEX idx_application_integration_token_list
    ON ab_application_access_token (tenant_id, installation_id, issued_at DESC)
    WHERE token_kind = 'integration';
