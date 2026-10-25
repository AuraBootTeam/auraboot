-- Bootstrap audits retain configuration intent, never setup credentials.
-- Preserve every other legacy configuration field and the bootstrap lifecycle.
UPDATE ab_bootstrap
SET input_params = input_params - 'adminPassword'
WHERE jsonb_typeof(input_params) = 'object'
  AND input_params ? 'adminPassword';
