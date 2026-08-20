-- Complete the governed overlay order and production-drift execution ledger.

ALTER TABLE ab_saved_view
    ADD COLUMN role_id VARCHAR(26);

ALTER TABLE ab_saved_view
    DROP CONSTRAINT chk_saved_view_scope,
    ADD CONSTRAINT chk_saved_view_scope
        CHECK (scope IN ('personal', 'team', 'role', 'global')),
    ADD CONSTRAINT chk_saved_view_scope_owner
        CHECK ((scope = 'personal' AND owner_id IS NOT NULL)
            OR (scope = 'team' AND team_id IS NOT NULL)
            OR (scope = 'role' AND role_id IS NOT NULL)
            OR scope = 'global');

CREATE INDEX idx_saved_view_role
    ON ab_saved_view(tenant_id, role_id)
    WHERE scope = 'role' AND deleted_flag = FALSE;

COMMENT ON COLUMN ab_saved_view.scope IS
    'Overlay scope: personal, team, role, or tenant-wide global';
COMMENT ON COLUMN ab_saved_view.role_id IS
    'Role PID for role-scoped view overlays';

ALTER TABLE ab_promotion_unit
    ADD COLUMN drift_execution_status VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN drift_execution_pid VARCHAR(26),
    ADD COLUMN drift_execution_payload JSONB,
    ADD CONSTRAINT chk_promotion_unit_drift_execution_status
        CHECK (drift_execution_status IN (
            'NONE', 'PREPARED', 'DEFERRED', 'BACKPORTED', 'APPLIED')),
    ADD CONSTRAINT chk_promotion_unit_drift_execution_payload
        CHECK (drift_execution_payload IS NULL
            OR jsonb_typeof(drift_execution_payload) = 'object');

ALTER TABLE ab_promotion
    ADD COLUMN parent_promotion_pid VARCHAR(26),
    ADD COLUMN origin_drift_decision_pid VARCHAR(26);

COMMENT ON COLUMN ab_promotion.parent_promotion_pid IS
    'Promotion whose governed BACKPORT decision created this reverse plan';
COMMENT ON COLUMN ab_promotion.origin_drift_decision_pid IS
    'Append-only decision identity that authorized creation of this plan';

ALTER TABLE ab_promotion_drift_event
    DROP CONSTRAINT chk_promotion_drift_event_type,
    ADD CONSTRAINT chk_promotion_drift_event_type
        CHECK (event_type IN ('DETECTED', 'DECIDED', 'EXECUTED', 'STALE', 'APPLIED'));

COMMENT ON COLUMN ab_promotion_unit.drift_execution_status IS
    'Durable executor state for REBASE, BACKPORT, KEEP_OVERRIDE, or OVERWRITE';
COMMENT ON COLUMN ab_promotion_unit.drift_execution_pid IS
    'Execution identity or generated reverse-promotion identity';
COMMENT ON COLUMN ab_promotion_unit.drift_execution_payload IS
    'Server-owned metadata or prepared rebase snapshot; never supplied by the browser';
