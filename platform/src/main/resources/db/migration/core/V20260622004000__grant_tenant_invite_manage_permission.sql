-- Backfill tenant invite-code management permission for existing tenants.
--
-- default-bootstrap.json already declares org.tenant.invite.manage and grants it
-- to tenant_admin (*) plus operator (explicit code). Existing tenants created
-- before that template entry may lack the materialized ab_permission /
-- ab_role_permission rows, causing invite-code E2E and real admins to hit 403.
--
-- Keep this migration tenant-scoped, additive, and idempotent.

-- Reactivate/update an existing row if it was soft-deleted or drifted.
UPDATE ab_permission p
SET name = 'Manage tenant invite codes',
    description = 'Tenant membership — generate / revoke invite codes',
    resource_type = 'function',
    resource_code = 'org:tenant-invite',
    action = 'manage',
    source = 'system',
    source_ref = 'org',
    status = 'active',
    deleted_flag = FALSE,
    updated_at = NOW()
WHERE p.code = 'org.tenant.invite.manage'
  AND p.tenant_id IN (
      SELECT DISTINCT r.tenant_id
      FROM ab_role r
      WHERE r.tenant_id IS NOT NULL
        AND r.code IN ('tenant_admin', 'operator')
        AND r.status = 'active'
        AND r.deleted_flag = FALSE
  );

-- Create the missing permission row once per tenant that has a default admin/operator role.
INSERT INTO ab_permission (
    pid,
    tenant_id,
    code,
    name,
    description,
    resource_type,
    resource_code,
    action,
    source,
    source_ref,
    status,
    deleted_flag,
    created_at,
    updated_at
)
SELECT
    'tinp' || substr(md5(t.tenant_id::text), 1, 28),
    t.tenant_id,
    'org.tenant.invite.manage',
    'Manage tenant invite codes',
    'Tenant membership — generate / revoke invite codes',
    'function',
    'org:tenant-invite',
    'manage',
    'system',
    'org',
    'active',
    FALSE,
    NOW(),
    NOW()
FROM (
    SELECT DISTINCT r.tenant_id
    FROM ab_role r
    WHERE r.tenant_id IS NOT NULL
      AND r.code IN ('tenant_admin', 'operator')
      AND r.status = 'active'
      AND r.deleted_flag = FALSE
) t
WHERE NOT EXISTS (
    SELECT 1
    FROM ab_permission p
    WHERE p.tenant_id = t.tenant_id
      AND p.code = 'org.tenant.invite.manage'
);

-- Reactivate/update any existing soft-deleted grant rows for default roles.
UPDATE ab_role_permission rp
SET grant_type = 'grant',
    status = 'active',
    deleted_flag = FALSE,
    updated_at = NOW()
FROM ab_role r
JOIN ab_permission p
  ON p.tenant_id = r.tenant_id
 AND p.code = 'org.tenant.invite.manage'
WHERE rp.tenant_id = r.tenant_id
  AND rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.code IN ('tenant_admin', 'operator')
  AND r.status = 'active'
  AND r.deleted_flag = FALSE;

-- Grant the permission to roles matching the default bootstrap contract.
INSERT INTO ab_role_permission (
    pid,
    tenant_id,
    role_id,
    permission_id,
    grant_type,
    status,
    deleted_flag,
    created_at,
    updated_at
)
SELECT
    'tinrp' || substr(md5(r.id::text || ':' || p.id::text), 1, 27),
    r.tenant_id,
    r.id,
    p.id,
    'grant',
    'active',
    FALSE,
    NOW(),
    NOW()
FROM ab_role r
JOIN ab_permission p
  ON p.tenant_id = r.tenant_id
 AND p.code = 'org.tenant.invite.manage'
 AND p.status = 'active'
 AND p.deleted_flag = FALSE
WHERE r.code IN ('tenant_admin', 'operator')
  AND r.status = 'active'
  AND r.deleted_flag = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM ab_role_permission ex
      WHERE ex.tenant_id = r.tenant_id
        AND ex.role_id = r.id
        AND ex.permission_id = p.id
  );
