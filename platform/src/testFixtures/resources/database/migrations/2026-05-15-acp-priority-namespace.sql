-- ============================================================================
-- 2026-05-15 — namespace ACP priority columns away from global priority fields
-- ============================================================================
--
-- The meta field registry is global per tenant. ACP originally declared a bare
-- "priority" integer field for missions, object aliases, and semantic terms.
-- Other plugins can legitimately own their own "priority" field with a
-- different data type, so ACP uses "acp_priority" from this point forward.
--
-- Fresh databases get the renamed columns from schema.sql. This migration keeps
-- long-lived development databases aligned without losing existing priorities.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_mission' AND column_name = 'priority'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_mission' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_mission RENAME COLUMN priority TO acp_priority;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_mission' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_mission ADD COLUMN acp_priority INTEGER DEFAULT 0;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_mission' AND column_name = 'priority'
    ) THEN
        UPDATE ab_mission SET acp_priority = priority WHERE acp_priority IS NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_object_alias' AND column_name = 'priority'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_object_alias' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_object_alias RENAME COLUMN priority TO acp_priority;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_object_alias' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_object_alias ADD COLUMN acp_priority INTEGER DEFAULT 0;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_object_alias' AND column_name = 'priority'
    ) THEN
        UPDATE ab_object_alias SET acp_priority = priority WHERE acp_priority IS NULL;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_semantic_term' AND column_name = 'priority'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_semantic_term' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_semantic_term RENAME COLUMN priority TO acp_priority;
    ELSIF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_semantic_term' AND column_name = 'acp_priority'
    ) THEN
        ALTER TABLE ab_semantic_term ADD COLUMN acp_priority INTEGER DEFAULT 0;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'ab_semantic_term' AND column_name = 'priority'
    ) THEN
        UPDATE ab_semantic_term SET acp_priority = priority WHERE acp_priority IS NULL;
    END IF;
END $$;
