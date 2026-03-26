-- Backfill displayName into ab_meta_model.extension for AuraBot Chinese search.
-- Automatically reads from ab_i18n_resource (zh-CN labels) instead of hardcoded values.
-- This is needed because plugin import stores displayName in i18n system,
-- but builtin__list_models searches extension->>'displayName'.
--
-- Run after plugin import: psql -d aura_boot -f scripts/backfill-model-displayname.sql
-- Idempotent: only updates models where displayName is missing.

-- Strategy 1: Auto-fill from i18n_resource table (covers all models with i18n labels)
UPDATE ab_meta_model m
SET extension = m.extension || jsonb_build_object('displayName', i.value)
FROM ab_i18n_resource i
WHERE i.i18n_key = 'model.' || m.code || '._meta.label'
  AND i.lang = 'zh-CN'
  AND i.value IS NOT NULL
  AND i.value != ''
  AND m.is_current = true
  AND m.deleted_flag = false
  AND (m.extension->>'displayName' IS NULL OR m.extension->>'displayName' = '');

-- Strategy 2: Fallback — use model code as displayName for any remaining without labels
-- (ensures every model has a displayName for AuraBot search)
UPDATE ab_meta_model
SET extension = extension || jsonb_build_object('displayName', replace(code, '_', ' '))
WHERE is_current = true
  AND deleted_flag = false
  AND (extension->>'displayName' IS NULL OR extension->>'displayName' = '');
