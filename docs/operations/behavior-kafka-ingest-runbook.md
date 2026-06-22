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
  --tests 'com.auraboot.framework.behavior.ingest.*' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorCollectServiceTest' \
  --tests 'com.auraboot.framework.behavior.controller.BehaviorCollectControllerIT' \
  --tests 'com.auraboot.framework.behavior.keyed.KeyedCollectIT' \
  --tests 'com.auraboot.framework.application.database.mybatis.MybatisPlusConfigTest' \
  --console=plain
```

`BehaviorIngestKafkaIT` uses the native broker only when `localhost:9092` is reachable; otherwise it skips cleanly. When Kafka is up, the same targeted command covers native-broker async round-trip, idempotency, malformed quarantine, and constraint quarantine.

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

- Replay / redrive from `ab_behavior_quarantine` is not implemented yet; see `docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md`.
- Server outcome outbox is a separate telemetry line; behavior Kafka ingest does not make business outcome events durable by itself.
- Local golden validation uses memory provider unless the Kafka IT is explicitly run with a native broker at `localhost:9092`.
