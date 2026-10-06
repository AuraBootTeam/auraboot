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
    assert.deepEqual(Object.keys(exported).sort(), ['metrics', 'root_group']);
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
