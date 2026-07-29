-- Governed proactive digital employees.
--
-- Schedules/events are trigger sources, never execution identities. These
-- columns make the employee, manager scope, quiet hours, release policy and
-- daily budget explicit and auditable instead of hiding them inside task JSON.

ALTER TABLE ab_agent_definition
    ADD COLUMN IF NOT EXISTS proactive_policy JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ab_agent_schedule
    ADD COLUMN IF NOT EXISTS agent_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS manager_member_id BIGINT,
    ADD COLUMN IF NOT EXISTS quiet_hours_start TIME,
    ADD COLUMN IF NOT EXISTS quiet_hours_end TIME,
    ADD COLUMN IF NOT EXISTS daily_run_budget INTEGER NOT NULL DEFAULT 24,
    ADD COLUMN IF NOT EXISTS concurrency_limit INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS missed_run_policy VARCHAR(20) NOT NULL DEFAULT 'skip',
    ADD COLUMN IF NOT EXISTS agent_release_pid VARCHAR(26),
    ADD COLUMN IF NOT EXISTS last_block_reason VARCHAR(100);

ALTER TABLE ab_agent_schedule
    DROP CONSTRAINT IF EXISTS chk_agent_schedule_daily_run_budget;
ALTER TABLE ab_agent_schedule
    ADD CONSTRAINT chk_agent_schedule_daily_run_budget
        CHECK (daily_run_budget > 0);

ALTER TABLE ab_agent_schedule
    DROP CONSTRAINT IF EXISTS chk_agent_schedule_concurrency_limit;
ALTER TABLE ab_agent_schedule
    ADD CONSTRAINT chk_agent_schedule_concurrency_limit
        CHECK (concurrency_limit > 0);

ALTER TABLE ab_agent_schedule
    DROP CONSTRAINT IF EXISTS chk_agent_schedule_missed_run_policy;
ALTER TABLE ab_agent_schedule
    ADD CONSTRAINT chk_agent_schedule_missed_run_policy
        CHECK (missed_run_policy IN ('skip', 'catch_up_once'));

CREATE INDEX IF NOT EXISTS idx_agent_schedule_agent
    ON ab_agent_schedule (tenant_id, agent_code)
    WHERE deleted_flag = FALSE;

CREATE TABLE IF NOT EXISTS ab_agent_proactive_usage (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    agent_code VARCHAR(64) NOT NULL,
    usage_date DATE NOT NULL,
    run_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_proactive_usage
        UNIQUE (tenant_id, agent_code, usage_date),
    CONSTRAINT chk_agent_proactive_usage_count
        CHECK (run_count >= 0)
);

COMMENT ON COLUMN ab_agent_definition.proactive_policy IS
    'Release-governed event trigger policy: timezone, quiet hours, daily budget, manager scope and allowed channels';
COMMENT ON COLUMN ab_agent_schedule.agent_code IS
    'Explicit proactive digital employee; task_template is input only and never an identity source';
COMMENT ON COLUMN ab_agent_schedule.manager_member_id IS
    'Human manager scope used for proactive result/approval routing';
COMMENT ON COLUMN ab_agent_schedule.agent_release_pid IS
    'Optional fixed immutable Agent Release; null follows the current deployment';
COMMENT ON TABLE ab_agent_proactive_usage IS
    'Atomic per-employee daily proactive run budget shared by schedule and event triggers';

-- Proactive policy belongs to the immutable Agent Release. Replacing the
-- canonical spec function ensures editing it produces a new release hash and
-- existing runs remain pinned to their old policy.
CREATE OR REPLACE FUNCTION ab_agent_release_spec(source ab_agent_definition)
RETURNS JSONB
LANGUAGE SQL
IMMUTABLE
AS $$
    SELECT jsonb_strip_nulls(jsonb_build_object(
        'agent_definition_pid', source.pid,
        'agent_code', source.agent_code,
        'name', source.name,
        'description', source.description,
        'avatar_url', source.avatar_url,
        'agent_type', source.agent_type,
        'model', source.model,
        'system_prompt', source.system_prompt,
        'tools', source.tools,
        'skills', source.skills,
        'guardrails', source.guardrails,
        'soul_profile', source.soul_profile,
        'personality', source.personality,
        'expertise', source.expertise,
        'communication_style', source.communication_style,
        'boundaries', source.boundaries,
        'soul_goals', source.soul_goals,
        'allowed_models', source.allowed_models,
        'allowed_operations', source.allowed_operations,
        'knowledge_base_ids', source.knowledge_base_ids,
        'max_tools', source.max_tools,
        'max_concurrent_runs', source.max_concurrent_runs,
        'execution_timeout_seconds', source.execution_timeout_seconds,
        'event_triggers', source.event_triggers,
        'execution_config', source.execution_config,
        'auto_reply_mode', source.auto_reply_mode,
        'visibility', source.visibility,
        'proactive_policy', source.proactive_policy
    ))
$$;

COMMENT ON COLUMN ab_agent_run.context_envelope IS
    'Secret-free context-envelope/v2 JSON; its SHA-256 is stored in context_envelope_hash';
COMMENT ON COLUMN ab_agent_run.context_envelope_hash IS
    'SHA-256 of the immutable context-envelope/v2 identity, capability, knowledge and budget snapshot';
