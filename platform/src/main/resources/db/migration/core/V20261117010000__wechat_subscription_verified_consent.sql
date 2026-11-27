-- Provider authorization is global to app + openid + template, not copied per tenant.
-- These three tables intentionally have no tenant_id; they are not generic business models.
CREATE TABLE ab_wechat_subscription_event (
    event_key CHAR(64) PRIMARY KEY CHECK (event_key ~ '^[a-f0-9]{64}$'),
    app_id VARCHAR(64) NOT NULL,
    openid VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_kind VARCHAR(16) NOT NULL CHECK (event_kind IN ('POPUP','CHANGE','SENT')),
    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array'),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE ab_wechat_subscription_state (
    app_id VARCHAR(64) NOT NULL,
    openid VARCHAR(128) NOT NULL,
    template_id VARCHAR(128) NOT NULL,
    revoked_through TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (app_id, openid, template_id)
);

CREATE TABLE ab_wechat_subscription_grant (
    grant_reference CHAR(64) PRIMARY KEY CHECK (grant_reference ~ '^[a-f0-9]{64}$'),
    event_key CHAR(64) NOT NULL REFERENCES ab_wechat_subscription_event(event_key),
    app_id VARCHAR(64) NOT NULL,
    openid VARCHAR(128) NOT NULL,
    template_id VARCHAR(128) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > granted_at),
    UNIQUE (event_key, template_id)
);
CREATE INDEX idx_wechat_subscription_grant_principal
    ON ab_wechat_subscription_grant (app_id, openid, template_id, granted_at);

CREATE FUNCTION prevent_wechat_subscription_fact_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Verified WeChat subscription facts are immutable';
END $$;
CREATE TRIGGER trg_wechat_subscription_event_immutable
    BEFORE UPDATE ON ab_wechat_subscription_event FOR EACH ROW EXECUTE FUNCTION prevent_wechat_subscription_fact_change();
CREATE TRIGGER trg_wechat_subscription_grant_immutable
    BEFORE UPDATE ON ab_wechat_subscription_grant FOR EACH ROW EXECUTE FUNCTION prevent_wechat_subscription_fact_change();

COMMENT ON TABLE ab_wechat_subscription_event IS 'Global authenticated provider event receipts; no credentials or raw ciphertext';
COMMENT ON TABLE ab_wechat_subscription_state IS 'Global revocation watermark; out-of-order acceptance cannot restore cancelled grants';
COMMENT ON TABLE ab_wechat_subscription_grant IS 'One-time provider-confirmed facts; not a delivery reservation or proof of delivery';
