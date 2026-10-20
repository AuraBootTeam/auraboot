-- Preserve existing in-flight attempts when upgrading populated queues.
-- New workers must not start another send to an origin still leased by the old generation.
WITH active AS (
    SELECT DISTINCT ON (tenant_id,receiver_key) tenant_id,receiver_key,lease_token,lease_until
    FROM ab_webhook_delivery_log
    WHERE delivery_status='processing' AND lease_until>clock_timestamp()
    ORDER BY tenant_id,receiver_key,lease_until DESC,id
)
UPDATE ab_webhook_receiver_lane l SET lease_token=a.lease_token,lease_until=a.lease_until
FROM active a WHERE l.tenant_id=a.tenant_id AND l.receiver_key=a.receiver_key
    AND (l.lease_until IS NULL OR l.lease_until<a.lease_until);
