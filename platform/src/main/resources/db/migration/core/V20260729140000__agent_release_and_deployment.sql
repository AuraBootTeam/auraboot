-- Separate the editable Agent blueprint from the immutable runtime release and
-- the tenant deployment that binds that release to an execution identity.

CREATE TABLE IF NOT EXISTS ab_agent_release (
    id                       BIGSERIAL PRIMARY KEY,
    pid                      VARCHAR(26) UNIQUE NOT NULL,
    tenant_id                BIGINT NOT NULL,
    agent_definition_pid     VARCHAR(26) NOT NULL,
    agent_code               VARCHAR(100) NOT NULL,
    release_no               INTEGER NOT NULL,
    release_hash             VARCHAR(64) NOT NULL,
    release_spec             JSONB NOT NULL,
    capability_requirements  JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                   VARCHAR(20) NOT NULL DEFAULT 'published',
    source_updated_at        TIMESTAMPTZ,
    published_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by               BIGINT,
    CONSTRAINT chk_agent_release_status
        CHECK (status IN ('published', 'deprecated')),
    CONSTRAINT chk_agent_release_number
        CHECK (release_no > 0),
    CONSTRAINT uq_agent_release_version
        UNIQUE (tenant_id, agent_code, release_no),
    CONSTRAINT uq_agent_release_hash
        UNIQUE (tenant_id, agent_code, release_hash)
);

CREATE INDEX IF NOT EXISTS idx_agent_release_definition
    ON ab_agent_release (tenant_id, agent_definition_pid, release_no DESC);
CREATE INDEX IF NOT EXISTS idx_agent_release_active
    ON ab_agent_release (tenant_id, agent_code, release_no DESC)
    WHERE status = 'published';

COMMENT ON TABLE ab_agent_release IS
    'Immutable published runtime snapshot of an editable Agent definition';
COMMENT ON COLUMN ab_agent_release.release_spec IS
    'Provider-neutral prompt, tool, skill, knowledge, memory and runtime policy snapshot';
COMMENT ON COLUMN ab_agent_release.release_hash IS
    'SHA-256 over the canonical JSONB runtime snapshot';

CREATE TABLE IF NOT EXISTS ab_agent_deployment (
    id                   BIGSERIAL PRIMARY KEY,
    pid                  VARCHAR(26) UNIQUE NOT NULL,
    tenant_id            BIGINT NOT NULL,
    agent_code           VARCHAR(100) NOT NULL,
    employee_id          BIGINT,
    agent_release_pid    VARCHAR(26) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'active',
    tool_grants          JSONB NOT NULL DEFAULT '[]'::jsonb,
    skill_grants         JSONB NOT NULL DEFAULT '[]'::jsonb,
    knowledge_base_ids   JSONB NOT NULL DEFAULT '[]'::jsonb,
    memory_policy        JSONB NOT NULL DEFAULT '{}'::jsonb,
    channel_policy       JSONB NOT NULL DEFAULT '{}'::jsonb,
    policy_snapshot      JSONB NOT NULL DEFAULT '{}'::jsonb,
    deployed_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           BIGINT,
    updated_by           BIGINT,
    CONSTRAINT fk_agent_deployment_release
        FOREIGN KEY (agent_release_pid) REFERENCES ab_agent_release(pid),
    CONSTRAINT chk_agent_deployment_status
        CHECK (status IN ('active', 'suspended', 'revoked'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_deployment_active
    ON ab_agent_deployment (tenant_id, agent_code)
    WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_deployment_employee
    ON ab_agent_deployment (tenant_id, employee_id)
    WHERE employee_id IS NOT NULL AND status = 'active';
CREATE INDEX IF NOT EXISTS idx_agent_deployment_release
    ON ab_agent_deployment (tenant_id, agent_release_pid);

COMMENT ON TABLE ab_agent_deployment IS
    'Tenant binding from a stable digital-employee/assistant identity to one immutable Agent release';

ALTER TABLE ab_agent_run
    ADD COLUMN IF NOT EXISTS deployment_pid VARCHAR(26),
    ADD COLUMN IF NOT EXISTS context_envelope TEXT;

ALTER TABLE ab_agent_action
    ADD COLUMN IF NOT EXISTS agent_release_pid VARCHAR(26),
    ADD COLUMN IF NOT EXISTS deployment_pid VARCHAR(26);

CREATE INDEX IF NOT EXISTS idx_agent_run_deployment
    ON ab_agent_run (tenant_id, deployment_pid, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_action_deployment
    ON ab_agent_action (tenant_id, deployment_pid, executed_at DESC);

COMMENT ON COLUMN ab_agent_run.context_envelope IS
    'Secret-free context-envelope/v1 JSON; its SHA-256 is stored in context_envelope_hash';

-- Runtime fields are assembled once here and by AgentReleaseService when an
-- operator publishes a later draft. JSONB text has deterministic key ordering,
-- so digest(spec::text) is stable for the same logical snapshot.
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
        'visibility', source.visibility
    ))
$$;

CREATE OR REPLACE FUNCTION ab_publish_initial_agent_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    spec JSONB;
    release_pid VARCHAR(26);
    deployment_pid VARCHAR(26);
    computed_hash VARCHAR(64);
BEGIN
    IF NEW.deleted_flag IS TRUE OR NEW.status <> 'active' THEN
        RETURN NEW;
    END IF;

    spec := ab_agent_release_spec(NEW);
    computed_hash := encode(digest(spec::text, 'sha256'), 'hex');
    release_pid := 'arl_' || substr(
        encode(digest(NEW.tenant_id::text || ':' || NEW.pid, 'sha256'), 'hex'),
        1,
        22);
    deployment_pid := 'adp_' || substr(
        encode(digest(NEW.tenant_id::text || ':' || NEW.agent_code, 'sha256'), 'hex'),
        1,
        22);

    INSERT INTO ab_agent_release (
        pid, tenant_id, agent_definition_pid, agent_code, release_no,
        release_hash, release_spec, capability_requirements, status,
        source_updated_at, published_at, created_by)
    VALUES (
        release_pid, NEW.tenant_id, NEW.pid, NEW.agent_code, 1,
        computed_hash, spec,
        jsonb_build_object(
            'toolCalling', COALESCE(NEW.tools, '') <> '',
            'retrieval', jsonb_array_length(COALESCE(NEW.knowledge_base_ids, '[]'::jsonb)) > 0,
            'thinking', lower(COALESCE(
                NEW.execution_config ->> 'thinking_enabled',
                'false')) = 'true'
        ),
        'published', NEW.updated_at, CURRENT_TIMESTAMP, NEW.created_by)
    ON CONFLICT (tenant_id, agent_code, release_hash) DO NOTHING;

    SELECT pid INTO release_pid
    FROM ab_agent_release
    WHERE tenant_id = NEW.tenant_id
      AND agent_code = NEW.agent_code
      AND ab_agent_release.release_hash = computed_hash
    ORDER BY release_no DESC
    LIMIT 1;

    INSERT INTO ab_agent_deployment (
        pid, tenant_id, agent_code, employee_id, agent_release_pid, status,
        tool_grants, skill_grants, knowledge_base_ids, memory_policy,
        channel_policy, policy_snapshot, created_by)
    VALUES (
        deployment_pid, NEW.tenant_id, NEW.agent_code, NEW.employee_id,
        release_pid, 'active',
        COALESCE(
            to_jsonb(string_to_array(NULLIF(NEW.tools, ''), ',')),
            '[]'::jsonb),
        COALESCE(
            to_jsonb(string_to_array(NULLIF(NEW.skills, ''), ',')),
            '[]'::jsonb),
        COALESCE(NEW.knowledge_base_ids, '[]'::jsonb),
        '{}'::jsonb, '{}'::jsonb,
        jsonb_build_object('source', 'initial-release', 'version', 1),
        NEW.created_by)
    ON CONFLICT (tenant_id, agent_code) WHERE status = 'active'
    DO NOTHING;

    RETURN NEW;
END
$$;

-- The trigger function above cannot be called safely as a plain function
-- because it consumes NEW. Perform the idempotent historical backfill directly.
INSERT INTO ab_agent_release (
    pid, tenant_id, agent_definition_pid, agent_code, release_no,
    release_hash, release_spec, capability_requirements, status,
    source_updated_at, published_at, created_by)
SELECT
    'arl_' || substr(
        encode(digest(d.tenant_id::text || ':' || d.pid, 'sha256'), 'hex'),
        1,
        22),
    d.tenant_id,
    d.pid,
    d.agent_code,
    1,
    encode(digest(ab_agent_release_spec(d)::text, 'sha256'), 'hex'),
    ab_agent_release_spec(d),
    jsonb_build_object(
        'toolCalling', COALESCE(d.tools, '') <> '',
        'retrieval', jsonb_array_length(COALESCE(d.knowledge_base_ids, '[]'::jsonb)) > 0,
        'thinking', lower(COALESCE(
            d.execution_config ->> 'thinking_enabled',
            'false')) = 'true'
    ),
    'published',
    d.updated_at,
    CURRENT_TIMESTAMP,
    d.created_by
FROM ab_agent_definition d
WHERE d.status = 'active'
  AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)
ON CONFLICT (tenant_id, agent_code, release_hash) DO NOTHING;

INSERT INTO ab_agent_deployment (
    pid, tenant_id, agent_code, employee_id, agent_release_pid, status,
    tool_grants, skill_grants, knowledge_base_ids, memory_policy,
    channel_policy, policy_snapshot, created_by)
SELECT
    'adp_' || substr(
        encode(digest(d.tenant_id::text || ':' || d.agent_code, 'sha256'), 'hex'),
        1,
        22),
    d.tenant_id,
    d.agent_code,
    d.employee_id,
    r.pid,
    'active',
    COALESCE(
        to_jsonb(string_to_array(NULLIF(d.tools, ''), ',')),
        '[]'::jsonb),
    COALESCE(
        to_jsonb(string_to_array(NULLIF(d.skills, ''), ',')),
        '[]'::jsonb),
    COALESCE(d.knowledge_base_ids, '[]'::jsonb),
    '{}'::jsonb,
    '{}'::jsonb,
    jsonb_build_object('source', 'migration-backfill', 'version', 1),
    d.created_by
FROM ab_agent_definition d
JOIN ab_agent_release r
  ON r.tenant_id = d.tenant_id
 AND r.agent_code = d.agent_code
 AND r.release_no = 1
WHERE d.status = 'active'
  AND (d.deleted_flag IS NULL OR d.deleted_flag = FALSE)
ON CONFLICT (tenant_id, agent_code) WHERE status = 'active'
DO NOTHING;

DROP TRIGGER IF EXISTS trg_agent_definition_initial_release
    ON ab_agent_definition;
CREATE TRIGGER trg_agent_definition_initial_release
AFTER INSERT ON ab_agent_definition
FOR EACH ROW
EXECUTE FUNCTION ab_publish_initial_agent_release();

CREATE OR REPLACE FUNCTION ab_guard_immutable_agent_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Agent releases are immutable and cannot be deleted';
    END IF;
    IF OLD.pid IS DISTINCT FROM NEW.pid
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.agent_definition_pid IS DISTINCT FROM NEW.agent_definition_pid
       OR OLD.agent_code IS DISTINCT FROM NEW.agent_code
       OR OLD.release_no IS DISTINCT FROM NEW.release_no
       OR OLD.release_hash IS DISTINCT FROM NEW.release_hash
       OR OLD.release_spec IS DISTINCT FROM NEW.release_spec
       OR OLD.capability_requirements IS DISTINCT FROM NEW.capability_requirements
       OR OLD.source_updated_at IS DISTINCT FROM NEW.source_updated_at
       OR OLD.published_at IS DISTINCT FROM NEW.published_at
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.created_by IS DISTINCT FROM NEW.created_by THEN
        RAISE EXCEPTION 'Agent release content is immutable; publish a new release';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS trg_agent_release_immutable
    ON ab_agent_release;
CREATE TRIGGER trg_agent_release_immutable
BEFORE UPDATE OR DELETE ON ab_agent_release
FOR EACH ROW
EXECUTE FUNCTION ab_guard_immutable_agent_release();
