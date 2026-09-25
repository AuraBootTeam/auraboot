-- Existing raw registrations retain NULL keys; new service registrations always set both fields.
ALTER TABLE ab_application_release
    ADD COLUMN registration_key TEXT,
    ADD COLUMN registration_request_digest TEXT,
    ADD CONSTRAINT chk_application_registration_key CHECK (
        (registration_key IS NULL AND registration_request_digest IS NULL)
        OR (registration_key IS NOT NULL AND registration_request_digest IS NOT NULL
            AND registration_key ~ '^[A-Za-z0-9._:-]{1,128}$'
            AND registration_request_digest ~ '^sha256:[0-9a-f]{64}$')),
    ADD CONSTRAINT uq_application_registration_key UNIQUE (application_id, registration_key);
