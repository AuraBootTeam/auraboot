import { pathToFileURL } from 'node:url';

/** Operator policy for an explicit, isolated model-publication phase. */
export function dynamicPublicationRolePolicy({ schema, runtimeRole, publisherRole, registrarRole, ownerRole }) {
  if (typeof schema !== 'string' || !/^[a-z_][a-z0-9_]{0,62}$/.test(schema)) {
    throw new Error('Explicit safe schema required');
  }
  const roles = [runtimeRole, publisherRole, registrarRole, ownerRole];
  if (roles.some(role => typeof role !== 'string' || !/^[a-z_][a-z0-9_]{0,62}$/.test(role)
      || role.startsWith('pg_') || role === 'public') || new Set(roles).size !== 4) {
    throw new Error('Four distinct safe database roles required');
  }
  const registry = ['ab_application', 'ab_application_release', 'ab_application_release_component', 'ab_platform_release_registry'];
  return `-- Apply only to the isolated publication environment, as the migration owner.
BEGIN;
DO $publication$
DECLARE account TEXT; object_name TEXT; sequence_name TEXT;
BEGIN
  FOREACH account IN ARRAY ARRAY['${runtimeRole}', '${publisherRole}', '${registrarRole}'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=account AND NOT rolsuper AND NOT rolcreaterole
        AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls) THEN
      RAISE EXCEPTION 'Publication account absent or privileged: %', account;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname=account) THEN
      RAISE EXCEPTION 'Publication accounts must not inherit or assume roles: %', account;
    END IF;
  END LOOP;
  IF has_schema_privilege('${runtimeRole}', '${schema}', 'CREATE')
      OR has_schema_privilege('${registrarRole}', '${schema}', 'CREATE') THEN
    RAISE EXCEPTION 'Runtime and registrar must not create schema objects';
  END IF;
  FOREACH object_name IN ARRAY ARRAY['${registry.join("','")}'] LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='${schema}' AND c.relname=object_name
      AND c.relkind='r' AND r.rolname='${ownerRole}') THEN
      RAISE EXCEPTION 'Registry ownership must remain with the migration owner: %', object_name;
    END IF;
  END LOOP;
END;
$publication$;
GRANT USAGE, CREATE ON SCHEMA "${schema}" TO "${publisherRole}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "${schema}" TO "${publisherRole}";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA "${schema}" TO "${publisherRole}";
REVOKE ALL ON TABLE ${registry.map(table => `"${schema}"."${table}"`).join(', ')} FROM "${publisherRole}";
GRANT SELECT ON TABLE ${registry.map(table => `"${schema}"."${table}"`).join(', ')} TO "${publisherRole}";
DO $sequences$
DECLARE sequence_name TEXT;
BEGIN
  FOR sequence_name IN SELECT format('%I.%I', n.nspname, c.relname) FROM pg_class c
    JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_depend d ON d.objid=c.oid
    JOIN pg_class t ON t.oid=d.refobjid JOIN pg_namespace tn ON tn.oid=t.relnamespace
    WHERE c.relkind='S' AND tn.nspname='${schema}'
      AND t.relname IN ('${registry.join("','")}') AND d.deptype IN ('a','i')
  LOOP
    EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM %I', sequence_name, '${publisherRole}');
  END LOOP;
END;
$sequences$;
-- New dynamic tables belong to the publisher; the runtime receives business DML only.
ALTER DEFAULT PRIVILEGES FOR ROLE "${publisherRole}" IN SCHEMA "${schema}"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${runtimeRole}";
ALTER DEFAULT PRIVILEGES FOR ROLE "${publisherRole}" IN SCHEMA "${schema}"
  GRANT USAGE, SELECT ON SEQUENCES TO "${runtimeRole}";
COMMIT;
`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 7) throw new Error('Usage: node dynamic-publication-role-policy.mjs <schema> <runtime-role> <publisher-role> <registrar-role> <owner-role>');
  const [schema, runtimeRole, publisherRole, registrarRole, ownerRole] = process.argv.slice(2);
  process.stdout.write(dynamicPublicationRolePolicy({ schema, runtimeRole, publisherRole, registrarRole, ownerRole }));
}
