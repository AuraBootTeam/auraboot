import { pathToFileURL } from 'node:url';

/** Generate policy only; trusted operators apply it after reviewing account and schema ownership. */
export function shadowBindingRolePolicy({ schema, runtimeRole, shadowRole, ownerRole }) {
  for (const [key, value] of Object.entries({ schema, runtimeRole, shadowRole, ownerRole })) {
    if (typeof value !== 'string' || !/^[a-z_][a-z0-9_]{0,62}$/.test(value) || value.startsWith('pg_')
        || (key !== 'schema' && value === 'public')) throw new Error('Explicit safe schema and role identifiers required');
  }
  if (new Set([runtimeRole, shadowRole, ownerRole]).size !== 3) throw new Error('Three distinct roles required');
  const tables = [
    'ab_application_release',
    'ab_tenant_application_binding',
    'ab_tenant_application_binding_history',
    'ab_application',
    'ab_application_channel_target',
    'ab_application_release_publication',
  ];
  const names = tables.map(name => `"${schema}"."${name}"`);
  return `-- Shadow writer cannot activate or update an active binding, including through raw SQL.
BEGIN;
DO $policy$
DECLARE role_name TEXT; table_name TEXT; column_names TEXT;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['${runtimeRole}', '${shadowRole}'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name AND NOT rolsuper AND NOT rolcreaterole
        AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) THEN
      RAISE EXCEPTION 'Binding account absent or privileged: %', role_name;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=role_name) THEN
      RAISE EXCEPTION 'Binding account must have no inherited or assumable roles: %', role_name;
    END IF;
    IF has_schema_privilege(role_name, '${schema}', 'CREATE') THEN
      RAISE EXCEPTION 'Binding account must not create schema objects: %', role_name;
    END IF;
  END LOOP;
  FOREACH table_name IN ARRAY ARRAY['${tables.join("','")}'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='${schema}' AND c.relname=table_name
        AND c.relkind='r' AND r.rolname='${ownerRole}') THEN
      RAISE EXCEPTION 'Binding table missing or unexpected owner: %', table_name;
    END IF;
    SELECT string_agg(format('%I',a.attname), ', ') INTO column_names FROM pg_attribute a
      WHERE a.attrelid=format('%I.%I','${schema}',table_name)::regclass AND a.attnum>0 AND NOT a.attisdropped;
    EXECUTE format('REVOKE ALL (%s) ON TABLE %I.%I FROM PUBLIC, %I, %I', column_names,
      '${schema}', table_name, '${runtimeRole}', '${shadowRole}');
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_policies WHERE schemaname='${schema}' AND tablename='ab_tenant_application_binding'
      AND policyname NOT IN ('binding_control_read','binding_shadow_insert','binding_shadow_update')) THEN
    RAISE EXCEPTION 'Unknown binding row policy requires explicit reconciliation';
  END IF;
END;
$policy$;
GRANT USAGE ON SCHEMA "${schema}" TO "${runtimeRole}", "${shadowRole}";
REVOKE ALL ON TABLE ${names.join(', ')} FROM PUBLIC, "${runtimeRole}", "${shadowRole}";
GRANT SELECT ON TABLE ${names.join(', ')} TO "${runtimeRole}", "${shadowRole}";
GRANT INSERT (tenant_id,application_id,current_release_id) ON TABLE ${names[1]} TO "${shadowRole}";
GRANT UPDATE (current_release_id,binding_version) ON TABLE ${names[1]} TO "${shadowRole}";
-- The existing history trigger rejects direct inserts; the writer cannot create or alter triggers.
GRANT INSERT ON TABLE ${names[2]} TO "${shadowRole}";
ALTER TABLE ${names[1]} ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS binding_control_read ON ${names[1]};
DROP POLICY IF EXISTS binding_shadow_insert ON ${names[1]};
DROP POLICY IF EXISTS binding_shadow_update ON ${names[1]};
CREATE POLICY binding_control_read ON ${names[1]} FOR SELECT TO "${runtimeRole}", "${shadowRole}" USING (true);
CREATE POLICY binding_shadow_insert ON ${names[1]} FOR INSERT TO "${shadowRole}" WITH CHECK (status='shadow');
CREATE POLICY binding_shadow_update ON ${names[1]} FOR UPDATE TO "${shadowRole}"
  USING (status='shadow') WITH CHECK (status='shadow');
COMMIT;
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 6) throw new Error('Usage: node shadow-binding-role-policy.mjs <schema> <runtime-role> <shadow-role> <owner-role>');
  const [schema, runtimeRole, shadowRole, ownerRole] = process.argv.slice(2);
  process.stdout.write(shadowBindingRolePolicy({ schema, runtimeRole, shadowRole, ownerRole }));
}
