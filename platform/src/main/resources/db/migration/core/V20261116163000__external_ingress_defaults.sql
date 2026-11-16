-- Stable request defaults are retained with installation identity, independently of transport-key TTL.
CREATE TABLE ab_external_ingress_defaults (
    tenant_id BIGINT NOT NULL,
    installation_id BIGINT NOT NULL,
    source_code VARCHAR(64) NOT NULL,
    identity_kind VARCHAR(32) NOT NULL,
    identity_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    envelope JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (installation_id, source_code, identity_kind, identity_hash),
    FOREIGN KEY (tenant_id, installation_id)
        REFERENCES ab_application_installation(tenant_id, id)
);
