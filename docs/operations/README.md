# Operations

Observability assets for AuraBoot platform operators.

## Host-First Goldens

- [Behavior Kafka Ingest Runbook](behavior-kafka-ingest-runbook.md) — local
  validation and operations notes for `/api/collect` / `/api/collect/keyed`
  enqueue, native Kafka IT, keyed load/backpressure checks, metrics, alerts,
  server outcome outbox, traceparent propagation, and quarantine retention
  checks without Docker.
- [OEE Host Golden](oee-host-golden.md) — repeatable local validation for the
  PCBA OEE dashboard using host PostgreSQL, Greptime, backend, BFF, and Vite
  without Docker.

## Behavior Kafka Ingest

`BehaviorIngestMetrics` exports the following custom Micrometer meters through
`/actuator/prometheus`:

- `auraboot_behavior_ingest_accepted_total{path}`
- `auraboot_behavior_ingest_enqueued_total{topic}`
- `auraboot_behavior_ingest_persisted_total{outcome}`
- `auraboot_behavior_ingest_quarantined_total{reason}`
- `auraboot_behavior_ingest_publish_failures_total{topic,error}`
- `auraboot_behavior_ingest_consumer_lag{topic,consumer_group}`

Prometheus alert rules live in `behavior-kafka-alerts.yaml`.

## Learning Loop (ACP, PR-49/51)

Two Micrometer counters are exported by `LearningLoopMetrics` and scraped via
the standard `/actuator/prometheus` endpoint:

- `auraboot_shadow_run_outcome_total{tenant,outcome}` — outcome ∈ `executed`,
  `skipped_ineligible`, `skipped_not_found`
- `auraboot_promotion_decision_total{tenant,decision}` — decision ∈ `PROMOTE`,
  `BELOW_THRESHOLD`, `INSUFFICIENT_RUNS`, `NOT_FOUND`

### `grafana-learning-loop.json`

Self-contained Grafana dashboard (schemaVersion 37) with four panels:

1. Shadow runs/min by outcome (timeseries)
2. Promotion decisions/15m by decision (timeseries)
3. % executed vs skipped (stat, 1h window)
4. Drafts promoted in 7d (stat)

Import via **Grafana UI → Dashboards → Import → Paste JSON**. Select the
Prometheus datasource when prompted (the dashboard uses a `DS_PROMETHEUS`
template variable, so no hardcoded UID).

### `learning-loop-alerts.yaml`

Prometheus alert rules (group `auraboot.learning_loop`):

- `ShadowSchedulerStalled` (warning) — no shadow runs in 30m
- `HighNotFoundRate` (warning) — >5% NOT_FOUND promotion decisions
- `PromotionStalled` (info) — 0 PROMOTE but >10 INSUFFICIENT_RUNS in 7d

Deploy:

- **Bare Prometheus:** drop into `/etc/prometheus/rules.d/` and reference from
  `rule_files:` in `prometheus.yml`, then `kill -HUP $(pidof prometheus)`.
- **Kubernetes / prometheus-operator:** wrap the `groups:` list inside a
  `PrometheusRule` CR (`apiVersion: monitoring.coreos.com/v1`) under
  `spec.groups`.

Validate locally before deploying:

```bash
promtool check rules docs/operations/learning-loop-alerts.yaml
```
