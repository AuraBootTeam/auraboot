-- Separate the execution actor from the subject that initiated the work.
--
-- actor_id remains the stable agent_code for compatibility. actor_user_id /
-- actor_member_id are the IAM identity whose permissions and data scope were
-- used. initiator_* is attribution only. on_behalf_of_user_id remains a
-- compatibility projection of initiator_user_id for existing readers.
ALTER TABLE ab_agent_action
    ADD COLUMN IF NOT EXISTS actor_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS actor_member_id BIGINT,
    ADD COLUMN IF NOT EXISTS initiator_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS initiator_member_id BIGINT,
    ADD COLUMN IF NOT EXISTS principal_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS delegation_grant_id VARCHAR(26);

COMMENT ON COLUMN ab_agent_action.actor_user_id IS
    'IAM user whose permissions and data scope executed this action';
COMMENT ON COLUMN ab_agent_action.actor_member_id IS
    'Tenant member whose roles and organizational scope executed this action';
COMMENT ON COLUMN ab_agent_action.initiator_user_id IS
    'Human/system user that caused the execution; attribution, not runtime authority';
COMMENT ON COLUMN ab_agent_action.initiator_member_id IS
    'Tenant member that caused the execution; attribution, not runtime authority';
COMMENT ON COLUMN ab_agent_action.principal_type IS
    'digital_employee | human_delegated | system | sandbox';
COMMENT ON COLUMN ab_agent_action.delegation_grant_id IS
    'Explicit delegation grant when one exists';
COMMENT ON COLUMN ab_agent_action.on_behalf_of_user_id IS
    'Compatibility projection of initiator_user_id; use actor_user_id for runtime authority';

CREATE INDEX IF NOT EXISTS idx_agent_action_execution_principal
    ON ab_agent_action (tenant_id, actor_user_id, actor_member_id, executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_action_initiator
    ON ab_agent_action (tenant_id, initiator_user_id, executed_at DESC);

ALTER TABLE ab_agent_run
    ADD COLUMN IF NOT EXISTS actor_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS actor_member_id BIGINT,
    ADD COLUMN IF NOT EXISTS initiator_user_id BIGINT,
    ADD COLUMN IF NOT EXISTS initiator_member_id BIGINT,
    ADD COLUMN IF NOT EXISTS principal_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS agent_release_pid VARCHAR(26),
    ADD COLUMN IF NOT EXISTS context_envelope_hash VARCHAR(64);

COMMENT ON COLUMN ab_agent_run.context_envelope_hash IS
    'SHA-256 of the immutable context-envelope/v1 identity and routing snapshot';

CREATE INDEX IF NOT EXISTS idx_agent_run_execution_principal
    ON ab_agent_run (tenant_id, actor_user_id, actor_member_id, started_at DESC);
