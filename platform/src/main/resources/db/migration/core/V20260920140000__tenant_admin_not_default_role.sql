-- P0 (2026-09-20): tenant_admin was marked as the tenant's DEFAULT role, so any user
-- provisioned through POST /api/admin/users with empty roleCodes (RoleAssignmentMode.DEFAULT)
-- silently became a tenant admin. The admin role must never be the default role: clear the
-- flag on every tenant. Long-lived databases carry this flag from the tenant bootstrap
-- (RoleServiceImpl.createDefaultRolesForTenant); fresh databases are unaffected at migrate
-- time (roles are created later at tenant bootstrap, by code that now sets the flag false).
UPDATE ab_role
SET is_default = FALSE,
    updated_at = NOW()
WHERE code = 'tenant_admin'
  AND is_default = TRUE
  AND deleted_flag = FALSE;
