-- Generic resource writes persist their original protocol response in the owning transaction.
ALTER TABLE ab_open_api_idempotency
    ADD COLUMN response_headers JSONB,
    ADD COLUMN authority_hash CHAR(64),
    ADD COLUMN response_record_pid VARCHAR(64);

ALTER TABLE ab_open_api_idempotency
    ADD CONSTRAINT chk_open_api_response_headers_object
        CHECK (response_headers IS NULL OR jsonb_typeof(response_headers) = 'object');
