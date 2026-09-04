import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runner = fs.readFileSync(path.join(root, 'scripts', 'observability-real-stack-ci.sh'), 'utf8');
const compose = fs.readFileSync(path.join(root, 'docker-compose.observability.yml'), 'utf8');
const prometheus = fs.readFileSync(path.join(root, 'docker', 'prometheus', 'prometheus.yml'), 'utf8');

test('real-stack runner proves every observability acceptance surface', () => {
  for (const token of ['prometheus', 'alertmanager', 'observability-canary-receiver', 'pushgateway', 'loki', 'tempo', 'grafana']) {
    assert.match(runner, new RegExp(token));
    assert.match(compose, new RegExp(`${token}:`));
  }
  for (const proof of ['notificationDeduplicated', 'loki-query.json', 'tempo-trace.json', 'grafana-dashboards.json', 'AuraBootAvailabilitySloBurn', 'restart prometheus loki tempo']) {
    assert.ok(runner.includes(proof), proof);
  }
  assert.doesNotMatch(runner, /docker compose[^\n]*down|down --volumes/);
});

test('Prometheus routes alerts and scrapes the controlled metric source', () => {
  assert.match(prometheus, /alertmanagers:/);
  assert.match(prometheus, /alertmanager:9093/);
  assert.match(prometheus, /pushgateway:9091/);
});
