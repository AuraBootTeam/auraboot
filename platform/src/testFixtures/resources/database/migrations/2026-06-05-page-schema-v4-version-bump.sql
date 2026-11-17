-- ============================================================================
-- 2026-06-05 — bump stored ab_page_schema.schema_version to current v4
-- ============================================================================
--
-- The plugin importer historically stamped schema_version = 2 for every imported
-- page, while the web-admin runtime targets schemaVersion 4 (flat blocks + 12-col
-- grid canvas) and migrates 2 -> 3 -> 4 in-memory on every read. The stored label
-- was therefore a stale virtual marker, never reflecting the real runtime version.
--
-- Rendering is version-label-independent (the frontend DslMigrator runs on read and
-- is a no-op for already-flat pages), so this UPDATE is a label-honesty bump, NOT a
-- structural change. After this migration the importer (PluginResourceImporterImpl)
-- stamps the declared version (default current = 4), so fresh imports are already 4;
-- this statement fixes pre-existing rows on upgraded installs.
--
-- Idempotent: safe to re-run; only touches rows not already at 4.
-- ============================================================================

DO $$
BEGIN
    UPDATE ab_page_schema
       SET schema_version = 4
     WHERE schema_version IS DISTINCT FROM 4;
END $$;
