-- Permanent external intent fence; no credentials or business payloads are retained here.
CREATE TABLE ab_installed_integration_receipt (
    tenant_id BIGINT NOT NULL,
    installation_pid VARCHAR(100) NOT NULL,
    source_code VARCHAR(64) NOT NULL,
    external_event_id VARCHAR(160) NOT NULL,
    durable_event_id VARCHAR(400) NOT NULL,
    envelope_sha256 CHAR(64) NOT NULL,
    binding_sha256 CHAR(64) NOT NULL,
    command_sha256 CHAR(64) NOT NULL,
    provider_sha256 VARCHAR(71) NOT NULL,
    request_pid VARCHAR(100),
    state VARCHAR(16) NOT NULL CHECK (state IN ('accepting','accepted','completed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id,installation_pid,source_code,external_event_id),
    UNIQUE (tenant_id,durable_event_id),
    CHECK (state='accepting' OR request_pid IS NOT NULL)
);
