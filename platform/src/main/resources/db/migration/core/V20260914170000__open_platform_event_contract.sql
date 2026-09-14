ALTER TABLE ab_webhook_subscription
    ADD COLUMN IF NOT EXISTS event_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS secret_rotated_at TIMESTAMPTZ;

UPDATE ab_webhook_subscription
SET secret_rotated_at = COALESCE(secret_rotated_at, created_at)
WHERE secret IS NOT NULL AND BTRIM(secret) <> '';

ALTER TABLE ab_webhook_subscription
    DROP CONSTRAINT IF EXISTS chk_webhook_event_version_positive;

ALTER TABLE ab_webhook_subscription
    ADD CONSTRAINT chk_webhook_event_version_positive CHECK (event_version > 0);

CREATE INDEX IF NOT EXISTS idx_webhook_subscription_installation_event_version
    ON ab_webhook_subscription (tenant_id, installation_pid, event_type, event_version, enabled);

COMMENT ON COLUMN ab_webhook_subscription.event_version IS
    'Pinned Open Platform Event Catalog schema version; legacy subscriptions default to version 1';
COMMENT ON COLUMN ab_webhook_subscription.secret_rotated_at IS
    'Last instant the outbound webhook signing secret changed; null means unsigned';
