#!/usr/bin/env node
/** Linux-only synthetic migrator lifecycle gate. Retains all containers and evidence. */
import { createHash, randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout } from 'node:timers/promises';
import { inventoryPackage } from './schema-ownership-audit.mjs';
import { stagePayload } from './migration-payload.mjs';
import { verifyMigrationExecution } from './migration-execution-verifier.mjs';

const PG_IMAGE = 'postgres@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636';
const CASES = ['base-migrate', 'base-validate', 'base-info', 'repeat', 'repair-denied', 'digest-denied',
  'legacy-denied', 'upgrade-migrate', 'upgrade-validate', 'fresh-migrate', 'fresh-validate'];
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
function need(condition, message) { if (!condition) throw new Error(message); }
function capture(command, args, options = {}) {
  return spawnSync(command, args, { encoding: 'utf8', timeout: 900000, maxBuffer: 32 * 1024 * 1024, ...options });
}
function checked(command, args, options) {
  const result = capture(command, args, options);
  need(result.status === 0, `${command} failed (exit=${result.status}, signal=${result.signal || 'none'})`);
  return result.stdout.trim();
}

export function parseOptions(args) {
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i]; const value = args[i + 1];
    need(['--repo-root', '--expected-sha', '--artifacts', '--job'].includes(key) && value && !options[key], 'invalid gate arguments');
    options[key] = value;
  }
  need(Object.keys(options).length === 4, 'required: --repo-root --expected-sha --artifacts --job');
  need(/^[a-f0-9]{40}$/.test(options['--expected-sha']), 'exact 40-character Core SHA required');
  need(/^[a-z0-9][a-z0-9-]{0,45}$/.test(options['--job']), 'invalid gate job ID');
  return options;
}

export async function runGate(options) {
  need(process.platform === 'linux' && process.arch === 'x64', 'migrator image gate requires Linux x64');
  const repo = realpathSync(options['--repo-root']); const sha = options['--expected-sha'];
  need(realpathSync(fileURLToPath(import.meta.url)) === join(repo, 'scripts/application/run-migrator-image-gate.mjs'), 'runner must belong to selected Core checkout');
  const git = (...args) => checked('git', ['-C', repo, ...args]);
  const verifySource = () => { need(git('rev-parse', 'HEAD') === sha, 'Core HEAD differs from expected SHA'); need(git('status', '--porcelain', '--untracked-files=all') === '', 'Core checkout must remain clean'); };
  verifySource();
  need(checked('docker', ['version', '--format', '{{.Server.Os}}/{{.Server.Arch}}']) === 'linux/amd64', 'Docker daemon must be Linux amd64');
  const artifacts = resolve(options['--artifacts']);
  need(!/[,\r\n]/.test(artifacts), 'artifact path cannot contain mount delimiters');
  mkdirSync(artifacts, { recursive: true });
  const root = join(artifacts, 'migrator-gate'); mkdirSync(root, { mode: 0o700 });
  const prefix = `rf-mig-${options['--job']}`; const network = `${prefix}-net`; const db = `${prefix}-db`;
  const summary = { schemaVersion: 1, claim: 'synthetic migrator lifecycle only', status: 'RUNNING',
    source: { commit: sha, runnerSha256: hash(readFileSync(fileURLToPath(import.meta.url))) },
    required: CASES, cases: [], images: {}, retained: { root, network, databaseContainer: db, volume: db },
    didNotRun: ['product migration packages', 'representative customer dump', 'Deployment integration', 'registry push'] };
  const checkpoint = () => writeFileSync(join(root, 'summary.json'), `${JSON.stringify(summary, null, 2)}\n`);
  const command = (name, executable, args, expected = 0) => {
    const result = capture(executable, args);
    writeFileSync(join(root, `${name}.log`), (result.stdout || '') + (result.stderr || ''));
    writeFileSync(join(root, `${name}.exit`), `${result.status ?? 'signal-or-launch-error'}\n`);
    need(result.status === expected, `${name}: expected exit ${expected}, actual ${result.status}; see log`);
    return (result.stdout || '') + (result.stderr || '');
  };
  checkpoint();
  try {
    const archive = join(root, 'core-source.tar');
    checked('git', ['-C', repo, 'archive', '--format=tar', `--output=${archive}`, sha, 'scripts/application']);
    summary.source.archiveSha256 = hash(readFileSync(archive));
    for (const version of [1, 2]) {
      const context = join(root, `context-v${version}`); mkdirSync(context);
      checked('tar', ['-xf', archive, '-C', context]);
      const fixture = join(root, `fixture-v${version}`); mkdirSync(fixture);
      writeFileSync(join(fixture, 'V1__probe.sql'), 'CREATE TABLE migrator_probe(id bigint PRIMARY KEY);\n');
      if (version === 2) writeFileSync(join(fixture, 'V2__label.sql'), "ALTER TABLE migrator_probe ADD COLUMN label text NOT NULL DEFAULT 'upgraded';\n");
      const contract = { ...inventoryPackage(fixture, 'core'), dependencies: [], objects: {
        migrator_probe: { owner: 'core', lifecycle: 'static-schema', evidence: 'Synthetic gate fixture; not product migration approval' },
      } };
      for (const [i, migration] of contract.migrations.entries()) Object.assign(migration.statements[0], {
        review: 'approved', rationale: 'Synthetic mechanism verification', objects: [{ name: 'migrator_probe', operation: i === 0 ? 'create' : 'alter' }],
      });
      const review = join(fixture, 'review.json'); writeFileSync(review, JSON.stringify(contract));
      const composition = join(root, `composition-v${version}.json`);
      writeFileSync(composition, JSON.stringify({ schemaVersion: 1, packages: [{ root: fixture, contract: review }] }));
      mkdirSync(join(context, 'migrator-input'));
      const payload = stagePayload(composition, join(context, 'migrator-input/payload'));
      writeFileSync(join(context, 'migrator-input/payload.sha256'), `${payload.manifestSha256}\n`);
      const tag = `aura-release-foundation/migrator:${options['--job']}-v${version}`;
      command(`build-v${version}`, 'docker', ['build', '--network=none', '-f', join(context, 'scripts/application/Dockerfile.migrator'),
        '--label', `org.opencontainers.image.revision=${sha}`, '-t', tag, context]);
      const image = checked('docker', ['image', 'inspect', tag, '--format', '{{.Id}}']);
      need(/^sha256:[a-f0-9]{64}$/.test(image), 'invalid built image identity');
      summary.images[`v${version}`] = { image, payloadSha256: payload.manifestSha256 }; checkpoint();
    }
    verifySource();
    // Credentials stay outside the archiveable evidence tree; retain the private mount for review.
    const privateState = mkdtempSync(join(tmpdir(), `${prefix}-secrets-`));
    summary.retained.privateState = privateState;
    const password = randomBytes(24).toString('hex');
    writeFileSync(join(privateState, 'db-password'), `${password}\n`, { mode: 0o644 });
    writeFileSync(join(privateState, 'db.env'), `POSTGRES_DB=rf_migrator\nPOSTGRES_USER=rf_migrator\nPOSTGRES_PASSWORD=${password}\n`, { mode: 0o600 });
    command('network', 'docker', ['network', 'create', '--internal', network]);
    command('database', 'docker', ['run', '-d', '--name', db, '--network', network, '--memory', '512m', '--cpus', '1',
      '--env-file', join(privateState, 'db.env'), '--mount', `type=volume,source=${db},target=/var/lib/postgresql`, PG_IMAGE]);
    let ready = false;
    for (let attempt = 0; attempt < 60; attempt++) {
      const logs = capture('docker', ['logs', db]);
      if (`${logs.stdout}${logs.stderr}`.includes('PostgreSQL init process complete; ready for start up.') &&
          capture('docker', ['exec', db, 'pg_isready', '-U', 'rf_migrator', '-d', 'rf_migrator']).status === 0) { ready = true; break; }
      await setTimeout(1000);
    }
    need(ready, 'PostgreSQL final server did not become ready');
    const sql = (database, query) => checked('docker', ['exec', db, 'psql', '-U', 'rf_migrator', '-d', database, '-X', '-At', '-v', 'ON_ERROR_STOP=1', '-c', query]);
    const scenario = (name, version, action, database = 'rf_migrator', expected = 0, text = '', assertion) => {
      const image = summary.images[`v${version}`];
      const env = join(root, `${name}.env`);
      const executionRoot = join(root, `${name}-execution`);
      mkdirSync(executionRoot); chmodSync(executionRoot, 0o777); // Owned mount for the unprivileged container; contains no credentials.
      writeFileSync(env, `AURA_DEPLOYMENT_ID=01ARZ3NDEKTSV4RRFFQ69G5FAV\nAURA_DEPLOYMENT_GENERATION=1\nAURA_MIGRATION_EVIDENCE_DIR=/evidence/run\nAURA_DB_HOST=${db}\nAURA_DB_PORT=5432\nAURA_DB_NAME=${database}\nAURA_DB_USER=rf_migrator\nAURA_DB_SSL_MODE=disable\nAURA_DB_PASSWORD_FILE=/run/db-password\nAURA_MIGRATION_PAYLOAD_SHA256=${name === 'digest-denied' ? 'invalid' : image.payloadSha256}\n`, { mode: 0o600 });
      const output = command(name, 'docker', ['run', '--name', `${prefix}-${name}`, '--network', network, '--read-only',
        '--tmpfs', '/tmp:rw,nosuid,size=128m', '--memory', '512m', '--cpus', '1', '--env-file', env,
        '--mount', `type=bind,src=${executionRoot},dst=/evidence`,
        '--mount', `type=bind,src=${join(privateState, 'db-password')},dst=/run/db-password,readonly`, image.image, action], expected);
      need(!text || output.includes(text), `${name}: expected failure reason missing`);
      if (expected === 0 || name === 'legacy-denied') {
        const startedBytes = readFileSync(join(executionRoot, 'run/started.json'));
        const resultBytes = readFileSync(join(executionRoot, 'run/result.json'));
        const outcome = verifyMigrationExecution(startedBytes, resultBytes, {
          deploymentId: '01ARZ3NDEKTSV4RRFFQ69G5FAV', generation: 1,
          action, payloadSha256: image.payloadSha256, database: { host: db, port: 5432, name: database },
          startedDigest: `sha256:${hash(startedBytes)}`, resultDigest: `sha256:${hash(resultBytes)}`,
        });
        need(outcome.exitCode === expected && outcome.executionSucceeded === (expected === 0), `${name}: execution outcome mismatch`);
      }

      if (assertion) {
        const actual = sql(database, assertion.query); writeFileSync(join(root, `${name}.db.txt`), `${actual}\n`);
        need(actual === assertion.expected, `${name}: database assertion failed`);
      }
      summary.cases.push({ name, passed: true, exitCode: expected }); checkpoint();
    };
    for (const action of ['migrate', 'validate', 'info']) scenario(`base-${action}`, 1, action);
    sql('rf_migrator', 'INSERT INTO migrator_probe VALUES(73);');
    scenario('repeat', 1, 'migrate', 'rf_migrator', 0, '', { query: 'SELECT count(*) FROM ab_flyway_schema_history WHERE success; SELECT id FROM migrator_probe;', expected: '1\n73' });
    scenario('repair-denied', 1, 'repair', 'rf_migrator', 2, 'usage: migrator info|validate|migrate');
    scenario('digest-denied', 1, 'migrate', 'rf_migrator', 2, 'deployment payload digest must match image payload');
    sql('postgres', 'CREATE DATABASE rf_migrator_legacy;');
    sql('rf_migrator_legacy', 'CREATE TABLE existing_customer(id bigint); INSERT INTO existing_customer VALUES(91);');
    scenario('legacy-denied', 1, 'migrate', 'rf_migrator_legacy', 1, 'but no schema history table', {
      query: "SELECT count(*) FROM existing_customer WHERE id=91; SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('ab_flyway_schema_history','migrator_probe');", expected: '1\n0' });
    sql('postgres', 'CREATE DATABASE rf_migrator_fresh;');
    const history = "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM ab_flyway_schema_history WHERE success; SELECT count(*) FROM information_schema.columns WHERE table_name='migrator_probe' AND column_name='label' AND is_nullable='NO';";
    for (const [target, database] of [['upgrade', 'rf_migrator'], ['fresh', 'rf_migrator_fresh']]) {
      scenario(`${target}-migrate`, 2, 'migrate', database, 0, '', { query: history, expected: '1,2\n1' });
      scenario(`${target}-validate`, 2, 'validate', database, 0, '', target === 'upgrade'
        ? { query: "SELECT id || ':' || label FROM migrator_probe;", expected: '73:upgraded' }
        : { query: 'SELECT count(*) FROM migrator_probe;', expected: '0' });
    }
    verifySource();
    need(JSON.stringify(summary.cases.map((c) => c.name)) === JSON.stringify(CASES), 'gate case denominator mismatch');
    summary.status = 'PASS'; checkpoint();
    return summary;
  } catch (error) {
    summary.status = 'FAIL'; summary.error = error.message; checkpoint(); throw error;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { const result = await runGate(parseOptions(process.argv.slice(2))); console.log(JSON.stringify({ status: result.status, cases: result.cases.length, retained: result.retained })); }
  catch (error) { console.error(`migrator-image-gate: ${error.message}`); process.exitCode = 1; }
}
