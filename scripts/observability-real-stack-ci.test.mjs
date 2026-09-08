import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const runner = fs.readFileSync(path.join(root, 'scripts', 'observability-real-stack-ci.sh'), 'utf8');
const compose = fs.readFileSync(path.join(root, 'docker-compose.observability.yml'), 'utf8');
const prometheus = fs.readFileSync(path.join(root, 'docker', 'prometheus', 'prometheus.yml'), 'utf8');
const browser = fs.readFileSync(path.join(root, 'scripts', 'observability-grafana-browser.mjs'), 'utf8');
const reliableMetrics = fs.readFileSync(path.join(root, 'platform', 'src', 'main', 'java', 'com', 'auraboot', 'framework', 'integration', 'ReliableIntegrationMetrics.java'), 'utf8');
const jwtFilter = fs.readFileSync(path.join(root, 'platform', 'src', 'main', 'java', 'com', 'auraboot', 'framework', 'application', 'web', 'filter', 'JwtAuthenticationFilter.java'), 'utf8');

test('real-stack runner proves every observability acceptance surface', () => {
  for (const token of ['prometheus', 'alertmanager', 'observability-canary-receiver', 'pushgateway', 'loki', 'tempo', 'grafana']) {
    assert.match(runner, new RegExp(token));
    assert.match(compose, new RegExp(`${token}:`));
  }
  for (const token of ['observability-postgres:', 'app:']) assert.match(compose, new RegExp(token));
  for (const proof of ['notificationDeduplicated', 'loki-query.json', 'tempo-trace.json', 'grafana-dashboards.json', 'AuraBootAvailabilitySloBurn', 'restart prometheus loki tempo']) {
    assert.ok(runner.includes(proof), proof);
  }
  assert.match(runner, /Buffer\.from\(span\.traceId \?\? '', 'base64'\)\.toString\('hex'\)/);
  for (const proof of ['application-prometheus.txt', 'application-request-log.json', 'applicationServerAndDownstreamSpans', 'observability-grafana-browser.mjs', 'grafanaBrowserNavigation']) {
    assert.ok(runner.includes(proof), proof);
  }
  assert.match(browser, /a\[href\*="\$\{traceId\}"\]/);
  assert.match(browser, /grafana-tempo-trace\.png/);
  assert.match(jwtFilter, /HTTP request completed method=\{\} path=\{\} status=\{\}/);
  for (const metric of ['auraboot_reliable_delivery_pending', 'auraboot_reliable_delivery_dlq', 'auraboot_reliable_delivery_oldest_pending_age_seconds', 'auraboot_reliable_delivery_unhealthy']) {
    assert.ok(reliableMetrics.includes(metric), metric);
  }
  assert.match(runner, /find \\\n[\s\S]*docker\/prometheus[\s\S]*docker\/grafana\/dashboards[\s\S]*-exec chmod a\+r/);
  for (const port of ['PROMETHEUS', 'ALERTMANAGER', 'CANARY', 'PUSHGATEWAY', 'LOKI', 'TEMPO', 'ZIPKIN', 'GRAFANA']) {
    assert.match(runner, new RegExp(`AURA_OBS_${port}_PORT`));
    assert.match(compose, new RegExp(`AURA_OBS_${port}_PORT`));
  }
  assert.doesNotMatch(runner, /docker compose[^\n]*down|down --volumes/);
});

test('Prometheus routes alerts and scrapes the controlled metric source', () => {
  assert.match(prometheus, /alertmanagers:/);
  assert.match(prometheus, /alertmanager:9093/);
  assert.match(prometheus, /pushgateway:9091/);
});
