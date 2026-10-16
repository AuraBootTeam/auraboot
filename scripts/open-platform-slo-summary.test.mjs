import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';

const source = readFileSync(new URL('../tests/load/k6/open-platform-slo.js', import.meta.url), 'utf8');
const handler = source.slice(source.indexOf('export function handleSummary')).replace('export function', 'function');
for (const verdict of ['passed', 'failed']) {
  test(`k6 ${verdict} summary keeps metrics and excludes setup credentials`, () => {
    const context = { __ENV: { SUMMARY_PATH: '/artifacts/slo-summary.json' } };
    runInNewContext(handler, context);
    const metrics = { open_api_duration: { values: { 'p(95)': 505 }, thresholds: { 'p(95)<250': { ok: verdict === 'passed' } } } };
    const root_group = { checks: [{ name: 'whoami succeeds', passes: 10, fails: 0 }] };
    const output = context.handleSummary({ metrics, root_group, setup_data: { token: 'sensitive-fixture-token' }, debug: { client_secret: 'sensitive-fixture-secret' } });
    const exported = JSON.parse(output['/artifacts/slo-summary.json']);
    assert.deepEqual(exported.metrics, metrics);
    assert.deepEqual(exported.root_group, root_group);
    assert.equal(exported.scope, 'baseline-only');
    assert.equal(exported.profile, 'smoke');
    assert.deepEqual(Object.keys(exported).sort(), ['metrics', 'profile', 'root_group', 'scope']);
    assert.doesNotMatch(JSON.stringify(output), /sensitive-fixture|setup_data|client_secret/);
    assert.equal(JSON.parse(output.stdout).metrics.open_api_duration.values['p(95)'], 505);
  });
}

test('standalone and release runners use the sanitized handler instead of the built-in credential-bearing export', () => {
  for (const file of ['run-open-platform-slo-gate.sh', 'run-open-platform-release-image-gate.sh']) {
    const runner = readFileSync(new URL(file, import.meta.url), 'utf8');
    assert.doesNotMatch(runner, /--summary-export/);
    assert.match(runner, /SUMMARY_PATH/);
  }
});


function runtime(invalidQuery = false, invalidEndpointQuery = false) {
  const observations = [];
  const fixture = { schemaVersion: 1, profile: 'open-platform-dynamic-slo-v1', runId: 'slo-' + 'a'.repeat(20),
    resourceCode: 'release.dynamic.parts', queryCode: 'release.slo.by-zone',
    allowedPid: 'allowed-pid', hiddenPid: 'hidden-pid', clientId: 'private-client', clientSecret: 'private-secret', integrationToken: 'private-direct-token', endpointPrefix: 'release.slo.endpoint.', commandCode: 'release.slo.command', handlerCode: 'release.slo.handler' };
  let created;
  const metric = class { constructor(name) { this.name = name; } add(value, tags) { observations.push({ name: this.name, value, tags }); } };
  const response = (status, value, headers) => ({ status, body: status === 204 ? '' : JSON.stringify(value),
    headers, timings: { duration: 5 }, json: () => value });
  const context = { __ENV: { PROFILE: 'smoke', BUSINESS_FIXTURE_PATH: '/private/fixture' },
    __VU: 1, __ITER: 0, open: () => JSON.stringify(fixture), Rate: metric, Counter: metric, Trend: metric,
    check: (r, assertions) => Object.values(assertions).every(fn => fn(r)),
    http: { expectedStatuses: status => status,
      get: (url, args) => response(200, {}, { 'X-Request-Id': args.headers['X-Request-Id'] }),
      request: (method, url, body, args) => {
        const tag = args.tags.operation;
        const endpoint = tag.startsWith('endpoint');
        const operation = endpoint ? tag[8].toLowerCase()+tag.slice(9) : tag;
        if (endpoint) {
          assert.match(url, /\/api\/open\/v1\/endpoints\/release\.slo\.endpoint\./);
          assert.ok(url.includes('endpoint.' + (operation === 'replay' ? 'create' : operation === 'commandReplay' ? 'command' : operation)));
        }
        const headers = { 'X-Request-Id': args.headers['X-Request-Id'] };
        if (operation === 'create') {
          created = { ...JSON.parse(body), pid: 'fresh-pid' };
          return response(201, created, { ...headers, ETag: '"v1"', Location: '/api/open/v1/resources/release.dynamic.parts/fresh-pid' });
        }
        if (operation === 'replay') return response(201, created, { ...headers, ETag: '"v1"', 'Idempotent-Replay': 'true' });
        if (operation === 'get') return response(200, created, { ...headers, ETag: '"v1"' });
        if (operation === 'list') return response(200, { items: [{ pid: 'allowed-pid', name: 'Seed', zone: 'A' }, created] }, headers);
        if (operation === 'query') return response(200, { items: [{ pid: 'allowed-pid', name: 'Seed', ...((endpoint ? invalidEndpointQuery : invalidQuery) ? { secret: 'private-value' } : {}) }] }, headers);
        if (operation === 'update') { created = { ...created, ...JSON.parse(body) }; return response(200, created, { ...headers, ETag: '"v2"' }); }
        if (operation === 'command' || operation === 'commandReplay') {
          created = { ...created, name: 'Business API qualified' };
          return response(200, { action: fixture.commandCode, resource: created, idempotentReplay: operation === 'commandReplay' },
            { ...headers, ETag: '"v3"', ...(operation === 'commandReplay' ? { 'Idempotent-Replay': 'true' } : {}) });
        }
        if (operation === 'handler') return response(200, { countOnPage: 2, hasMore: false }, headers);
        if (operation === 'delete') return response(204, null, headers);
        throw new Error('Unexpected workload operation');
      } } };
  const executable = source.replace(/^import .*;$/gm, '').replace(/export default function/g, 'function runIteration')
    .replace(/export (const|function)/g, '$1');
  runInNewContext(executable, context);
  return { context, observations };
}

test('fixed k6 workload executes all nineteen resource and Controller operations and checks public results', () => {
  const { context, observations } = runtime();
  context.runIteration({ token: 'private-token', businessToken: 'private-machine-token', runId: 'fresh-run' });
  assert.deepEqual(observations.filter(row => row.name === 'business_requests').map(row => row.tags.operation),
    ['create', 'replay', 'get', 'list', 'query', 'update', 'command', 'commandReplay', 'handler', 'endpointCreate', 'endpointReplay', 'endpointGet', 'endpointList', 'endpointQuery', 'endpointUpdate', 'endpointCommand', 'endpointCommandReplay', 'endpointDelete', 'delete']);
  assert.equal(observations.filter(row => row.name === 'business_errors').length, 19);
  assert.ok(observations.filter(row => row.name === 'business_errors').every(row => row.value === false));
});

test('query private field makes the actual workload check red and prevents false completed writes', () => {
  const { context, observations } = runtime(true);
  context.runIteration({ token: 'private-token', businessToken: 'private-machine-token', runId: 'fresh-run' });
  assert.deepEqual(observations.filter(row => row.name === 'business_requests').map(row => row.tags.operation),
    ['create', 'replay', 'get', 'list', 'query']);
  assert.ok(observations.some(row => row.name === 'business_errors' && row.tags.operation === 'query' && row.value === true));
});

test('Controller query private field makes its own SLO metric red and stops later writes', () => {
  const { context, observations } = runtime(false, true);
  context.runIteration({ token: 'private-token', businessToken: 'private-machine-token', runId: 'fresh-run' });
  assert.ok(observations.some(row => row.name === 'business_errors' && row.tags.operation === 'endpointQuery' && row.value === true));
  assert.ok(!observations.some(row => row.name === 'business_requests' && row.tags.operation === 'endpointUpdate'));
});

test('production fixture cannot silently downgrade to the original identity-only workload', () => {
  const { context } = runtime();
  context.__ENV.PROFILE = 'production';
  // The guard is evaluated by the real module, rather than a mirrored validator.
  const executable = source.replace(/^import .*;$/gm, '').replace(/export default function/g, 'function runIteration').replace(/export (const|function)/g, '$1');
  context.__ENV = { PROFILE: 'production', CLIENT_ID: 'private-client', CLIENT_SECRET: 'private-secret' };
  const production = { ...context, http: { post: () => { throw new Error('Unexpected authentication call'); } } };
  runInNewContext(executable, production);
  assert.throws(() => production.setup(), /requires the dynamic business fixture/);
});


test('actual Python SLO verifier rejects missing samples, false green and wrong workload scope', async () => {
  const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { spawnSync } = await import('node:child_process');
  const metrics = {};
  const metric = (name, values) => { metrics[name] = { values, thresholds: { required: { ok: true } } }; };
  metric('open_api_errors', { rate: 0, passes: 0, fails: 20 });
  metric('open_api_duration', { 'p(95)': 100, 'p(99)': 150 });
  metric('http_reqs', { rate: 100 });
  metric('business_requests', { count: 380, rate: 70 });
  metric('business_errors', { rate: 0, passes: 0, fails: 380 });
  for (const operation of ['create', 'replay', 'get', 'list', 'query', 'update', 'command', 'commandReplay', 'handler', 'endpointCreate', 'endpointReplay', 'endpointGet', 'endpointList', 'endpointQuery', 'endpointUpdate', 'endpointCommand', 'endpointCommandReplay', 'endpointDelete', 'delete']) {
    metric(`business_requests{operation:${operation}}`, { count: 20 });
    metric(`business_errors{operation:${operation}}`, { rate: 0, passes: 0, fails: 20 });
    metric(`business_duration{operation:${operation}}`, { 'p(95)': 100, 'p(99)': 150 });
  }
  const report = { profile: 'production', scope: 'dynamic-business', metrics };
  const directory = mkdtempSync(join(tmpdir(), 'slo-contract-'));
  const file = join(directory, 'summary.json');
  const verify = value => {
    writeFileSync(file, JSON.stringify(value));
    return spawnSync('python3', [new URL('./ci/verify-open-platform-slo.py', import.meta.url).pathname, file, 'production'], { encoding: 'utf8' });
  };
  try {
    const normal = verify(report); assert.equal(normal.status, 0, normal.stderr);
    for (const change of [
      value => { value.scope = 'baseline-only'; },
      value => { delete value.metrics['business_requests{operation:delete}']; },
      value => { value.metrics['business_errors{operation:create}'].values.fails = 0; },
      value => { value.metrics['business_duration{operation:query}'].values['p(95)'] = 900; },
      value => { value.metrics.business_requests.values.count = 201; },
      value => { value.metrics['business_requests{operation:get}'].values.count = true; },
      value => { value.metrics.business_requests.values.rate = 19; },
      value => { value.metrics['business_duration{operation:delete}'].thresholds.required.ok = false; },
    ]) {
      const bad = JSON.parse(JSON.stringify(report)); change(bad); const result = verify(bad);
      assert.notEqual(result.status, 0, result.stdout);
    }
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('actual standalone shell prevents k6 automatic setup-data export and keeps secrets out of argv', async () => {
  const { mkdtempSync, writeFileSync, readFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { spawnSync } = await import('node:child_process');
  const directory = mkdtempSync(join(tmpdir(), 'slo-environment-'));
  const calls = join(directory, 'calls');
  const summary = join(directory, 'summary.json');
  writeFileSync(join(directory, 'k6'), '#!/bin/bash\nprintf "%s\\n" "${K6_SUMMARY_EXPORT-unset}" "$@" > "$CALLS"\nexit 17\n', { mode: 0o700 });
  try {
    const result = spawnSync('bash', [new URL('./run-open-platform-slo-gate.sh', import.meta.url).pathname, 'smoke'],
      { encoding: 'utf8', env: { ...process.env, PATH: directory + ':' + process.env.PATH, CALLS: calls,
        CLIENT_ID: 'private-client-sentinel', CLIENT_SECRET: 'private-secret-sentinel', K6_SUMMARY_EXPORT: summary } });
    assert.equal(result.status, 17, result.stderr);
    const actual = readFileSync(calls, 'utf8');
    assert.match(actual, /^unset\nrun\n/);
    assert.ok(actual.includes('SUMMARY_PATH=' + summary));
    assert.doesNotMatch(actual + result.stdout + result.stderr, /private-.*-sentinel/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
