-- Document imports persist a stable, auditable profile identity as
-- DOCUMENT:<profileCode>. The original row-import mode column only allowed
-- short values such as insert/update and cannot represent that contract.
ALTER TABLE ab_import_job
    ALTER COLUMN import_mode TYPE VARCHAR(128);
