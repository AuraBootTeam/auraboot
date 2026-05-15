-- ============================================================================
-- 2026-05-15 — namespace announcement priority away from global priority fields
-- ============================================================================
--
-- Plugin field definitions are global by field code. Core announcement's
-- priority is an enum, while platform-admin data-permission priority is an
-- integer. Keep the announcement API DTO field named "priority", but store and
-- import the dynamic field as "announcement_priority".
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_announcement' AND column_name = 'priority'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_announcement' AND column_name = 'announcement_priority'
    ) THEN
        ALTER TABLE ab_announcement RENAME COLUMN priority TO announcement_priority;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_announcement' AND column_name = 'announcement_priority'
    ) THEN
        ALTER TABLE ab_announcement ADD COLUMN announcement_priority VARCHAR(16) NOT NULL DEFAULT 'normal';
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_announcement' AND column_name = 'priority'
    ) THEN
        UPDATE ab_announcement
        SET announcement_priority = COALESCE(NULLIF(priority, ''), announcement_priority)
        WHERE announcement_priority IS NULL OR announcement_priority = '';
    END IF;
END $$;
