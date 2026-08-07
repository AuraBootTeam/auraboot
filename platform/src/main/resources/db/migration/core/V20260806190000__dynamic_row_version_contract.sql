-- Dynamic command concurrency contract.
--
-- Runtime-created mt_* tables predate the trusted expectedVersion command contract and may not
-- have a row_version column. Upgrade all existing physical dynamic tables here; newly imported
-- models receive the same column from SchemaManagementServiceImpl.
DO $$
DECLARE
    dynamic_table RECORD;
BEGIN
    FOR dynamic_table IN
        SELECT table_schema, table_name
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_type = 'BASE TABLE'
          AND table_name LIKE 'mt\_%' ESCAPE '\'
    LOOP
        EXECUTE format(
            'ALTER TABLE %I.%I ADD COLUMN IF NOT EXISTS row_version INTEGER NOT NULL DEFAULT 1',
            dynamic_table.table_schema,
            dynamic_table.table_name
        );
    END LOOP;
END
$$;
