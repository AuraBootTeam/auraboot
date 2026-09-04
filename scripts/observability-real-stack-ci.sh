#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACTS="${AURA_REGRESSION_ARTIFACTS:-$PROJECT_ROOT/.workspace/observability-real-stack}"
COMPOSE=(docker compose -f "$PROJECT_ROOT/docker-compose.observability.yml" -p aura-ci-observability)
RUN_ID="obs-$(date -u +%Y%m%dT%H%M%SZ)-$$"
TRACE_ID="$(printf '%032x' "$$")"
SPAN_ID="$(printf '%016x' "$$")"
export AURA_OBS_PROMETHEUS_PORT="${AURA_OBS_PROMETHEUS_PORT:-29090}"
export AURA_OBS_ALERTMANAGER_PORT="${AURA_OBS_ALERTMANAGER_PORT:-29093}"
export AURA_OBS_CANARY_PORT="${AURA_OBS_CANARY_PORT:-28080}"
export AURA_OBS_PUSHGATEWAY_PORT="${AURA_OBS_PUSHGATEWAY_PORT:-29091}"
export AURA_OBS_LOKI_PORT="${AURA_OBS_LOKI_PORT:-23100}"
export AURA_OBS_TEMPO_PORT="${AURA_OBS_TEMPO_PORT:-23200}"
export AURA_OBS_ZIPKIN_PORT="${AURA_OBS_ZIPKIN_PORT:-29411}"
export AURA_OBS_GRAFANA_PORT="${AURA_OBS_GRAFANA_PORT:-23000}"
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

# Exact-ref CI checkouts may be created under a restrictive umask. Bind-mounted
# configuration must remain readable by the non-root users in the observability
# images; only the ephemeral checkout permissions are relaxed here.
find \
  "$PROJECT_ROOT/docker/prometheus" \
  "$PROJECT_ROOT/docker/alertmanager" \
  "$PROJECT_ROOT/docker/observability-canary" \
  "$PROJECT_ROOT/docker/loki" \
  "$PROJECT_ROOT/docker/tempo" \
  "$PROJECT_ROOT/docker/grafana/provisioning" \
  "$PROJECT_ROOT/docker/grafana/dashboards" \
  -type f -exec chmod a+r {} +

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

wait_http prometheus "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/-/ready"
wait_http alertmanager "http://127.0.0.1:$AURA_OBS_ALERTMANAGER_PORT/-/ready"
wait_http canary-receiver "http://127.0.0.1:$AURA_OBS_CANARY_PORT/health"
wait_http pushgateway "http://127.0.0.1:$AURA_OBS_PUSHGATEWAY_PORT/-/ready"
wait_http loki "http://127.0.0.1:$AURA_OBS_LOKI_PORT/ready"
wait_http tempo "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/ready"
wait_http grafana "http://127.0.0.1:$AURA_OBS_GRAFANA_PORT/api/health"

cat <<METRICS | curl --fail --silent --show-error --data-binary @- \
  "http://127.0.0.1:$AURA_OBS_PUSHGATEWAY_PORT/metrics/job/auraboot-observability-canary"
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
  --data-binary "@$ARTIFACTS/loki-push.json" "http://127.0.0.1:$AURA_OBS_LOKI_PORT/loki/api/v1/push"

node - "$TRACE_ID" "$SPAN_ID" "$RUN_ID" > "$ARTIFACTS/zipkin-trace.json" <<'NODE'
const [traceId, spanId, runId] = process.argv.slice(2);
process.stdout.write(JSON.stringify([{ traceId, id: spanId, name: 'auraboot.observability.canary', timestamp: Date.now() * 1000, duration: 1000, localEndpoint: { serviceName: 'auraboot-canary' }, tags: { runId, tenantId: '990301', userId: '990302' } }]));
NODE
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data-binary "@$ARTIFACTS/zipkin-trace.json" "http://127.0.0.1:$AURA_OBS_ZIPKIN_PORT/api/v2/spans"

deadline=$((SECONDS + 120))
while true; do
  curl --fail --silent --show-error "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/api/v1/alerts" > "$ARTIFACTS/prometheus-alerts.json"
  curl --fail --silent --show-error "http://127.0.0.1:$AURA_OBS_CANARY_PORT/events" > "$ARTIFACTS/alert-notifications.json"
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
  "http://127.0.0.1:$AURA_OBS_LOKI_PORT/loki/api/v1/query_range" > "$ARTIFACTS/loki-query.json"
curl --fail --silent --show-error "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/api/traces/$TRACE_ID" > "$ARTIFACTS/tempo-trace.json"
curl --fail --silent --show-error -u admin:auraboot-observability-ci \
  "http://127.0.0.1:$AURA_OBS_GRAFANA_PORT/api/datasources" > "$ARTIFACTS/grafana-datasources.json"
curl --fail --silent --show-error -u admin:auraboot-observability-ci \
  "http://127.0.0.1:$AURA_OBS_GRAFANA_PORT/api/search?type=dash-db" > "$ARTIFACTS/grafana-dashboards.json"

node - "$ARTIFACTS" "$RUN_ID" "$TRACE_ID" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const [dir, runId, traceId] = process.argv.slice(2);
const read = name => JSON.parse(fs.readFileSync(path.join(dir, name)));
const loki = read('loki-query.json');
const tempo = read('tempo-trace.json');
const datasources = read('grafana-datasources.json');
const dashboards = read('grafana-dashboards.json');
const tempoTraceIds = (tempo.batches ?? []).flatMap(batch =>
  (batch.scopeSpans ?? []).flatMap(scope =>
    (scope.spans ?? []).map(span => Buffer.from(span.traceId ?? '', 'base64').toString('hex'))));
if (!JSON.stringify(loki).includes(runId) || !JSON.stringify(loki).includes(traceId)) throw new Error('Loki correlation proof missing');
if (!tempoTraceIds.includes(traceId)) throw new Error('Tempo trace proof missing');
for (const name of ['Prometheus', 'Loki', 'Tempo']) if (!datasources.some(x => x.name === name)) throw new Error(`Grafana datasource missing: ${name}`);
if (dashboards.length < 9) throw new Error(`Grafana loaded ${dashboards.length} dashboards; expected at least 9`);
fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({ contractVersion: 1, status: 'passed', runId, traceId, checks: { metrics: true, alerts: true, notificationDeduplicated: true, logs: true, traces: true, logTraceCorrelation: true, dashboards: dashboards.length, sloBurn: true } }, null, 2) + '\n');
NODE

# Retention is proved across bounded service restarts. The project and volumes remain available
# for evidence inspection; the CI runtime owner can remove them after its retention TTL.
"${COMPOSE[@]}" restart prometheus loki tempo
wait_http prometheus "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/-/ready"
wait_http loki "http://127.0.0.1:$AURA_OBS_LOKI_PORT/ready"
wait_http tempo "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/ready"
curl --fail --silent --show-error --get --data-urlencode 'query={service="auraboot-canary"}' \
  "http://127.0.0.1:$AURA_OBS_LOKI_PORT/loki/api/v1/query_range" | grep -q "$TRACE_ID"
curl --fail --silent --show-error "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/api/traces/$TRACE_ID" \
  > "$ARTIFACTS/tempo-trace-after-restart.json"
node - "$ARTIFACTS/tempo-trace-after-restart.json" "$TRACE_ID" <<'NODE'
const fs = require('node:fs');
const [tempoPath, traceId] = process.argv.slice(2);
const tempo = JSON.parse(fs.readFileSync(tempoPath, 'utf8'));
const traceIds = (tempo.batches ?? []).flatMap(batch =>
  (batch.scopeSpans ?? []).flatMap(scope =>
    (scope.spans ?? []).map(span => Buffer.from(span.traceId ?? '', 'base64').toString('hex'))));
if (!traceIds.includes(traceId)) process.exit(1);
NODE
printf '[observability-real-stack] PASS run=%s trace=%s artifacts=%s runtime=retained\n' "$RUN_ID" "$TRACE_ID" "$ARTIFACTS"
