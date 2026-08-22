-- Revision-bound impact analysis for Contextual Authoring.
--
-- Impact is orthogonal to sync, validation and workflow state. Results are append-only and bound
-- to one exact ChangeSet revision. Unknown, failed or stale impact can never be published.

ALTER TABLE ab_authoring_change_set
    ADD COLUMN impact_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT chk_authoring_change_set_impact
        CHECK (impact_state IN ('UNKNOWN', 'KNOWN', 'STALE', 'FAILED'));

ALTER TABLE ab_authoring_resource_draft
    ADD COLUMN impact_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD CONSTRAINT chk_authoring_resource_draft_impact
        CHECK (impact_state IN ('UNKNOWN', 'KNOWN', 'STALE', 'FAILED'));

CREATE TABLE ab_authoring_impact_run (
    id                    BIGSERIAL PRIMARY KEY,
    pid                   VARCHAR(26) UNIQUE NOT NULL,
    tenant_id             BIGINT NOT NULL,
    env_id                BIGINT NOT NULL,
    change_set_id         BIGINT NOT NULL,
    change_set_revision   BIGINT NOT NULL,
    resource_draft_id     BIGINT NOT NULL,
    status                VARCHAR(16) NOT NULL,
    analyzer_version      VARCHAR(40) NOT NULL,
    manifest_checksum     VARCHAR(64) NOT NULL,
    snapshot_checksum     VARCHAR(64) NOT NULL,
    dependency_checksum   VARCHAR(64),
    dependencies          JSONB NOT NULL DEFAULT '[]'::jsonb,
    failure_code          VARCHAR(80),
    actor_user_id         BIGINT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_impact_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id),
    CONSTRAINT fk_authoring_impact_resource_draft
        FOREIGN KEY (resource_draft_id) REFERENCES ab_authoring_resource_draft(id),
    CONSTRAINT fk_authoring_impact_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_impact_revision CHECK (change_set_revision > 0),
    CONSTRAINT chk_authoring_impact_status CHECK (status IN ('KNOWN', 'FAILED')),
    CONSTRAINT chk_authoring_impact_dependencies CHECK (jsonb_typeof(dependencies) = 'array'),
    CONSTRAINT chk_authoring_impact_result CHECK (
        (status = 'KNOWN' AND dependency_checksum IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND dependency_checksum IS NULL AND failure_code IS NOT NULL))
);

CREATE INDEX idx_authoring_impact_revision
    ON ab_authoring_impact_run
       (tenant_id, env_id, change_set_id, change_set_revision, created_at DESC, id DESC);

CREATE TRIGGER trg_authoring_impact_run_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_impact_run
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

COMMENT ON TABLE ab_authoring_impact_run IS
    'Append-only dependency impact result bound to one immutable ChangeSet revision';
COMMENT ON COLUMN ab_authoring_impact_run.dependencies IS
    'Metadata-only resource fingerprints; no business record values are persisted';
