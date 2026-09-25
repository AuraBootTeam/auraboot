import { pathToFileURL } from 'node:url';

/** Generate a reviewed, transactional policy. This module never connects to a database. */
export function registryRolePolicy({ schema, runtimeRole, registrarRole, ownerRole }) {
  for (const value of [schema, runtimeRole, registrarRole, ownerRole]) {
    if (typeof value !== 'string' || !/^[a-z_][a-z0-9_]{0,62}$/.test(value) || value.startsWith('pg_') || value === 'public') {
      // Public is a valid schema, but never an account identity.
      if (value === schema && value === 'public' && ![runtimeRole, registrarRole, ownerRole].includes(value)) continue;
      throw new Error('Explicit safe schema and role identifiers required');
    }
  }
  if (new Set([runtimeRole, registrarRole, ownerRole]).size !== 3) throw new Error('Three distinct database roles required');
  const tables = ['ab_application', 'ab_application_release', 'ab_application_release_component'];
  const qualified = tables.map(name => `"${schema}"."${name}"`);
  return `-- Generated Registry policy; apply with psql ON_ERROR_STOP=1 as a privileged operator.
-- Existing runtime/registrar memberships are rejected, never silently changed.
BEGIN;
DO $policy$
DECLARE role_name TEXT; table_name TEXT; identity_sequence TEXT; column_names TEXT;
BEGIN
  FOREACH role_name IN ARRAY ARRAY['${runtimeRole}', '${registrarRole}'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=role_name AND NOT rolsuper AND NOT rolcreaterole
        AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) THEN
      RAISE EXCEPTION 'Registry account absent or privileged: %', role_name;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=role_name) THEN
      RAISE EXCEPTION 'Registry account must have no inherited or assumable roles: %', role_name;
    END IF;
    IF has_schema_privilege(role_name, '${schema}', 'CREATE') THEN
      RAISE EXCEPTION 'Registry account must not create schema objects: %', role_name;
    END IF;
  END LOOP;
  FOREACH table_name IN ARRAY ARRAY['ab_application','ab_application_release','ab_application_release_component'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='${schema}' AND c.relname=table_name
        AND c.relkind='r' AND r.rolname='${ownerRole}') THEN
      RAISE EXCEPTION 'Registry table missing or owned by an unexpected role: %', table_name;
    END IF;
    SELECT string_agg(format('%I', a.attname), ', ') INTO column_names FROM pg_attribute a
      WHERE a.attrelid=format('%I.%I', '${schema}', table_name)::regclass AND a.attnum>0 AND NOT a.attisdropped;
    EXECUTE format('REVOKE ALL (%s) ON TABLE %I.%I FROM PUBLIC, %I, %I', column_names,
      '${schema}', table_name, '${runtimeRole}', '${registrarRole}');
  END LOOP;
  identity_sequence := pg_get_serial_sequence('"${schema}"."ab_application"', 'id');
  IF identity_sequence IS NULL THEN RAISE EXCEPTION 'Application identity sequence missing'; END IF;
  EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM PUBLIC, %I, %I', identity_sequence, '${runtimeRole}', '${registrarRole}');
  EXECUTE format('GRANT USAGE ON SEQUENCE %s TO %I', identity_sequence, '${registrarRole}');
END;
$policy$;
GRANT USAGE ON SCHEMA "${schema}" TO "${runtimeRole}", "${registrarRole}";
REVOKE ALL ON TABLE ${qualified.join(', ')} FROM PUBLIC, "${runtimeRole}", "${registrarRole}";
GRANT SELECT ON TABLE ${qualified.join(', ')} TO "${runtimeRole}", "${registrarRole}";
GRANT INSERT ON TABLE ${qualified.join(', ')} TO "${registrarRole}";
-- SELECT FOR UPDATE and the sequence-allocation trigger require this column privilege.
GRANT UPDATE (next_release_sequence) ON TABLE ${qualified[0]} TO "${registrarRole}";
COMMIT;
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 6) throw new Error('Usage: node registry-role-policy.mjs <schema> <runtime-role> <registrar-role> <owner-role>');
  const [schema, runtimeRole, registrarRole, ownerRole] = process.argv.slice(2);
  process.stdout.write(registryRolePolicy({ schema, runtimeRole, registrarRole, ownerRole }));
}
