-- ab_file_relation is not in the TenantLineInnerInterceptor ignoreTable whitelist,
-- so every mapper query against it gets a tenant_id predicate appended. The table
-- previously had no such column, which broke file lookups that go through the
-- relation table (e.g. quote Excel download authorization) with
-- "column tenant_id does not exist". ab_file already carries tenant_id.

ALTER TABLE ab_file_relation ADD COLUMN IF NOT EXISTS tenant_id BIGINT;

-- Backfill stored relations from the tenant of the referenced file.
-- file_id stores ab_file.id rendered as text (see FileServiceImpl.createFileRelations).
UPDATE ab_file_relation r
SET tenant_id = f.tenant_id
FROM ab_file f
WHERE r.file_id = f.id::VARCHAR
  AND r.tenant_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ab_file_relation_tenant
    ON ab_file_relation (tenant_id)
    WHERE deleted_flag = FALSE;
