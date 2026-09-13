-- Server-owned provenance for task execution; never exposed as a dynamic model.
-- Composite identity prevents a task from being attributed across tenants.
ALTER TABLE ab_agent_task ADD CONSTRAINT uq_agent_task_tenant_pid UNIQUE (tenant_id, pid);

CREATE TABLE ab_analytics_task_execution (
    tenant_id BIGINT NOT NULL,
    task_pid VARCHAR(26) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    adoption_pid VARCHAR(26) NOT NULL,
    request_key UUID NOT NULL,
    binding JSONB NOT NULL CHECK (jsonb_typeof(binding) = 'object'),
    goal TEXT NOT NULL CHECK (length(goal) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, task_pid),
    UNIQUE (tenant_id, request_key),
    UNIQUE (tenant_id, actor_user_id, adoption_pid),
    FOREIGN KEY (tenant_id, task_pid) REFERENCES ab_agent_task (tenant_id, pid)
);
