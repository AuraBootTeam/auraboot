-- Backfill Decision Runtime rollout permissions for existing tenants.
--
-- Fresh tenants get these permissions from tenant-templates/default-bootstrap.json.
-- Long-lived dev/prod tenants need an idempotent backfill so the backend guards
-- and frontend disabled-state checks agree after rollout governance ships.

WITH rollout_permissions(code, name, description, action) AS (
    VALUES
        ('decision.rollout.manage', 'Decision rollout manage', 'Manage Decision Runtime rollout policies', 'manage'),
        ('decision.rollout.promote', 'Decision rollout promote', 'Promote a Decision Runtime rollout candidate to full traffic', 'promote'),
        ('decision.rollout.rollback', 'Decision rollout rollback', 'Rollback a Decision Runtime rollout to baseline traffic', 'rollback')
),
tenants AS (
    SELECT id
    FROM ab_tenant
    WHERE deleted_flag = FALSE
)
INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, category,
    resource_type, resource_code, action, source, status, deleted_flag
)
SELECT
    SUBSTRING(REPLACE(gen_random_uuid()::text, '-', ''), 1, 26),
    tenants.id,
    rollout_permissions.code,
    rollout_permissions.name,
    rollout_permissions.description,
    'decision',
    'decision',
    'rollout',
    rollout_permissions.action,
    'SYSTEM',
    'active',
    FALSE
FROM tenants
CROSS JOIN rollout_permissions
ON CONFLICT (tenant_id, code) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    category = EXCLUDED.category,
    resource_type = EXCLUDED.resource_type,
    resource_code = EXCLUDED.resource_code,
    action = EXCLUDED.action,
    source = EXCLUDED.source,
    status = 'active',
    deleted_flag = FALSE,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO ab_role_permission (
    pid, tenant_id, role_id, permission_id,
    grant_type, priority, status, deleted_flag
)
SELECT
    SUBSTRING(REPLACE(gen_random_uuid()::text, '-', ''), 1, 26),
    role.tenant_id,
    role.id,
    permission.id,
    'grant',
    0,
    'active',
    FALSE
FROM ab_role role
JOIN ab_permission permission
  ON permission.tenant_id = role.tenant_id
 AND permission.code IN (
     'decision.rollout.manage',
     'decision.rollout.promote',
     'decision.rollout.rollback'
 )
 AND permission.deleted_flag = FALSE
WHERE role.code = 'tenant_admin'
  AND role.deleted_flag = FALSE
ON CONFLICT (tenant_id, role_id, permission_id) DO NOTHING;
