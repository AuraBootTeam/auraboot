import { test } from 'node:test';
import assert from 'node:assert/strict';
import { verifyGoldenProduct } from '../lib/golden-product-identity.mjs';

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
