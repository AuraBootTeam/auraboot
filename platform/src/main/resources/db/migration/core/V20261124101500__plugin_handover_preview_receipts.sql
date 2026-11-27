-- Preview receipts only. No ownership mutation, execution or retirement state is introduced.
-- Re-versioned from 20261119003000, which landed below versions preserved databases had
-- already recorded. IF NOT EXISTS lets a database that applied that backdated version
-- proceed after `flyway repair` removes it from the schema history.
CREATE TABLE IF NOT EXISTS ab_plugin_handover_batch (
    id BIGSERIAL PRIMARY KEY,
    pid VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    contract_id VARCHAR(128) NOT NULL,
    contract_digest VARCHAR(64) NOT NULL,
    source_plugin_pid VARCHAR(64) NOT NULL,
    source_plugin_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    preview_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status = 'PREVIEWED'),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    UNIQUE (tenant_id, idempotency_key),
    UNIQUE (tenant_id, pid),
    CHECK (expires_at > created_at)
);
CREATE TABLE IF NOT EXISTS ab_plugin_handover_item (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    batch_pid VARCHAR(64) NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type IN ('field', 'dict', 'model_field_binding')),
    resource_code VARCHAR(255) NOT NULL,
    resource_pid VARCHAR(64) NOT NULL,
    target_plugin_pid VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL CHECK (action IN ('MOVE_OWNER', 'VERIFY_REFERENCE')),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    FOREIGN KEY (tenant_id, batch_pid) REFERENCES ab_plugin_handover_batch (tenant_id, pid),
    UNIQUE (tenant_id, batch_pid, resource_type, resource_code),
    UNIQUE (tenant_id, batch_pid, ordinal)
);
