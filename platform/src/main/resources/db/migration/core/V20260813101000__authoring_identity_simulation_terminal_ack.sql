-- Terminal feedback must survive refresh/concurrent recovery reads until the actor dismisses it.
ALTER TABLE ab_authoring_identity_simulation
    ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMPTZ;

-- Historical terminal sessions predate the acknowledgement UX and must not reappear.
UPDATE ab_authoring_identity_simulation
SET acknowledged_at = COALESCE(ended_at, updated_at, CURRENT_TIMESTAMP)
WHERE status IN ('ENDED', 'EXPIRED')
  AND acknowledged_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_authoring_identity_simulation_recoverable
    ON ab_authoring_identity_simulation (
        tenant_id, env_id, actor_user_id, source_session_pid, created_at DESC)
    WHERE status = 'ACTIVE' OR acknowledged_at IS NULL;
