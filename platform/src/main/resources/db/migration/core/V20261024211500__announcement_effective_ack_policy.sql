-- A resolved form default preserves important-notice acknowledgement without overwriting an explicit opt-out.
ALTER TABLE ab_announcement ADD COLUMN ack_required_effective BOOLEAN GENERATED ALWAYS AS
    (COALESCE((draft_settings->>'acknowledgementRequired')::boolean,announcement_priority='important')) STORED;

-- Native file widgets use server-derived presentation metadata, not bare file identifiers.
ALTER TABLE ab_announcement DROP COLUMN attachments;
ALTER TABLE ab_announcement ADD COLUMN attachments JSONB GENERATED ALWAYS AS
    (COALESCE(draft_settings->'attachmentMetadata','[]'::jsonb)) STORED;
