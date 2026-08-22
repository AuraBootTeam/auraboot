-- Revision-bound validation facts for Contextual Authoring.
--
-- Saving a draft and validating it are independent. Every validation attempt is append-only and
-- bound to one exact ChangeSet revision; failures keep the authoring workspace editable.

CREATE TABLE ab_authoring_validation_run (
    id                    BIGSERIAL PRIMARY KEY,
    pid                   VARCHAR(26) UNIQUE NOT NULL,
    tenant_id             BIGINT NOT NULL,
    env_id                BIGINT NOT NULL,
    change_set_id         BIGINT NOT NULL,
    change_set_revision   BIGINT NOT NULL,
    resource_draft_id     BIGINT NOT NULL,
    status                VARCHAR(16) NOT NULL,
    validator_version     VARCHAR(40) NOT NULL,
    manifest_checksum     VARCHAR(64) NOT NULL,
    snapshot_checksum     VARCHAR(64) NOT NULL,
    error_count           INTEGER NOT NULL,
    issues                JSONB NOT NULL DEFAULT '[]'::jsonb,
    actor_user_id         BIGINT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_authoring_validation_change_set
        FOREIGN KEY (change_set_id) REFERENCES ab_authoring_change_set(id),
    CONSTRAINT fk_authoring_validation_resource_draft
        FOREIGN KEY (resource_draft_id) REFERENCES ab_authoring_resource_draft(id),
    CONSTRAINT fk_authoring_validation_env
        FOREIGN KEY (env_id) REFERENCES ab_environment(id),
    CONSTRAINT chk_authoring_validation_revision
        CHECK (change_set_revision > 0),
    CONSTRAINT chk_authoring_validation_status
        CHECK (status IN ('VALID', 'INVALID')),
    CONSTRAINT chk_authoring_validation_error_count
        CHECK (error_count >= 0
            AND ((status = 'VALID' AND error_count = 0)
                OR (status = 'INVALID' AND error_count > 0))),
    CONSTRAINT chk_authoring_validation_issues
        CHECK (jsonb_typeof(issues) = 'array')
);

CREATE INDEX idx_authoring_validation_revision
    ON ab_authoring_validation_run
       (tenant_id, env_id, change_set_id, change_set_revision, created_at DESC, id DESC);

CREATE TRIGGER trg_authoring_validation_run_append_only
    BEFORE UPDATE OR DELETE ON ab_authoring_validation_run
    FOR EACH ROW EXECUTE FUNCTION ab_authoring_reject_history_mutation();

COMMENT ON TABLE ab_authoring_validation_run IS
    'Append-only server validation result bound to one immutable ChangeSet revision';
COMMENT ON COLUMN ab_authoring_validation_run.issues IS
    'Location-only issue metadata; rejected values and business record data are not persisted';
