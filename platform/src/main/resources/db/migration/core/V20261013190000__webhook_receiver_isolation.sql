-- Durable receiver-origin bulkheads; HTTP never holds a database lock.
ALTER TABLE ab_webhook_delivery_log ADD COLUMN receiver_key VARCHAR(320);
CREATE TABLE ab_webhook_receiver_lane (
    tenant_id BIGINT NOT NULL,
    receiver_key VARCHAR(320) NOT NULL,
    lease_token VARCHAR(128),
    lease_until TIMESTAMPTZ,
    ready_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
    last_claimed_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
    PRIMARY KEY (tenant_id, receiver_key)
);

-- Existing delivery state is migrated once by Flyway, never repaired at startup.
WITH origins AS (
    SELECT d.id, d.subscription_pid,
           regexp_match(COALESCE(s.target_url, d.request_url, ''),
               '^(https?)://(\[[0-9a-f:.]+]|[a-z0-9.-]+)(?::([0-9]{1,5}))?(?:[/?#]|$)', 'i') AS parts
    FROM ab_webhook_delivery_log d
    LEFT JOIN ab_webhook_subscription s ON s.tenant_id=d.tenant_id AND s.pid=d.subscription_pid
)
UPDATE ab_webhook_delivery_log d SET receiver_key = CASE
    WHEN parts IS NOT NULL AND length(parts[2]) <= 255
         AND (parts[3] IS NULL OR parts[3]::int BETWEEN 1 AND 65535)
    THEN lower(parts[1]) || '://' || lower(parts[2]) || ':' ||
         COALESCE(parts[3]::int, CASE WHEN lower(parts[1])='https' THEN 443 ELSE 80 END)::text
    ELSE 'subscription:' || o.subscription_pid END
FROM origins o WHERE d.id=o.id;
ALTER TABLE ab_webhook_delivery_log ALTER COLUMN receiver_key SET NOT NULL;
INSERT INTO ab_webhook_receiver_lane(tenant_id, receiver_key)
SELECT DISTINCT tenant_id, receiver_key FROM ab_webhook_delivery_log;
CREATE INDEX idx_webhook_receiver_ready ON ab_webhook_delivery_log
    (tenant_id, receiver_key, created_at, id) WHERE delivery_status IN ('pending','processing');
