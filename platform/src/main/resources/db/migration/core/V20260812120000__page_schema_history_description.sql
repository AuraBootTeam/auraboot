ALTER TABLE ab_page_schema_history
    ADD COLUMN IF NOT EXISTS description TEXT;

COMMENT ON COLUMN ab_page_schema_history.description
    IS 'Human-readable reason recorded for a page schema version or snapshot action';
