-- Older customer-save tracking used uppercase resource names against lowercase storage.
-- Its false flags cannot distinguish untouched vendor content from customer edits.
-- Preserve existing page/dashboard definitions until an operator explicitly reviews an overwrite.
-- Fresh imports after this migration continue to track true unmodified baselines normally.
UPDATE ab_plugin_resource SET user_modified=true
WHERE resource_type IN ('page','dashboard') AND user_modified=false;
