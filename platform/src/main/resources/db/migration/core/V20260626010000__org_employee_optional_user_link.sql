-- Align organization employee records with the Quote/BOM account SOT:
-- an employee profile may exist before a login account is opened.

ALTER TABLE IF EXISTS mt_org_employee
    ALTER COLUMN org_emp_user_id DROP NOT NULL;

UPDATE ab_meta_model_field_binding binding
SET required = false,
    updated_at = NOW()
FROM ab_meta_model model,
     ab_meta_field field
WHERE binding.model_id = model.id
  AND binding.field_id = field.id
  AND model.code = 'org_employee'
  AND field.code = 'org_emp_user_id'
  AND COALESCE(binding.required, false) = true;
