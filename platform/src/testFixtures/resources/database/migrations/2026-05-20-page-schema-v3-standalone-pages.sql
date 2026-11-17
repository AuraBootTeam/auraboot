-- ============================================================================
-- 2026-05-20 — allow standalone PageSchema V3 pages without a bound model
-- ============================================================================
--
-- Recursive PageSchema V3 allows dashboard and composite pages to be independent
-- from a business model. The API DTO already treats modelCode as optional, so
-- the storage schema must no longer require ab_page_schema.model_code.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'ab_page_schema'
          AND column_name = 'model_code'
          AND is_nullable = 'NO'
    ) THEN
        ALTER TABLE ab_page_schema ALTER COLUMN model_code DROP NOT NULL;
    END IF;
END $$;
