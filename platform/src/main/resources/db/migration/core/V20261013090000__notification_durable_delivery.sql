-- Reuse the platform notification history for durable external intents. Legacy rows remain unchanged.
ALTER TABLE ab_notification_send_log ALTER COLUMN channel TYPE VARCHAR(32);
ALTER TABLE ab_notification_send_log
    ADD COLUMN delivery_key TEXT,
    ADD COLUMN request_hash CHAR(64),
    ADD COLUMN resolution_reference TEXT,
    ADD COLUMN configuration_hash CHAR(64),
    ADD COLUMN request_snapshot JSONB,
    ADD COLUMN attempt_fence UUID,
    ADD COLUMN attempt_started_at TIMESTAMPTZ,
    ADD COLUMN attempt_lease_until TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN provider_code BIGINT;

CREATE UNIQUE INDEX uq_notification_durable_identity
    ON ab_notification_send_log(tenant_id, channel, delivery_key) WHERE delivery_key IS NOT NULL;
CREATE INDEX idx_notification_durable_pending
    ON ab_notification_send_log(tenant_id, id) WHERE delivery_key IS NOT NULL AND status = 'pending';
ALTER TABLE ab_notification_send_log ADD CONSTRAINT ck_notification_durable_shape CHECK (
    delivery_key IS NULL OR (
        tenant_id > 0 AND channel = 'wechat_subscription' AND delivery_key <> ''
        AND request_hash IS NOT NULL AND request_hash ~ '^[a-f0-9]{64}$'
        AND configuration_hash IS NOT NULL AND configuration_hash ~ '^[a-f0-9]{64}$'
        AND resolution_reference IS NOT NULL AND resolution_reference <> ''
        AND request_snapshot IS NOT NULL AND jsonb_typeof(request_snapshot) = 'object'
        AND status IN ('pending','attempting','accepted','rejected','unknown','not_attempted')
        AND ((status = 'pending' AND attempt_fence IS NULL AND attempt_started_at IS NULL)
          OR (status <> 'pending' AND attempt_fence IS NOT NULL AND attempt_started_at IS NOT NULL))
    )
);
CREATE FUNCTION protect_notification_durable_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.delivery_key IS NOT NULL THEN
        IF ROW(NEW.tenant_id,NEW.channel,NEW.delivery_key,NEW.request_hash,NEW.resolution_reference,
               NEW.configuration_hash,NEW.request_snapshot,NEW.recipient)
           IS DISTINCT FROM
           ROW(OLD.tenant_id,OLD.channel,OLD.delivery_key,OLD.request_hash,OLD.resolution_reference,
               OLD.configuration_hash,OLD.request_snapshot,OLD.recipient) THEN
            RAISE EXCEPTION 'Immutable notification delivery identity changed';
        END IF;
        IF OLD.status IN ('accepted','rejected','unknown','not_attempted') AND NEW.status <> OLD.status THEN
            RAISE EXCEPTION 'Terminal notification delivery cannot be reopened';
        END IF;
        IF OLD.status = 'attempting' AND NEW.status = 'pending' THEN
            RAISE EXCEPTION 'Attempted notification delivery cannot be reclaimed';
        END IF;
        IF OLD.status <> 'pending' AND NEW.attempt_fence IS DISTINCT FROM OLD.attempt_fence THEN
            RAISE EXCEPTION 'Notification attempt fence is immutable';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_notification_durable_intent BEFORE UPDATE ON ab_notification_send_log
    FOR EACH ROW EXECUTE FUNCTION protect_notification_durable_intent();
COMMENT ON COLUMN ab_notification_send_log.delivery_key IS 'Nonnull only for durable external notification intents; legacy logs use NULL';
COMMENT ON COLUMN ab_notification_send_log.request_snapshot IS 'Host-verified immutable request; never credentials, access tokens or raw provider diagnostics';
