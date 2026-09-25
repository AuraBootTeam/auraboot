#!/usr/bin/env node
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { verifyPayload } from './migration-payload.mjs';

const ACTIONS = new Set(['info', 'validate', 'migrate']);
function need(condition, message) { if (!condition) throw new Error(message); }

export function migrationInvocation({ action, root, digest, host, port, database, user, password, sslMode, home }) {
  need(ACTIONS.has(action), 'migrator action must be info, validate or migrate');
  const receipt = verifyPayload(root, digest);
  need(/^[a-zA-Z0-9][a-zA-Z0-9.-]*$/.test(host || ''), 'explicit database hostname required');
  need(/^[0-9]+$/.test(String(port)) && Number(port) >= 1 && Number(port) <= 65535, 'invalid database port');
  need(/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(database || ''), 'invalid database name');
  need(typeof user === 'string' && user.length > 0 && user.length <= 128 && !/[\r\n\0]/.test(user), 'database user required');
  need(typeof password === 'string' && password.length > 0 && !password.includes('\0'), 'database password required');
  need(['disable', 'require', 'verify-ca', 'verify-full'].includes(sslMode), 'explicit database SSL mode required');
  need(typeof home === 'string' && home.startsWith('/'), 'isolated runtime home required');
  const locations = receipt.dependencyOrder.map((id) => `filesystem:${join(resolve(root), 'packages', id, 'sql')}`).join(',');
  return {
    executable: '/flyway/flyway',
    args: ['-configFiles=/dev/null',
      `-url=jdbc:postgresql://${host}:${Number(port)}/${database}?sslmode=${sslMode}`,
      `-locations=${locations}`, '-table=ab_flyway_schema_history', '-schemas=public', '-defaultSchema=public',
      '-createSchemas=false', '-cleanDisabled=true', '-baselineOnMigrate=false', '-outOfOrder=false',
      '-validateOnMigrate=true', '-validateMigrationNaming=true', '-failOnMissingLocations=true',
      '-placeholderReplacement=false', '-ignoreMigrationPatterns=', action],
    // No ambient JAVA_ARGS, CLASSPATH, FLYWAY_* or loader variables enter the child.
    env: { PATH: '/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin', JAVA_HOME: '/opt/java/openjdk',
      HOME: home, LANG: 'C.UTF-8', FLYWAY_USER: user, FLYWAY_PASSWORD: password },
    cwd: home,
  };
}

export function main(args, environment) {
  need(args.length === 1 && ACTIONS.has(args[0]), 'usage: migrator info|validate|migrate');
  const baked = readFileSync('/opt/aura/payload.sha256', 'utf8').trim();
  need(environment.AURA_MIGRATION_PAYLOAD_SHA256 === baked, 'deployment payload digest must match image payload');
  const passwordFile = environment.AURA_DB_PASSWORD_FILE;
  need(typeof passwordFile === 'string' && passwordFile.startsWith('/'), 'absolute database password file required');
  const password = readFileSync(passwordFile, 'utf8').replace(/\r?\n$/, '');
  const home = mkdtempSync(join(tmpdir(), 'aura-migrator-'));
  const invocation = migrationInvocation({ action: args[0], root: '/opt/aura/migrations', digest: baked,
    host: environment.AURA_DB_HOST, port: environment.AURA_DB_PORT, database: environment.AURA_DB_NAME,
    user: environment.AURA_DB_USER, password, sslMode: environment.AURA_DB_SSL_MODE, home });
  const result = spawnSync(invocation.executable, invocation.args, {
    cwd: invocation.cwd, env: invocation.env, stdio: ['ignore', 'inherit', 'inherit'],
  });
  if (result.error) throw new Error('Flyway runtime could not be launched');
  // A signal or missing exit status must never appear as successful migration.
  return Number.isInteger(result.status) ? result.status : 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { process.exitCode = main(process.argv.slice(2), process.env); }
  catch (error) { console.error(`migrator: ${error.message}`); process.exitCode = 2; }
}
