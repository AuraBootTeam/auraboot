import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyGoldenProduct, liveEnvironment } from '../lib/golden-product-identity.mjs';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

// Hermetic identity controls. These do not replace native process/environment probes.
function fixture() {
  const repo = '/owned/oss', runtime = 'owned', token = 'fixture-token';
  const report = { verdict: 'ok', runtime, environment: { POSTGRES_DB: 'auraboot_211', REDIS_DATABASE: '12' },
    sources: [{ key: 'auraboot', status: 'ok', expected: { root: repo, commit: 'frozen-sha' } }],
    artifacts: [{ key: 'backend', path: '/owned/runtime/boot.jar', status: 'ok',
      expected: { sourceCommit: 'frozen-sha', hash: 'fixture-hash' } }], ports: {}, processes: [] };
  const environments = {};
  for (const [index, key] of ['backend', 'web', 'bff'].entries()) {
    report.ports[key] = { port: [6611, 5311, 6311][index], status: 'listening', pids: [100 + index] };
    report.processes.push({ key, status: 'ok', expected: { pid: 100 + index,
      cwd: repo + (key === 'backend' ? '/platform' : '/web-admin'), tokenHash: 'secret-hash', tokenFile: '/secret' } });
    environments[key] = { AURA_RUNTIME_NAME: runtime, AURA_RUNTIME_OWNERSHIP_TOKEN: token,
      SERVER_PORT: '6611', VITE_PORT: '5311', BFF_PORT: '6311',
      SPRING_DATASOURCE_URL: 'jdbc:postgresql://127.0.0.1:5432/auraboot_211?charSet=UTF8',
      SPRING_DATA_REDIS_DATABASE: '12', SPRING_BOOT_URL: 'http://127.0.0.1:6611',
      BFF_INTERNAL_URL: 'http://127.0.0.1:6611' };
  }
  return { report, repo, token, environments, commands: { backend: ['java', '-jar', '/owned/runtime/boot.jar'] },
    database: 'auraboot_211', health: { backend: { status: 'UP' }, bff: { status: 'ok',
      services: { springBoot: { status: 'healthy', backend: { status: 'UP' } } } } } };
}
test('records only verified runtime facts without ownership tokens or raw commands', () => {
  const value = verifyGoldenProduct(fixture());
  assert.equal(value.runtime, 'owned'); assert.equal(value.database, 'auraboot_211');
  const text = JSON.stringify(value);
  assert.doesNotMatch(text, /fixture-token|secret-hash|tokenFile|tokenHash|java|PASSWORD/);
});
const mutations = {
  'Workspace failure': f => { f.report.verdict = 'invalid'; },
  'foreign source': f => { f.report.sources[0].expected.root = '/foreign'; },
  'artifact drift': f => { f.report.artifacts[0].status = 'drift'; },
  'artifact source drift': f => { f.report.artifacts[0].expected.sourceCommit = 'other'; },
  'foreign listener': f => { f.report.ports.web.pids = [999]; },
  'ambiguous listener': f => { f.report.ports.bff.pids.push(999); },
  'foreign cwd': f => { f.report.processes[0].expected.cwd = '/foreign'; },
  'foreign runtime': f => { f.environments.web.AURA_RUNTIME_NAME = 'other'; },
  'foreign ownership token': f => { f.environments.bff.AURA_RUNTIME_OWNERSHIP_TOKEN = 'other'; },
  'missing ownership token': f => { f.token = ''; },
  'mutable backend jar': f => { f.commands.backend[2] = '/build/boot.jar'; },
  'jar prefix impersonation': f => { f.commands.backend[2] += '.foreign'; },
  'backend port drift': f => { f.environments.backend.SERVER_PORT = '6500'; },
  'backend database drift': f => { f.environments.backend.SPRING_DATASOURCE_URL = 'jdbc:postgresql://foreign/db'; },
  'database connection drift': f => { f.database = 'other'; },
  'Redis database drift': f => { f.environments.backend.SPRING_DATA_REDIS_DATABASE = '0'; },
  'frontend port drift': f => { f.environments.web.BFF_PORT = '6200'; },
  'BFF upstream drift': f => { f.environments.bff.SPRING_BOOT_URL = 'http://production'; },
  'consistent foreign BFF upstreams': f => {
    f.environments.bff.SPRING_BOOT_URL = 'http://foreign';
    f.environments.bff.BFF_INTERNAL_URL = 'http://foreign';
  },
  'internal upstream drift': f => { f.environments.web.BFF_INTERNAL_URL = 'http://foreign'; },
  'backend health failure': f => { f.health.backend.status = 'DOWN'; },
  'BFF upstream unhealthy': f => { f.health.bff.services.springBoot.backend.status = 'DOWN'; },
};
for (const [label, mutate] of Object.entries(mutations)) test(`rejects ${label} before publishing evidence`, () => {
  const f = fixture(); mutate(f); assert.throws(() => verifyGoldenProduct(f));
});

test('preserves the registered Core source key for an existing retained stack', () => {
  const f = fixture(); f.report.sources[0].key = 'core';
  assert.equal(verifyGoldenProduct(f).sourceRoots[0].key, 'core');
  f.report.sources[0].expected.root = '/foreign';
  assert.throws(() => verifyGoldenProduct(f), /source identity mismatch/);
});


test('production proof requires both live frontend listeners to run in production mode', () => {
  const f = fixture();
  for (const key of ['web', 'bff']) Object.assign(f.environments[key], { AURA_WEB_MODE: 'production', NODE_ENV: 'production' });
  assert.equal(verifyGoldenProduct(f).frontendMode, 'production');
  f.environments.bff.NODE_ENV = 'development';
  assert.throws(() => verifyGoldenProduct(f), /mode drift/);
});

function isolatedRedisFixture() {
  const f = fixture();
  Object.assign(f.environments.backend, {
    SPRING_DATA_REDIS_HOST: '127.0.0.1', SPRING_DATA_REDIS_PORT: '16317',
  });
  f.report.processes.push({ key: 'redis-owned', status: 'ok',
    expected: { pid: 104, cwd: '/state/redis/owned' } });
  f.redisEndpoint = { isolated: true, host: '127.0.0.1', port: 16317,
    database: '12', pid: 104, dataDirectory: '/state/redis/owned' };
  return f;
}

test('records the verified private Redis endpoint separately from its database number', () => {
  const f = isolatedRedisFixture();
  assert.deepEqual(verifyGoldenProduct(f).redisEndpoint, f.redisEndpoint);
});

for (const [label, mutate] of Object.entries({
  'Redis endpoint drift with the same database': f => { f.environments.backend.SPRING_DATA_REDIS_PORT = '16311'; },
  'Redis host drift': f => { f.environments.backend.SPRING_DATA_REDIS_HOST = 'foreign'; },
  'a shared Redis port labelled isolated': f => {
    f.redisEndpoint.port = 6379; f.environments.backend.SPRING_DATA_REDIS_PORT = '6379';
  },
  'an unverified Redis process': f => { f.report.processes.at(-1).status = 'drift'; },
  'Redis endpoint database drift': f => { f.redisEndpoint.database = '0'; },
  'a missing private endpoint proof': f => { delete f.redisEndpoint; },
})) test(`rejects ${label} before publishing evidence`, () => {
  const f = isolatedRedisFixture(); mutate(f);
  assert.throws(() => verifyGoldenProduct(f));
});

test('live environment captures local storage configuration without unrelated storage or database secrets', async () => {
  const child=spawn(process.execPath,['-e',"process.stdout.write('ready');setInterval(()=>{},1000)"],{
    env:{PATH:process.env.PATH,AURA_STORAGE_TYPE:'local',AURA_STORAGE_LOCAL_BASE_PATH:'/owned/run/files',AURA_STORAGE_ACCESS_KEY:'synthetic-hidden-value',POSTGRES_PASSWORD:'synthetic-hidden-value'},stdio:['ignore','pipe','ignore']});
  try {
    await once(child.stdout,'data');
    const observed=liveEnvironment(child.pid);
    assert.equal(observed.AURA_STORAGE_TYPE,'local');
    assert.equal(observed.AURA_STORAGE_LOCAL_BASE_PATH,'/owned/run/files');
    assert.equal(observed.AURA_STORAGE_ACCESS_KEY,undefined);
    assert.equal(observed.POSTGRES_PASSWORD,undefined);
    assert.doesNotMatch(JSON.stringify(observed),/synthetic-hidden-value/);
  } finally { const exited=once(child,'exit');child.kill('SIGTERM');await exited; }
});
