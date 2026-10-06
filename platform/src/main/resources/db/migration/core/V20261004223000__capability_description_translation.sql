-- Preserve bilingual capability hints across plugin imports and reloads.
ALTER TABLE ab_permission_capability ADD COLUMN description_en TEXT;
