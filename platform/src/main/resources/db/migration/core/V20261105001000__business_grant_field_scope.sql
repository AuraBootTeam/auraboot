ALTER TABLE ab_role_permission ADD COLUMN field_scope JSONB;

ALTER TABLE ab_role_permission ADD CONSTRAINT chk_role_permission_field_scope_object
    CHECK (field_scope IS NULL OR jsonb_typeof(field_scope) = 'object');

COMMENT ON COLUMN ab_role_permission.field_scope IS
    'Fields on the same complete grant: modelCode, readFields and writeFields; SQL NULL retains legacy semantics.';
