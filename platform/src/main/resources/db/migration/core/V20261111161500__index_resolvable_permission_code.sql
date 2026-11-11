-- Match fresh authorization lookups without changing their tenant, soft-delete or case semantics.
CREATE INDEX IF NOT EXISTS idx_permission_resolvable_tenant_code
    ON ab_permission (tenant_id, LOWER(code), created_at DESC)
    WHERE deleted_flag = false OR deleted_flag IS NULL;
