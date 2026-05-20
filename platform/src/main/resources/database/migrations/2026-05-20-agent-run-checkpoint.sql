-- 2026-05-20 — append-only durable workflow checkpoint history.
-- Mirrors schema.sql so existing environments can receive the table without a reset.

CREATE TABLE IF NOT EXISTS ab_agent_run_checkpoint (
  id              BIGSERIAL PRIMARY KEY,
  pid             VARCHAR(26) UNIQUE NOT NULL,
  tenant_id       BIGINT NOT NULL,
  run_pid         VARCHAR(26) NOT NULL,
  checkpoint_type VARCHAR(32) NOT NULL,
  step_index      INTEGER,
  reason          VARCHAR(128),
  plan_snapshot   JSONB,
  state_snapshot  JSONB,
  created_at      TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoint_run
  ON ab_agent_run_checkpoint (tenant_id, run_pid, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_run_checkpoint_type
  ON ab_agent_run_checkpoint (tenant_id, checkpoint_type, created_at);

COMMENT ON TABLE ab_agent_run_checkpoint IS 'Append-only durable workflow checkpoint history for agent runs';
