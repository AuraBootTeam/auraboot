#!/usr/bin/env node
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
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
    args: [`-configFiles=${join(home, 'flyway.conf')}`,
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

export function beginMigrationEvidence(directory, identity) {
  need(typeof directory === 'string' && directory.startsWith('/'), 'absolute migration evidence directory required');
  need(identity && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(identity.deploymentId ?? '')
    && Number.isSafeInteger(identity.generation) && identity.generation > 0, 'explicit deployment identity and generation required');
  need(ACTIONS.has(identity.action) && /^[0-9a-f]{64}$/.test(identity.payloadSha256 ?? ''), 'exact migration action and payload required');
  need(typeof identity.database === 'string' && /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(identity.database)
    && typeof identity.host === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9.-]*$/.test(identity.host)
    && Number.isInteger(identity.port) && identity.port > 0 && identity.port <= 65535, 'exact migration database target required');
  const started = { schemaVersion: 1, kind: 'migration-execution', deploymentId: identity.deploymentId,
    generation: identity.generation, action: identity.action, payloadSha256: identity.payloadSha256,
    database: { host: identity.host, port: identity.port, name: identity.database },
    state: 'started', startedAt: new Date().toISOString() };
  const bytes = Buffer.from(`${JSON.stringify(started, null, 2)}\n`);
  // Reserve a fresh evidence namespace before starting the migration engine.
  mkdirSync(directory, { mode: 0o755 });
  writeFileSync(join(directory, 'started.json'), bytes, { flag: 'wx', mode: 0o444 });
  return { directory, startedDigest: `sha256:${createHash('sha256').update(bytes).digest('hex')}` };
}

export function finishMigrationEvidence(evidence, result) {
  const exitCode = !result.error && !result.signal && Number.isInteger(result.status) && result.status >= 0 ? result.status : 1;
  const receipt = { schemaVersion: 1, kind: 'migration-execution-result', startedDigest: evidence.startedDigest,
    state: !result.error && exitCode === 0 ? 'succeeded' : 'failed', exitCode: result.error ? 1 : exitCode,
    failureKind: result.error ? 'engine-launch-failed' : result.signal ? 'engine-signaled' : exitCode ? 'engine-exit-nonzero' : null,
    completedAt: new Date().toISOString() };
  writeFileSync(join(evidence.directory, 'result.json'), `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx', mode: 0o444 });
  return receipt.exitCode;
}

export function main(args, environment) {
  need(args.length === 1 && ACTIONS.has(args[0]), 'usage: migrator info|validate|migrate');
  const baked = readFileSync('/opt/aura/payload.sha256', 'utf8').trim();
  need(environment.AURA_MIGRATION_PAYLOAD_SHA256 === baked, 'deployment payload digest must match image payload');
  const passwordFile = environment.AURA_DB_PASSWORD_FILE;
  need(typeof passwordFile === 'string' && passwordFile.startsWith('/'), 'absolute database password file required');
  const password = readFileSync(passwordFile, 'utf8').replace(/\r?\n$/, '');
  const home = mkdtempSync(join(tmpdir(), 'aura-migrator-'));
  writeFileSync(join(home, 'flyway.conf'), '', { flag: 'wx', mode: 0o600 });
  const invocation = migrationInvocation({ action: args[0], root: '/opt/aura/migrations', digest: baked,
    host: environment.AURA_DB_HOST, port: environment.AURA_DB_PORT, database: environment.AURA_DB_NAME,
    user: environment.AURA_DB_USER, password, sslMode: environment.AURA_DB_SSL_MODE, home });
  need(/^[1-9][0-9]*$/.test(environment.AURA_DEPLOYMENT_GENERATION ?? ''), 'canonical deployment generation required');
  const evidence = beginMigrationEvidence(environment.AURA_MIGRATION_EVIDENCE_DIR, {
    deploymentId: environment.AURA_DEPLOYMENT_ID, generation: Number(environment.AURA_DEPLOYMENT_GENERATION),
    action: args[0], payloadSha256: baked, host: environment.AURA_DB_HOST,
    port: Number(environment.AURA_DB_PORT), database: environment.AURA_DB_NAME,
  });
  const result = spawnSync(invocation.executable, invocation.args, {
    cwd: invocation.cwd, env: invocation.env, stdio: ['ignore', 'inherit', 'inherit'],
  });
  // Launch failures and signals are failures with immutable evidence, never success.
  return finishMigrationEvidence(evidence, result);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { process.exitCode = main(process.argv.slice(2), process.env); }
  catch (error) { console.error(`migrator: ${error.message}`); process.exitCode = 2; }
}
