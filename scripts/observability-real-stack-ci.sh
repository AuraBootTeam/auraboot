#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACTS="${AURA_REGRESSION_ARTIFACTS:-$PROJECT_ROOT/.workspace/observability-real-stack}"
COMPOSE=(docker compose -f "$PROJECT_ROOT/docker-compose.observability.yml" -p aura-ci-observability --profile acceptance)
RUN_ID="obs-$(date -u +%Y%m%dT%H%M%SZ)-$$"
TRACE_ID=""
export AURA_OBS_PROMETHEUS_PORT="${AURA_OBS_PROMETHEUS_PORT:-29090}"
export AURA_OBS_ALERTMANAGER_PORT="${AURA_OBS_ALERTMANAGER_PORT:-29093}"
export AURA_OBS_CANARY_PORT="${AURA_OBS_CANARY_PORT:-28080}"
export AURA_OBS_PUSHGATEWAY_PORT="${AURA_OBS_PUSHGATEWAY_PORT:-29091}"
export AURA_OBS_LOKI_PORT="${AURA_OBS_LOKI_PORT:-23100}"
export AURA_OBS_TEMPO_PORT="${AURA_OBS_TEMPO_PORT:-23200}"
export AURA_OBS_ZIPKIN_PORT="${AURA_OBS_ZIPKIN_PORT:-29411}"
export AURA_OBS_GRAFANA_PORT="${AURA_OBS_GRAFANA_PORT:-23000}"
export AURA_OBS_APP_PORT="${AURA_OBS_APP_PORT:-26443}"
mkdir -p "$ARTIFACTS"

invalid() {
  printf '[observability-real-stack] environment-invalid: %s\n' "$*" >&2
  exit 2
}

command -v docker >/dev/null 2>&1 || invalid 'docker is unavailable'
command -v curl >/dev/null 2>&1 || invalid 'curl is unavailable'
command -v node >/dev/null 2>&1 || invalid 'node is unavailable'
command -v java >/dev/null 2>&1 || invalid 'java is unavailable'
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
"$PROJECT_ROOT/platform/gradlew" -p "$PROJECT_ROOT/platform" bootJar --no-daemon -x test \
  > "$ARTIFACTS/bootjar.log" 2>&1
"${COMPOSE[@]}" up -d --build observability-postgres app prometheus alertmanager \
  observability-canary-receiver pushgateway loki tempo grafana
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
wait_http app "http://127.0.0.1:$AURA_OBS_APP_PORT/api/bootstrap/status"

curl --fail --silent --show-error \
  "http://127.0.0.1:$AURA_OBS_APP_PORT/api/bootstrap/status" > "$ARTIFACTS/bootstrap-status.json"
if ! node - "$ARTIFACTS/bootstrap-status.json" <<'NODE'
const fs = require('node:fs');
process.exit(JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))?.data?.initialized === true ? 0 : 1);
NODE
then
  curl --fail --silent --show-error -X POST \
    "http://127.0.0.1:$AURA_OBS_APP_PORT/api/bootstrap/setup" \
    -H 'Content-Type: application/json' \
    --data '{"companyName":"Observability Acceptance","adminEmail":"admin@auraboot.com","adminPassword":"Test2026x","adminDisplayName":"Acceptance Admin","systemMode":"single"}' \
    > "$ARTIFACTS/bootstrap.json"
fi
LOGIN_RESPONSE="$(curl --fail --silent --show-error -X POST \
  "http://127.0.0.1:$AURA_OBS_APP_PORT/api/auth/login" \
  -H 'Content-Type: application/json' \
  --data '{"email":"admin@auraboot.com","password":"Test2026x"}')"
JWT="$(printf '%s' "$LOGIN_RESPONSE" | node -e '
let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => input += chunk);
process.stdin.on("end", () => {
  const value = JSON.parse(input)?.data?.jwt;
  if (!value) process.exit(1);
  process.stdout.write(value);
});')" || { printf '[observability-real-stack] product-failure: login returned no JWT\n' >&2; exit 1; }
unset LOGIN_RESPONSE
curl --fail --silent --show-error -D "$ARTIFACTS/application-response.headers" \
  -H "Authorization: Bearer $JWT" \
  "http://127.0.0.1:$AURA_OBS_APP_PORT/api/observability/snapshot" \
  > "$ARTIFACTS/application-response.json"
TRACE_ID="$(awk 'BEGIN{IGNORECASE=1} /^X-Trace-Id:/ {gsub("\\r", "", $2); print $2}' \
  "$ARTIFACTS/application-response.headers" | tail -1)"
[[ "$TRACE_ID" =~ ^[0-9a-f]{32}$ ]] \
  || { printf '[observability-real-stack] product-failure: application response has no valid X-Trace-Id\n' >&2; exit 1; }

deadline=$((SECONDS + 120))
while true; do
  if curl --fail --silent --show-error \
    "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/api/traces/$TRACE_ID" \
    > "$ARTIFACTS/tempo-trace.json" 2>/dev/null; then
    break
  fi
  (( SECONDS < deadline )) || { printf '[observability-real-stack] product-failure: application trace did not reach Tempo\n' >&2; exit 1; }
  sleep 2
done

deadline=$((SECONDS + 120))
while true; do
  curl --fail --silent --show-error --get \
    --data-urlencode 'query=up{job="aura-monolith"}' \
    "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/api/v1/query" \
    > "$ARTIFACTS/prometheus-app-up.json"
  if node - "$ARTIFACTS/prometheus-app-up.json" <<'NODE'
const fs = require('node:fs');
const result = JSON.parse(fs.readFileSync(process.argv[2], 'utf8')).data?.result ?? [];
process.exit(result.some(item => item.value?.[1] === '1') ? 0 : 1);
NODE
  then break; fi
  (( SECONDS < deadline )) || { printf '[observability-real-stack] product-failure: Prometheus did not scrape AuraBoot\n' >&2; exit 1; }
  sleep 2
done
curl --fail --silent --show-error \
  "http://127.0.0.1:$AURA_OBS_APP_PORT/actuator/prometheus" \
  > "$ARTIFACTS/application-prometheus.txt"
curl --fail --silent --show-error --get \
  --data-urlencode 'query={__name__=~"auraboot_reliable_delivery_.+|auraboot_integration_events_total"}' \
  "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/api/v1/query" \
  > "$ARTIFACTS/prometheus-app-metrics.json"
node - "$ARTIFACTS/application-prometheus.txt" "$ARTIFACTS/prometheus-app-metrics.json" <<'NODE'
const fs = require('node:fs');
const metrics = fs.readFileSync(process.argv[2], 'utf8');
const requiredNames = [
  'auraboot_reliable_delivery_pending',
  'auraboot_reliable_delivery_dlq',
  'auraboot_reliable_delivery_oldest_pending_age_seconds',
  'auraboot_reliable_delivery_unhealthy',
];
for (const required of requiredNames) if (!metrics.includes(required)) throw new Error(`application metric missing: ${required}`);
if (!metrics.includes('auraboot_integration_events_total{outcome="retry_scheduled"}')) {
  throw new Error('bounded retry counter missing from application exporter');
}
const scraped = JSON.parse(fs.readFileSync(process.argv[3], 'utf8')).data?.result ?? [];
const scrapedNames = new Set(scraped.map(item => item.metric?.__name__));
for (const required of requiredNames) if (!scrapedNames.has(required)) throw new Error(`Prometheus scrape missing: ${required}`);
if (!scraped.some(item => item.metric?.__name__ === 'auraboot_integration_events_total'
  && item.metric?.outcome === 'retry_scheduled')) throw new Error('Prometheus scrape missing bounded retry counter');
NODE

"${COMPOSE[@]}" logs --no-color app > "$ARTIFACTS/application.log"
node - "$ARTIFACTS/application.log" "$TRACE_ID" "$ARTIFACTS/application-request-log.json" <<'NODE'
const fs = require('node:fs');
const [logPath, traceId, output] = process.argv.slice(2);
for (const raw of fs.readFileSync(logPath, 'utf8').split('\n')) {
  const start = raw.indexOf('{');
  if (start < 0) continue;
  try {
    const line = raw.slice(start);
    const value = JSON.parse(line);
    const actualTrace = value.traceId ?? value['trace.id'];
    if (actualTrace !== traceId || !String(value.message).includes('/api/observability/snapshot')) continue;
    if (!value.spanId && !value['span.id']) throw new Error('request log has no span id');
    if (!value.tenantId || !value.userId) throw new Error('request log has no tenant/user identity');
    const serialized = JSON.stringify(value);
    if (/Bearer |Test2026x|admin@auraboot\.com/.test(serialized)) throw new Error('request log leaked credentials');
    fs.writeFileSync(output, line + '\n');
    process.exit(0);
  } catch (error) {
    if (String(error.message).startsWith('request log')) throw error;
  }
}
throw new Error('correlated structured application request log not found');
NODE

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
node - "$timestamp_ns" "$RUN_ID" "$ARTIFACTS/application-request-log.json" > "$ARTIFACTS/loki-push.json" <<'NODE'
const fs = require('node:fs');
const [timestamp, runId, logPath] = process.argv.slice(2);
const line = fs.readFileSync(logPath, 'utf8').trim();
process.stdout.write(JSON.stringify({ streams: [{ stream: { service: 'auraboot-application', run_id: runId }, values: [[timestamp, line]] }] }));
NODE
curl --fail --silent --show-error -H 'Content-Type: application/json' \
  --data-binary "@$ARTIFACTS/loki-push.json" "http://127.0.0.1:$AURA_OBS_LOKI_PORT/loki/api/v1/push"

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

curl --fail --silent --show-error --get --data-urlencode 'query={service="auraboot-application"}' \
  "http://127.0.0.1:$AURA_OBS_LOKI_PORT/loki/api/v1/query_range" > "$ARTIFACTS/loki-query.json"
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
const spans = (tempo.batches ?? []).flatMap(batch =>
  (batch.scopeSpans ?? []).flatMap(scope => scope.spans ?? []));
if (spans.length < 2) throw new Error(`expected server and downstream spans, received ${spans.length}`);
if (!spans.some(span => span.kind === 'SPAN_KIND_SERVER' || span.kind === 2)) throw new Error('server span missing');
if (!spans.some(span => String(span.name).includes('observability-snapshot'))) throw new Error('downstream observation span missing');
for (const name of ['Prometheus', 'Loki', 'Tempo']) if (!datasources.some(x => x.name === name)) throw new Error(`Grafana datasource missing: ${name}`);
if (dashboards.length < 9) throw new Error(`Grafana loaded ${dashboards.length} dashboards; expected at least 9`);
fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({ contractVersion: 1, status: 'passed', runId, traceId, checks: { applicationScrape: true, reliableDeliveryMetrics: true, metrics: true, alerts: true, notificationDeduplicated: true, structuredApplicationLogs: true, logs: true, applicationServerAndDownstreamSpans: true, traces: true, logTraceCorrelation: true, dashboards: dashboards.length, sloBurn: true } }, null, 2) + '\n');
NODE

PLAYWRIGHT_BIN="${PLAYWRIGHT_BIN:-$PROJECT_ROOT/web-admin/node_modules/.bin/playwright}"
[[ -x "$PLAYWRIGHT_BIN" ]] || invalid "Playwright binary not found: $PLAYWRIGHT_BIN"
PLAYWRIGHT_MODULE="$(node - "$PLAYWRIGHT_BIN" <<'NODE'
const path = require('node:path');
const { createRequire } = require('node:module');
const requireFromBin = createRequire(path.resolve(process.argv[2]));
process.stdout.write(requireFromBin.resolve('@playwright/test'));
NODE
)"
node "$PROJECT_ROOT/scripts/observability-grafana-browser.mjs" \
  "http://127.0.0.1:$AURA_OBS_GRAFANA_PORT" "$TRACE_ID" "$ARTIFACTS" "$PLAYWRIGHT_MODULE"
node - "$ARTIFACTS/summary.json" <<'NODE'
const fs = require('node:fs');
const file = process.argv[2];
const summary = JSON.parse(fs.readFileSync(file, 'utf8'));
summary.checks.grafanaBrowserNavigation = true;
fs.writeFileSync(file, JSON.stringify(summary, null, 2) + '\n');
NODE

# Retention is proved across bounded service restarts. The project and volumes remain available
# for evidence inspection; the CI runtime owner can remove them after its retention TTL.
"${COMPOSE[@]}" restart prometheus loki tempo
wait_http prometheus "http://127.0.0.1:$AURA_OBS_PROMETHEUS_PORT/-/ready"
wait_http loki "http://127.0.0.1:$AURA_OBS_LOKI_PORT/ready"
wait_http tempo "http://127.0.0.1:$AURA_OBS_TEMPO_PORT/ready"
curl --fail --silent --show-error --get --data-urlencode 'query={service="auraboot-application"}' \
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
