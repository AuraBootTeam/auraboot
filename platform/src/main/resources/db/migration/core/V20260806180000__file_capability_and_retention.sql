-- Split the historical all-in-one file permission and add monotonic retention locks.
-- Existing holders of sys.file.upload retain their prior read/delete/relation access;
-- new product roles can now receive only the minimum capability they need.

ALTER TABLE ab_file
    ADD COLUMN IF NOT EXISTS retention_locked BOOLEAN NOT NULL DEFAULT FALSE;

WITH permission_def(code, name, description, action, suffix) AS (
    VALUES
        ('sys.file.read', 'File read', 'Read tenant-visible file metadata and bytes', 'read', 'r'),
        ('sys.file.delete', 'File delete', 'Delete actor-owned files that are not retention locked', 'delete', 'd'),
        ('sys.file.relation.manage', 'File relation manage', 'Manage file relations on authorized business records', 'manage', 'm')
)
INSERT INTO ab_permission (
    pid, tenant_id, code, name, description, resource_type, resource_code, action,
    source, source_ref, status, deleted_flag, created_at, updated_at
)
SELECT
    'fcp' || old_permission.id || '_' || permission_def.suffix,
    old_permission.tenant_id,
    permission_def.code,
    permission_def.name,
    permission_def.description,
    'function',
    'sys:file',
    permission_def.action,
    'system',
    'system',
    'active',
    FALSE,
    NOW(),
    NOW()
FROM ab_permission old_permission
CROSS JOIN permission_def
WHERE old_permission.code = 'sys.file.upload'
  AND NOT EXISTS (
      SELECT 1
      FROM ab_permission existing
      WHERE existing.tenant_id = old_permission.tenant_id
        AND existing.code = permission_def.code
  );

WITH permission_def(code, suffix) AS (
    VALUES
        ('sys.file.read', 'r'),
        ('sys.file.delete', 'd'),
        ('sys.file.relation.manage', 'm')
)
INSERT INTO ab_role_permission (
    pid, tenant_id, role_id, permission_id, grant_type, status, deleted_flag,
    created_at, updated_at
)
SELECT
    'fcrp' || old_grant.id || '_' || permission_def.suffix,
    old_grant.tenant_id,
    old_grant.role_id,
    new_permission.id,
    old_grant.grant_type,
    'active',
    FALSE,
    NOW(),
    NOW()
FROM ab_role_permission old_grant
JOIN ab_permission old_permission
  ON old_permission.id = old_grant.permission_id
 AND old_permission.tenant_id = old_grant.tenant_id
 AND old_permission.code = 'sys.file.upload'
CROSS JOIN permission_def
JOIN ab_permission new_permission
  ON new_permission.tenant_id = old_grant.tenant_id
 AND new_permission.code = permission_def.code
WHERE old_grant.status = 'active'
  AND old_grant.deleted_flag = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM ab_role_permission existing
      WHERE existing.tenant_id = old_grant.tenant_id
        AND existing.role_id = old_grant.role_id
        AND existing.permission_id = new_permission.id
        AND existing.deleted_flag = FALSE
  );
