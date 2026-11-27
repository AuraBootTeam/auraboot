-- Authenticated application observations are not provider consent and cannot replenish quota.
CREATE TABLE ab_wechat_subscription_observation (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    app_id VARCHAR(64) NOT NULL,
    identity_pid VARCHAR(26) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    results JSONB NOT NULL CHECK (jsonb_typeof(results) = 'array'
        AND jsonb_array_length(results) BETWEEN 1 AND 20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, user_id, app_id, idempotency_key)
);
CREATE FUNCTION prevent_wechat_subscription_observation_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'WeChat client subscription observations are immutable';
END $$;
CREATE TRIGGER trg_wechat_subscription_observation_immutable
    BEFORE UPDATE ON ab_wechat_subscription_observation FOR EACH ROW EXECUTE FUNCTION prevent_wechat_subscription_observation_change();
COMMENT ON TABLE ab_wechat_subscription_observation IS 'Tenant/user-owned client observations; never trusted provider authorization or delivery proof';
