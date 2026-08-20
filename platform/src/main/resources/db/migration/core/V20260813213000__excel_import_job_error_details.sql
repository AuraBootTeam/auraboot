ALTER TABLE ab_import_job
    ADD COLUMN IF NOT EXISTS error_details TEXT;
