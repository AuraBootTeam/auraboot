-- Distinguish immutable pointer restoration from new snapshot publication.
ALTER TABLE ab_open_api_publication_audit DROP CONSTRAINT ab_open_api_publication_audit_action_check;
ALTER TABLE ab_open_api_publication_audit ADD CONSTRAINT ab_open_api_publication_audit_action_check
    CHECK (action IN ('draft', 'publish', 'withdraw', 'grant', 'revoke', 'plugin_reactivate', 'plugin_rollback'));
