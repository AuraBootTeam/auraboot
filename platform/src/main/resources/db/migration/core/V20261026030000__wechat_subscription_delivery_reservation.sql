-- A provider-confirmed one-time grant belongs to at most one delivery across all tenants.
-- Ownership is append-only, including failed/unknown sends: never release or recycle provider quota.
CREATE TABLE ab_wechat_subscription_reservation (
    tenant_id BIGINT NOT NULL CHECK (tenant_id > 0),
    resolution_reference VARCHAR(512) NOT NULL,
    reservation_reference CHAR(64) NOT NULL UNIQUE CHECK (reservation_reference ~ '^[a-f0-9]{64}$'),
    grant_reference CHAR(64) NOT NULL UNIQUE REFERENCES ab_wechat_subscription_grant(grant_reference),
    source_version VARCHAR(128) NOT NULL,
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    reserved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, resolution_reference)
);
CREATE TRIGGER trg_wechat_subscription_reservation_immutable
    BEFORE UPDATE ON ab_wechat_subscription_reservation FOR EACH ROW EXECUTE FUNCTION prevent_wechat_subscription_fact_change();
COMMENT ON TABLE ab_wechat_subscription_reservation IS 'Immutable delivery-bound ownership of one verified provider grant; no replacement after unknown or failed delivery';
