ALTER TABLE ab_file ADD COLUMN artifact_authorization TEXT;
COMMENT ON COLUMN ab_file.artifact_authorization IS
    'Host-owned business artifact provenance and generated public field view; NULL retains ordinary upload authorization.';
