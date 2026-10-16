-- URI userinfo and zero-padded ports must not bypass an origin's shared bulkhead.
WITH origins AS (
    SELECT d.id, d.subscription_pid, regexp_match(
        regexp_replace(COALESCE(s.target_url,d.request_url,''), '^(https?://)[^/?#]*@', '\1', 'i'),
        '^(https?)://(\[[0-9a-f:.]+]|[a-z0-9.-]+)(?::([0-9]+))?(?:[/?#]|$)', 'i') AS p
    FROM ab_webhook_delivery_log d
    LEFT JOIN ab_webhook_subscription s ON s.tenant_id=d.tenant_id AND s.pid=d.subscription_pid
), parsed AS (
    SELECT *, CASE WHEN p[3] IS NULL THEN CASE WHEN lower(p[1])='https' THEN 443 ELSE 80 END
                   WHEN length(ltrim(p[3],'0')) BETWEEN 1 AND 5 THEN p[3]::int ELSE 0 END AS port
    FROM origins
)
UPDATE ab_webhook_delivery_log d SET receiver_key = CASE
    WHEN p IS NOT NULL AND length(p[2])<=255 AND port BETWEEN 1 AND 65535
    THEN lower(p[1]) || '://' || lower(p[2]) || ':' || port::text
    ELSE 'subscription:' || o.subscription_pid END
FROM parsed o WHERE d.id=o.id;
INSERT INTO ab_webhook_receiver_lane(tenant_id,receiver_key)
SELECT DISTINCT tenant_id,receiver_key FROM ab_webhook_delivery_log ON CONFLICT DO NOTHING;
WITH active AS (
    SELECT DISTINCT ON (tenant_id,receiver_key) tenant_id,receiver_key,lease_token,lease_until
    FROM ab_webhook_delivery_log WHERE delivery_status='processing' AND lease_until>clock_timestamp()
    ORDER BY tenant_id,receiver_key,lease_until DESC,id
)
UPDATE ab_webhook_receiver_lane l SET lease_token=a.lease_token,lease_until=a.lease_until
FROM active a WHERE l.tenant_id=a.tenant_id AND l.receiver_key=a.receiver_key
    AND (l.lease_until IS NULL OR l.lease_until<a.lease_until);
