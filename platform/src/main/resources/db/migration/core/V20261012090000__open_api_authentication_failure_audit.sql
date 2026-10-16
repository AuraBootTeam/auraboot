-- Unknown credentials have no tenant or installation; never invent a machine identity.
ALTER TABLE ab_open_api_call_audit ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE ab_open_api_call_audit ALTER COLUMN application_pid DROP NOT NULL;
ALTER TABLE ab_open_api_call_audit ALTER COLUMN installation_pid DROP NOT NULL;

ALTER TABLE ab_open_api_call_audit ADD CONSTRAINT chk_open_api_audit_identity
CHECK (
    (tenant_id IS NOT NULL AND application_pid IS NOT NULL AND installation_pid IS NOT NULL)
    OR (tenant_id IS NULL AND application_pid IS NULL AND installation_pid IS NULL AND token_pid IS NULL
        AND COALESCE(business_details->>'actorKind' = 'unauthenticated', FALSE)
        AND COALESCE(business_details->>'phase' = 'authentication', FALSE))
);
