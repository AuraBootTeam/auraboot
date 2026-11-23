-- ab_agent_action was created without the standard audit columns, while the
-- agent-control-plane config model declares created_by/updated_by — every
-- list query SELECTs them and fails with "column does not exist"
-- (found by the 5501 full-page render sweep, 2026-11-23).
ALTER TABLE ab_agent_action ADD COLUMN created_by BIGINT;
ALTER TABLE ab_agent_action ADD COLUMN updated_by BIGINT;
