import assert from 'node:assert/strict';
import { realpathSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export function sourceMappings(core, extras, specs) {
  const rows = [{ key: 'core', root: realpathSync(core) }];
  for (const spec of specs) {
    const separator = spec.indexOf('=');
    const key = spec.slice(0, separator);
    assert(separator > 0 && /^[a-z][a-z0-9-]*$/.test(key), 'source requires key=path');
    assert(!rows.some(row => row.key === key), 'duplicate source key');
    rows.push({ key, root: realpathSync(spec.slice(separator + 1)) });
  }
  for (const extra of extras) {
    const root = realpathSync(extra);
    assert.equal(rows.filter(row => root === row.root || root.startsWith(`${row.root}/`)).length,
      1, 'every extra plugin root requires exactly one explicit source owner');
  }
  return rows;
}

export function resumeArgs(args) {
  const result = [];
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--fresh-db' || arg === '--require-new-db') continue;
    if (arg === '--product-migration-root') { index++; continue; }
    if (arg.startsWith('--product-migration-root=')) continue;
    result.push(arg);
  }
  return result;
}

export function inputMappings(core, args) {
  const extras = [], specs = [];
  for (let index = 0; index < args.length; index++) {
    const arg = args[index];
    if (arg === '--source' || arg === '--extra-plugin-root') {
      assert(args[index + 1] && !args[index + 1].startsWith('--'), `${arg} requires a value`);
      (arg === '--source' ? specs : extras).push(args[++index]);
    } else if (arg.startsWith('--extra-plugin-root=')) extras.push(arg.slice('--extra-plugin-root='.length));
  }
  return sourceMappings(core, extras, specs);
}

export function validateResumePlan(plan, runtime, core) {
  assert.equal(plan.schemaVersion, 1);
  assert.equal(plan.runtime, runtime, 'wrong resume runtime');
  assert.equal(plan.core, realpathSync(core), 'wrong resume source root');
  assert.deepEqual(plan.args, resumeArgs(plan.args), 'resume must never replay database initialization');
  assert.equal(plan.environment.MANAGEMENT_HEALTH_DB_ENABLED, 'false');
  assert.deepEqual(Object.keys(plan.environment), ['MANAGEMENT_HEALTH_DB_ENABLED']);
  return plan;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [action, runtime, core, state, ...args] = process.argv.slice(2);
    const file = join(state, 'lifecycle-plan.json');
    if (action === 'validate') {
      inputMappings(core, args);
    } else if (action === 'record') {
      const plan = { schemaVersion: 1, runtime, core: realpathSync(core), args: resumeArgs(args),
        environment: { MANAGEMENT_HEALTH_DB_ENABLED: 'false' } };
      validateResumePlan(plan, runtime, core);
      writeFileSync(`${file}.tmp`, `${JSON.stringify(plan, null, 2)}\n`, { mode: 0o600 });
      renameSync(`${file}.tmp`, file);
    } else if (action === 'resume') {
      const plan = validateResumePlan(JSON.parse(readFileSync(file, 'utf8')), runtime, core);
      assert.equal(process.env.AURA_RUNTIME_NAME, runtime, 'resume requires the public runtime controller');
      const result = spawnSync(join(core, 'scripts/oss-golden-stack.sh'), ['up', runtime, ...plan.args],
        { stdio: 'inherit', env: { ...process.env, ...plan.environment, AURA_OSS_RESUME_CONTEXT: '1' } });
      assert.equal(result.status, 0, 'resume startup failed');
    } else if (action === 'check-resuming') {
      assert(process.env.AURA_WORKSPACE_ROOT, 'explicit Workspace controller required');
      const result = spawnSync(join(process.env.AURA_WORKSPACE_ROOT, 'aura'),
        ['runtime', 'explain', runtime, '--json'], { encoding: 'utf8' });
      assert([0, 1].includes(result.status));
      const info = JSON.parse(result.stdout);
      assert.equal(info.metadata.lifecycle, 'resuming', 'resume context is not controller-owned');
      assert.equal(info.sources.find(row => row.key === 'core')?.actual.root, realpathSync(core));
      for (const source of info.sources) assert.equal(source.status, 'ok', 'resume source identity drift');
    } else throw new Error('unknown lifecycle action');
  } catch (error) {
    console.error(`OSS_STACK_LIFECYCLE_INVALID: ${error.message}`);
    process.exitCode = 1;
  }
}
