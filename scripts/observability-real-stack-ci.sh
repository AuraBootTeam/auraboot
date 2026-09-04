#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACTS="${AURA_REGRESSION_ARTIFACTS:-$PROJECT_ROOT/.workspace/observability-real-stack}"
COMPOSE=(docker compose -f "$PROJECT_ROOT/docker-compose.observability.yml" -p aura-ci-observability)
RUN_ID="obs-$(date -u +%Y%m%dT%H%M%SZ)-$$"
TRACE_ID="$(printf '%032x' "$$")"
SPAN_ID="$(printf '%016x' "$$")"
mkdir -p "$ARTIFACTS"

invalid() {
  printf '[observability-real-stack] environment-invalid: %s\n' "$*" >&2
  exit 2
}

command -v docker >/dev/null 2>&1 || invalid 'docker is unavailable'
command -v curl >/dev/null 2>&1 || invalid 'curl is unavailable'
command -v node >/dev/null 2>&1 || invalid 'node is unavailable'
docker compose version >/dev/null 2>&1 || invalid 'docker compose v2 is unavailable'
docker info >/dev/null 2>&1 || invalid 'Docker daemon is unavailable'

"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" up -d prometheus alertmanager observability-canary-receiver pushgateway loki tempo grafana
"${COMPOSE[@]}" ps --all > "$ARTIFACTS/compose-ps.txt"

wait_http() {
  local name="$1" url="$2" deadline=$((SECONDS + 180))
  until curl --fail --silent --show-error "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      "${COMPOSE[@]}" logs --no-color > "$ARTIFACTS/compose.log" 2>&1 || true
      invalid "$name did not become ready: $url"
    fi
    sleep 2
  done
}

wait_http prometheus http://127.0.0.1:9090/-/ready
wait_http alertmanager http://127.0.0.1:19093/-/ready
wait_http canary-receiver http://127.0.0.1:18080/health
wait_http pushgateway http://127.0.0.1:19091/-/ready
wait_http loki http://127.0.0.1:13100/ready
wait_http tempo http://127.0.0.1:13200/ready
wait_http grafana http://127.0.0.1:3000/api/health

cat <<METRICS | curl --fail --silent --show-error --data-binary @- \
  "http://127.0.0.1:19091/metrics/job/auraboot-observability-canary"
# TYPE auraboot_observability_canary gauge
auraboot_observability_canary{run_id="$RUN_ID"} 1
# TYPE auraboot_sli_availability_ratio gauge
auraboot_sli_availability_ratio{run_id="$RUN_ID"} 0.80
# TYPE auraboot_reliable_delivery_pending gauge
auraboot_reliable_delivery_pending 3
# TYPE auraboot_reliable_delivery_retry_total counter
auraboot_reliable_delivery_retry_total 1
# TYPE auraboot_reliable_delivery_dlq_total counter
auraboot_reliable_delivery_dlq_total 1
# TYPE auraboot_behavior_ingest_lag gauge
auraboot_behavior_ingest_lag 2
# TYPE auraboot_dependency_unhealthy gauge
auraboot_dependency_unhealthy 1
METRICS

timestamp_ns="$(date +%s)000000000"
node - "$timestamp_ns" "$RUN_ID" "$TRACE_ID" > "$ARTIFACTS/loki-push.json" <<'NODE'
const [timestamp, runId, traceId] = process.argv.slice(2);
const line = JSON.stringify({ level: 'ERROR', service: 'auraboot-canary', tenantId: '990301', userId: '990302', traceId, spanId: traceId.slice(16), runId, message: 'controlled reliability tracing canary' });
process.stdout.write(JSON.stringify({ streams: [{ stream: { service: 'auraboot-canary', run_id: runId }, values: [[timestamp, line]] }] }));
NODE
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data-binary "@$ARTIFACTS/loki-push.json" http://127.0.0.1:13100/loki/api/v1/push

node - "$TRACE_ID" "$SPAN_ID" "$RUN_ID" > "$ARTIFACTS/zipkin-trace.json" <<'NODE'
const [traceId, spanId, runId] = process.argv.slice(2);
process.stdout.write(JSON.stringify([{ traceId, id: spanId, name: 'auraboot.observability.canary', timestamp: Date.now() * 1000, duration: 1000, localEndpoint: { serviceName: 'auraboot-canary' }, tags: { runId, tenantId: '990301', userId: '990302' } }]));
NODE
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data-binary "@$ARTIFACTS/zipkin-trace.json" http://127.0.0.1:19411/api/v2/spans

deadline=$((SECONDS + 120))
while true; do
  curl --fail --silent --show-error 'http://127.0.0.1:9090/api/v1/alerts' > "$ARTIFACTS/prometheus-alerts.json"
  curl --fail --silent --show-error 'http://127.0.0.1:18080/events' > "$ARTIFACTS/alert-notifications.json"
  if node - "$ARTIFACTS/prometheus-alerts.json" "$ARTIFACTS/alert-notifications.json" "$RUN_ID" <<'NODE'
const fs = require('node:fs');
const [alertsPath, eventsPath, runId] = process.argv.slice(2);
const alerts = JSON.parse(fs.readFileSync(alertsPath)).data.alerts;
const events = JSON.parse(fs.readFileSync(eventsPath)).events;
const firing = new Set(alerts.filter(x => x.state === 'firing' && x.labels.run_id === runId).map(x => x.labels.alertname));
const deliveries = events.filter(e => e.alerts?.some(a => a.labels?.alertname === 'AuraBootObservabilityCanary' && a.labels?.run_id === runId));
process.exit(firing.has('AuraBootObservabilityCanary') && firing.has('AuraBootAvailabilitySloBurn') && deliveries.length === 1 ? 0 : 1);
NODE
  then break; fi
  (( SECONDS < deadline )) || { printf '[observability-real-stack] product-failure: alerts or deduplicated delivery did not converge\n' >&2; exit 1; }
  sleep 2
done

curl --fail --silent --show-error --get --data-urlencode 'query={service="auraboot-canary"}' \
  http://127.0.0.1:13100/loki/api/v1/query_range > "$ARTIFACTS/loki-query.json"
curl --fail --silent --show-error "http://127.0.0.1:13200/api/traces/$TRACE_ID" > "$ARTIFACTS/tempo-trace.json"
curl --fail --silent --show-error -u admin:auraboot-observability-ci \
  http://127.0.0.1:3000/api/datasources > "$ARTIFACTS/grafana-datasources.json"
curl --fail --silent --show-error -u admin:auraboot-observability-ci \
  'http://127.0.0.1:3000/api/search?type=dash-db' > "$ARTIFACTS/grafana-dashboards.json"

node - "$ARTIFACTS" "$RUN_ID" "$TRACE_ID" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const [dir, runId, traceId] = process.argv.slice(2);
const read = name => JSON.parse(fs.readFileSync(path.join(dir, name)));
const loki = read('loki-query.json');
const tempo = read('tempo-trace.json');
const datasources = read('grafana-datasources.json');
const dashboards = read('grafana-dashboards.json');
if (!JSON.stringify(loki).includes(runId) || !JSON.stringify(loki).includes(traceId)) throw new Error('Loki correlation proof missing');
if (!JSON.stringify(tempo).includes(traceId)) throw new Error('Tempo trace proof missing');
for (const name of ['Prometheus', 'Loki', 'Tempo']) if (!datasources.some(x => x.name === name)) throw new Error(`Grafana datasource missing: ${name}`);
if (dashboards.length < 9) throw new Error(`Grafana loaded ${dashboards.length} dashboards; expected at least 9`);
fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({ contractVersion: 1, status: 'passed', runId, traceId, checks: { metrics: true, alerts: true, notificationDeduplicated: true, logs: true, traces: true, logTraceCorrelation: true, dashboards: dashboards.length, sloBurn: true } }, null, 2) + '\n');
NODE

# Retention is proved across bounded service restarts. The project and volumes remain available
# for evidence inspection; the CI runtime owner can remove them after its retention TTL.
"${COMPOSE[@]}" restart prometheus loki tempo
wait_http prometheus http://127.0.0.1:9090/-/ready
wait_http loki http://127.0.0.1:13100/ready
wait_http tempo http://127.0.0.1:13200/ready
curl --fail --silent --show-error --get --data-urlencode 'query={service="auraboot-canary"}' \
  http://127.0.0.1:13100/loki/api/v1/query_range | grep -q "$TRACE_ID"
curl --fail --silent --show-error "http://127.0.0.1:13200/api/traces/$TRACE_ID" | grep -q "$TRACE_ID"
printf '[observability-real-stack] PASS run=%s trace=%s artifacts=%s runtime=retained\n' "$RUN_ID" "$TRACE_ID" "$ARTIFACTS"
