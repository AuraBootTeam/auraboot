-- Private authorization basis; never exposed as a dynamic model or relayed event payload.
CREATE TABLE ab_analytics_deleted_record_basis (
    tenant_id BIGINT NOT NULL,
    event_id VARCHAR(40) NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    target_key VARCHAR(120) NOT NULL,
    record_id BIGINT NOT NULL,
    record_pid VARCHAR(26) NOT NULL,
    created_by BIGINT,
    basis_version INTEGER NOT NULL DEFAULT 1 CHECK (basis_version = 1),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, event_id)
        REFERENCES ab_behavior_outcome_outbox (tenant_id, event_id)
        ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX idx_analytics_deleted_record_target
    ON ab_analytics_deleted_record_basis (tenant_id, model_code, target_key);
COMMENT ON TABLE ab_analytics_deleted_record_basis IS
    'Server-captured identity and owner basis from the same transaction as an analytics deletion outcome; not an authorization grant';
