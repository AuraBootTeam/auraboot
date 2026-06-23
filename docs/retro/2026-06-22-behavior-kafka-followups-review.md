---
type: retro
status: closed
created: 2026-06-22
area: behavior-telemetry
relates_to:
  - docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md
  - docs/superpowers/reports/feature-coverage-core-dashboard-behavior-quarantine-2026-06-22.md
  - docs/operations/behavior-kafka-ingest-runbook.md
---

<!-- no-precipitation: This review contains no new durable agent rule; the reusable operator procedure is documented in docs/operations/behavior-kafka-ingest-runbook.md. -->

# Behavior Kafka Follow-Ups Review

## Scope Closed

This review closes BK-F1 through BK-F9 for the behavior Kafka follow-up queue:

- Quarantine query/admin page and replay/redrive.
- Metrics, alerts, topic policy, Avro compatibility, and load harness.
- Transactional server outcome outbox.
- Kafka `traceparent` propagation.
- Quarantine retention cleanup.

All validation was host-first on local services; Docker was not used.

## Product And UX Evidence

- Browser golden:
  `web-admin/tests/e2e/behavior/behavior-quarantine-admin.golden.spec.ts`
- Screenshot:
  `web-admin/test-results/bqa-01-quarantine-list.png`
- Coverage report:
  `docs/superpowers/reports/feature-coverage-core-dashboard-behavior-quarantine-2026-06-22.md`

The golden enters through the sidebar, renders the quarantine table with real
reason/detail/raw payload data, exercises `reason` and `replayStatus` filters,
clicks the row replay action, then verifies the durable `ab_behavior_event`
write and replayed status.

## Verification Evidence

Backend compile:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :compileJava :compileTestJava --console=plain
```

Backend targeted regression:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/platform
./gradlew --no-daemon :test \
  --tests com.auraboot.framework.observability.W3cTraceparentTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorIngestMetricsTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorIngestPublisherTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorEventPersisterTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorIngestConsumerTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorQuarantineConsumerTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorKafkaTopicPolicyTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorEventAvroCompatibilityTest \
  --tests com.auraboot.framework.behavior.ingest.BehaviorIngestKafkaIT \
  --tests com.auraboot.framework.behavior.service.BehaviorCollectServiceTest \
  --tests com.auraboot.framework.behavior.service.BehaviorQuarantineServiceTest \
  --tests com.auraboot.framework.behavior.service.BehaviorQuarantineReplayIT \
  --tests com.auraboot.framework.behavior.service.BehaviorQuarantineRetentionJobIT \
  --tests com.auraboot.framework.behavior.controller.BehaviorQuarantineControllerTest \
  --tests com.auraboot.framework.behavior.controller.BehaviorCollectControllerIT \
  --tests com.auraboot.framework.behavior.keyed.KeyedCollectIT \
  --tests com.auraboot.framework.behavior.outcome.BehaviorOutcomeOutboxIT \
  --tests com.auraboot.framework.application.database.mybatis.MybatisPlusConfigTest \
  --console=plain
```

Result: BUILD SUCCESSFUL; native Kafka tests ran against `localhost:9092`.

Frontend DSL unit:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/web-admin
pnpm exec vitest run app/framework/meta/utils/__tests__/canonicalizePageDsl.test.ts
```

Result: 19 passed.

Browser golden:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify/web-admin
eval "$(../scripts/oss-golden-stack.sh env behavior-quarantine-followups-77)"
PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts \
  --project=chromium \
  tests/e2e/behavior/behavior-quarantine-admin.golden.spec.ts \
  --reporter=line
```

Result: 20 passed, 1 shared setup skip.

Load/backpressure:

```bash
cd /Users/ghj/work/auraboot-behavior-kafka-main-verify
eval "$(./scripts/oss-golden-stack.sh env behavior-quarantine-followups-77)"
node scripts/behavior-keyed-load-test.mjs \
  --batches 10 \
  --batch-size 50 \
  --concurrency 4 \
  --p95-budget-ms 5000
```

Result: 500 accepted, 500 persisted, 0 dropped, 0 failed, p95 324ms.

Schema/docs gates:

```bash
./scripts/check-schema-sql.sh --local
bash scripts/check-docs.sh --strict
node scripts/check-docs-governance.mjs
ruby -e "require 'yaml'; YAML.load_file('docs/operations/behavior-kafka-alerts.yaml'); puts 'yaml ok'"
git diff --check
```

Result: all passed.

## Remaining Boundaries

- `BehaviorOutcomeRelay.publishPending(...)` is a Spring component with explicit
  relay semantics; backend product code must call
  `BehaviorOutcomePublisher.publish(...)` inside business transactions that need
  durable server-side outcome telemetry.
- The admin page exposes row replay. The reason-filtered batch replay endpoint is
  covered at controller/service level and documented for operator/API use.
- Kafka IT noise can appear when a long-lived local native broker still has old
  bad messages on `earliest`; targeted assertions passed in the verified run.

## Reflection

The main correction in this close-out was treating the DSL filters as real
operator features, not secondary UI. The first golden proved list/replay but left
filter interaction shallow; the final golden now seeds a decoy row and verifies
both `reason` and `replayStatus` filters from the browser path. The e2e-truth
audit also forced the spec away from direct `/p/` navigation and long literal
timeouts, which keeps this page aligned with the current E2E rules instead of
only adding pass count.
