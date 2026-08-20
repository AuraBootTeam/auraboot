-- A browser refresh must restore the one actor-bound simulation for its source session.
-- Resolve any pre-constraint duplicates deterministically before enforcing the invariant.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, env_id, actor_user_id, source_session_pid
               ORDER BY created_at DESC, id DESC
           ) AS active_rank
    FROM ab_authoring_identity_simulation
    WHERE status = 'ACTIVE'
)
UPDATE ab_authoring_identity_simulation simulation
SET status = 'ENDED',
    ended_at = COALESCE(simulation.ended_at, CURRENT_TIMESTAMP),
    row_version = simulation.row_version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM ranked
WHERE simulation.id = ranked.id
  AND ranked.active_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_authoring_identity_simulation_active_session
    ON ab_authoring_identity_simulation (
        tenant_id, env_id, actor_user_id, source_session_pid)
    WHERE status = 'ACTIVE';
