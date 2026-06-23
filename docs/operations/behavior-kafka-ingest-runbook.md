---
type: product-doc
status: active
created: 2026-06-22
relates_to:
  - docs/superpowers/specs/2026-06-21-behavior-kafka-decouple-ingestion-design.md
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
---

# Behavior Kafka Ingest Runbook

本文记录行为采集解耦层的本地验证、运行配置和排障入口。该链路已在 OSS PR #1023 合并到 `main`,本地验证默认 **host-first / 零 Docker**。

## Runtime Contract

采集端点只做同步 guard 与上下文解析:

- `/api/collect`: 从登录态解析 `tenant_id` 与 `user_id`。
- `/api/collect/keyed`: 从 `X-Site-Key` 同步解析 tenant;unknown / disabled / missing key 仍同步 403,不会入队。
- 返回 `{accepted:n}` 表示 publish 成功的事件数,不是持久化完成数。

入队后由消费者异步处理:

```text
HTTP collect endpoint
  -> BehaviorCollectService
  -> BehaviorIngestPublisher
  -> aura.behavior.events.v1
  -> BehaviorIngestConsumer
  -> BehaviorEventPersister
  -> ab_behavior_event

malformed / constraint violation
  -> aura.behavior.quarantine.v1
  -> BehaviorQuarantineConsumer
  -> ab_behavior_quarantine
```

## Configuration

默认本地与 golden 使用 memory provider:

```yaml
aura:
  mq:
    type: memory
```

Kafka provider 使用平台 MQ SPI,不在 behavior 代码里直接依赖 `KafkaTemplate`:

```yaml
aura:
  mq:
    type: kafka
    kafka:
      bootstrap-servers: localhost:9092
      consumer-group: aura-group
      dead-letter:
        enabled: true
        topic-suffix: .DLT
        max-attempts: 3
```

topic 名称是冻结契约:

| Topic | Producer | Consumer | Durable sink |
| --- | --- | --- | --- |
| `aura.behavior.events.v1` | `BehaviorIngestPublisher.publish` | `BehaviorIngestConsumer` | `ab_behavior_event` |
| `aura.behavior.quarantine.v1` | `BehaviorEventPersister.publishQuarantine` | `BehaviorQuarantineConsumer` | `ab_behavior_quarantine` |

## Production Kafka Topic Policy

`KafkaMqProvider` intentionally does not create or mutate production topic
configuration. Production rollout must pre-create topics and DLT topics, then
run the native broker smoke below.

| Topic | Partitions | Retention | Notes |
| --- | ---: | --- | --- |
| `aura.behavior.events.v1` | 12 | 7d (`retention.ms=604800000`) | Main async ingest topic; scale partitions with collector throughput. |
| `aura.behavior.quarantine.v1` | 6 | 30d (`retention.ms=2592000000`) | Bad payload recovery surface; keep longer than main ingest. |
| `aura.behavior.events.v1.DLT` | 12 | 14d (`retention.ms=1209600000`) | DLT from consumer handler failures after `maxAttempts=3`. |
| `aura.behavior.quarantine.v1.DLT` | 6 | 14d (`retention.ms=1209600000`) | DLT for quarantine sink write failures. |

Production clusters should use replication factor 3 and
`min.insync.replicas=2` where available. Local native broker smoke may use the
broker default partition count, but production readiness requires the table
above to be applied explicitly.

Consumer group names are code-level contracts:

| Consumer | Group |
| --- | --- |
| `BehaviorIngestConsumer` | `aura-behavior-ingest` |
| `BehaviorQuarantineConsumer` | `aura-behavior-quarantine` |

Example host-first topic creation (adjust `--bootstrap-server`,
`--replication-factor`, and cluster-specific auth flags):

```bash
kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic aura.behavior.events.v1 --partitions 12 --replication-factor 3 \
  --config retention.ms=604800000 --config min.insync.replicas=2

kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic aura.behavior.quarantine.v1 --partitions 6 --replication-factor 3 \
  --config retention.ms=2592000000 --config min.insync.replicas=2

kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic aura.behavior.events.v1.DLT --partitions 12 --replication-factor 3 \
  --config retention.ms=1209600000 --config min.insync.replicas=2

kafka-topics --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic aura.behavior.quarantine.v1.DLT --partitions 6 --replication-factor 3 \
  --config retention.ms=1209600000 --config min.insync.replicas=2
```

## Local Verification

以下命令是 post-merge 主线回归使用的本地路径,不启动 Docker。

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify
./scripts/check-schema-sql.sh --local
```

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :compileJava :compileTestJava --console=plain
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.observability.W3cTraceparentTest' \
  --tests 'com.auraboot.framework.behavior.ingest.*' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorCollectServiceTest' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorQuarantineServiceTest' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorQuarantineReplayIT' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorQuarantineRetentionJobIT' \
  --tests 'com.auraboot.framework.behavior.controller.BehaviorQuarantineControllerTest' \
  --tests 'com.auraboot.framework.behavior.controller.BehaviorCollectControllerIT' \
  --tests 'com.auraboot.framework.behavior.keyed.KeyedCollectIT' \
  --tests 'com.auraboot.framework.behavior.outcome.BehaviorOutcomeOutboxIT' \
  --tests 'com.auraboot.framework.application.database.mybatis.MybatisPlusConfigTest' \
  --console=plain
```

`BehaviorIngestKafkaIT` uses the native broker only when `localhost:9092` is reachable; otherwise it skips cleanly. When Kafka is up, the same targeted command covers native-broker async round-trip, idempotency, malformed quarantine, and constraint quarantine.

Topic policy and native broker smoke:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorKafkaTopicPolicyTest' \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorIngestKafkaIT' \
  --console=plain
```

When `localhost:9092` is available, `BehaviorIngestKafkaIT` also confirms that
the documented topics exist on the native broker and that test consumer groups
preserve the production prefixes (`aura-behavior-ingest-it-*`,
`aura-behavior-quarantine-it-*`).

## Schema Registry / Avro Contract

The behavior ingest topic has canonical Avro schema artifacts under
`platform/src/main/resources/schemas/behavior/`:

| Subject | Current schema | Compatibility |
| --- | --- | --- |
| `aura.behavior.events.v1-value` | `behavior-ingest-envelope-v2.avsc` | `BACKWARD_TRANSITIVE` |

`behavior-ingest-envelope-v1.avsc` is retained as the first compatibility
fixture. `v2` only adds nullable fields with defaults (`traceparent`,
`partitionKeyKind`), so new readers can decode old payloads and old readers can
ignore new additive fields. Future evolution must stay additive or ship an
explicit migration plan and a compatibility test before changing the subject.

Runtime registry wiring remains opt-in through:

```yaml
aura:
  mq:
    type: kafka
    kafka:
      schema-registry:
        url: https://schema-registry.example.internal
```

When the URL is blank, `KafkaSchemaRegistryClient.Noop` keeps the provider in
plain string mode; this is the default for local host-first verification.

Local compatibility gate:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorEventAvroCompatibilityTest' \
  --console=plain
```

## Server Outcome Outbox

Backend business outcomes that must be recorded as behavior telemetry use the
transactional outbox path:

```text
business transaction
  -> BehaviorOutcomePublisher.publish(...)
  -> ab_behavior_outcome_outbox
  -> BehaviorOutcomeRelay.publishPending(...)
  -> aura.behavior.events.v1
  -> BehaviorIngestConsumer
  -> ab_behavior_event
```

`BehaviorOutcomePublisher.publish(...)` is `Propagation.MANDATORY`; callers
must invoke it inside the same transaction as the business state change. The
outbox has a unique `(tenant_id,event_id)` guard, and the relay claims pending
rows before enqueueing so repeated relay calls do not publish the same outcome
twice.

Targeted gate:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.behavior.outcome.BehaviorOutcomeOutboxIT' \
  --console=plain
```

## Traceparent Propagation

When an event carries valid `traceId` and `sourceSpanId`, the publisher adds a
W3C `traceparent` header to the MQ message. The consumer parses the header and
backfills missing event trace fields before persistence; explicit payload
fields win over the header.

Targeted gates:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.observability.W3cTraceparentTest' \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorIngestPublisherTest' \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorIngestConsumerTest' \
  --tests 'com.auraboot.framework.behavior.ingest.BehaviorIngestKafkaIT.kafkaProvider_traceparentHeader_roundTripsIntoPersistedTraceFields' \
  --console=plain
```

Run browser goldens through the host-first stack:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify
./scripts/oss-golden-stack.sh up behavior-kafka-main-verify-69 \
  --slot 69 \
  --ttl 2h \
  --plugin core-site-key \
  --plugin core-dashboard
```

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/web-admin
eval "$(../scripts/oss-golden-stack.sh env behavior-kafka-main-verify-69)"
npx playwright test -c playwright.config.ts --project=chromium --no-deps \
  tests/e2e/behavior/anonymous-keyed-collect.golden.spec.ts \
  tests/e2e/behavior/site-key-registry.golden.spec.ts \
  tests/e2e/behavior/behavior-ingest-quarantine.golden.spec.ts \
  --reporter=line
```

Always destroy only the runtime created for this validation:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify
./scripts/oss-golden-stack.sh destroy behavior-kafka-main-verify-69
```

Validate the browser SDK package separately:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/web-admin
pnpm exec vitest run \
  packages/track/src/__tests__/public.test.ts \
  packages/track/src/__tests__/tracker.test.ts \
  packages/track/src/__tests__/envelope.test.ts \
  --reporter=verbose
pnpm --filter @auraboot/track build
node packages/track/scripts/verify-global-build.mjs
pnpm test:env-lint
```

## Load And Backpressure Harness

Use the host-first load harness against an already-running isolated runtime. The
script seeds a synthetic active site key in the runtime database, posts large
`/api/collect/keyed` batches, waits for asynchronous persistence, and fails on
any request error, accepted-count mismatch, dropped good event, or p95 budget
breach.

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify
eval "$(./scripts/oss-golden-stack.sh env behavior-quarantine-followups-77)"
node scripts/behavior-keyed-load-test.mjs \
  --batches 10 \
  --batch-size 50 \
  --concurrency 4 \
  --p95-budget-ms 5000
```

Expected summary shape:

```json
{
  "expectedEvents": 500,
  "accepted": 500,
  "persisted": 500,
  "droppedGoodEvents": 0,
  "failedRequests": 0,
  "p95Ms": 324,
  "p95BudgetMs": 5000
}
```

## Quarantine Retention

Kafka topic retention only controls broker storage. The durable quarantine table
also has a host-side cleanup policy so raw bad payloads do not grow forever:

```yaml
behavior:
  quarantine:
    retention:
      enabled: true
      days: 30
      batch-size: 1000
      initial-delay-ms: 300000
      fixed-delay-ms: 3600000
```

`BehaviorQuarantineRetentionJob.cleanupExpired()` deletes rows where
`quarantined_at` is older than the configured retention window, bounded by
`batch-size`. Migration `V20260622003000__behavior_quarantine_retention_index.sql`
adds `(quarantined_at,id)` for that cleanup path.

Targeted gate:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.behavior.service.BehaviorQuarantineRetentionJobIT' \
  --console=plain
```

## Metrics And Alerts

All behavior ingest metrics are exported through the standard
`/actuator/prometheus` endpoint:

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `auraboot_behavior_ingest_accepted_total` | counter | `path` (`authenticated`/`keyed`) | Events accepted by collect APIs after synchronous guard checks. |
| `auraboot_behavior_ingest_enqueued_total` | counter | `topic` | Events/messages successfully published to the MQ provider. |
| `auraboot_behavior_ingest_persisted_total` | counter | `outcome` (`inserted`/`duplicate`) | Events durably inserted, or already present through idempotency. |
| `auraboot_behavior_ingest_quarantined_total` | counter | `reason` | Events durably written to `ab_behavior_quarantine`. |
| `auraboot_behavior_ingest_publish_failures_total` | counter | `topic`, `error` | MQ publish failures before enqueue completes. |
| `auraboot_behavior_ingest_consumer_lag` | gauge | `topic`, `consumer_group` | Consumer lag reported by the active provider; memory/local paths keep it at 0. |

Deploy the behavior ingest alerts from
`docs/operations/behavior-kafka-alerts.yaml`:

```bash
promtool check rules docs/operations/behavior-kafka-alerts.yaml
```

The default rules cover publish failures, enqueued-without-persisting,
quarantine-rate spikes, and high consumer lag for group
`aura-behavior-ingest`.

## Operational Checks

Use these SQL checks when diagnosing a behavior ingest issue:

```sql
-- accepted event persisted
SELECT tenant_id, event_id, event_name, user_id, anon_id, occurred_at
FROM ab_behavior_event
WHERE tenant_id = :tenant_id
ORDER BY occurred_at DESC
LIMIT 20;

-- bad events moved to quarantine sink
SELECT tenant_id, reason, event_id, event_name, anon_id, quarantined_at
FROM ab_behavior_quarantine
WHERE tenant_id = :tenant_id
ORDER BY quarantined_at DESC
LIMIT 20;

-- idempotency check
SELECT tenant_id, event_id, count(*)
FROM ab_behavior_event
GROUP BY tenant_id, event_id
HAVING count(*) > 1;
```

Expected behavior:

- duplicate `(tenant_id,event_id)` events are accepted but produce one durable row.
- missing `eventId` or `eventName` writes no behavior row and creates one quarantine row with `reason=malformed_missing_event_id` or `reason=malformed_missing_event_name`.
- field length / database constraint violations write no behavior row and create one quarantine row with `reason=constraint_violation`.
- keyed unknown / disabled / missing site key is rejected before publish and writes neither table.

## Known Boundaries

- Replay / redrive from `ab_behavior_quarantine` is available through
  `/api/analytics/behavior/quarantine/{id}/replay` and
  `/api/analytics/behavior/quarantine/replay`; replay is tenant-scoped and
  guarded by `(tenant_id,event_id)` idempotency.
- Server outcome publishing is explicit: backend code must call
  `BehaviorOutcomePublisher.publish(...)` inside the business transaction to
  write `ab_behavior_outcome_outbox`.
- Local golden validation uses memory provider unless the Kafka IT is explicitly run with a native broker at `localhost:9092`.
