ALTER TABLE ab_authoring_config_session
    ADD COLUMN workspace_mode VARCHAR(20) NOT NULL DEFAULT 'AUTHORING';

ALTER TABLE ab_authoring_config_session
    ADD CONSTRAINT chk_authoring_config_session_workspace_mode
        CHECK (workspace_mode IN ('AUTHORING', 'OBSERVER', 'REVIEW'));

COMMENT ON COLUMN ab_authoring_config_session.workspace_mode IS
    'Server-enforced authoring surface boundary; REVIEW sessions remain read-only even for designer admins';
