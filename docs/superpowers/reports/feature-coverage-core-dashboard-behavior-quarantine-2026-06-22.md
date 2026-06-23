---
type: artifact
status: closed
created: 2026-06-22
area: behavior-telemetry
relates_to:
  - docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md
  - docs/operations/behavior-kafka-ingest-runbook.md
---

<!-- no-precipitation: This is a feature coverage artifact for a closed slice; durable operator guidance lives in docs/operations/behavior-kafka-ingest-runbook.md. -->

# Behavior Kafka Follow-Ups Feature Coverage

## Scope

Target: BK-F1 through BK-F9 from
`docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md`.

Browser surface: `plugins/core-dashboard/config/pages.json`
`behavior_quarantine_list` and menu `behavior_quarantine`.

Golden evidence:

- Spec: `web-admin/tests/e2e/behavior/behavior-quarantine-admin.golden.spec.ts`
- Run: `20 passed, 1 skipped` against host-first slot 77
- Screenshot: `web-admin/test-results/bqa-01-quarantine-list.png`

## Feature Inventory

| Feature | Source | Required Evidence |
| --- | --- | --- |
| Quarantine menu entry | `plugins/core-dashboard/config/menus.json` | Sidebar navigation reaches `/p/c/behavior_quarantine_list`. |
| Quarantine list datasource | `pages.json` API datasource | Page loads rows from `/api/analytics/behavior/quarantine`. |
| Filter field `reason` | `behavior_quarantine_filters` | UI filter changes visible table rows. |
| Filter field `replayStatus` | `behavior_quarantine_filters` | UI filter changes visible table rows after replay. |
| Table columns | `behavior_quarantine_table` | Reason, event ID, detail, raw event, replay status render real values. |
| Row action `replay` | `rowActions.replay` | UI click posts replay API, writes one behavior row, updates replay status. |
| Replay idempotency | `BehaviorQuarantineService` | Replaying same quarantined event does not duplicate `(tenant_id,event_id)`. |
| Metrics and alerts | `BehaviorIngestMetrics`, alert YAML | Micrometer counters/gauge and Prometheus rules parse. |
| Topic policy | runbook + Kafka tests | Topic/group constants and native broker smoke. |
| Avro compatibility | schema fixtures | v1/v2 compatibility tests. |
| Load/backpressure | load harness | Host-first p95 and no dropped good events. |
| Outcome outbox | `BehaviorOutcomePublisher`, `BehaviorOutcomeRelay` | Atomic commit and idempotent relay IT. |
| Traceparent propagation | `W3cTraceparent`, publisher/consumer | Header generation and native Kafka persisted trace fields. |
| Quarantine retention | retention job/config/migration | Config binding and real DB cleanup IT. |

## Coverage Matrix

| Function | Type | Status | Browser Evidence | Backend Evidence | Test File | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| menu:behavior_quarantine | menu | Deep | Sidebar link click opens page; no direct `/p/` goto in target spec | DSL canonicalization includes page config | `behavior-quarantine-admin.golden.spec.ts`; `canonicalizePageDsl.test.ts` | Covers actual operator entry. |
| page:behavior_quarantine_list datasource | list/API | Deep | Seeded target and decoy rows render in table | `BehaviorQuarantineControllerTest` validates tenant-scoped list params and pagination | same | API datasource path is not mocked. |
| filter:reason | filter | Deep | Reason filter hides decoy row with `malformed_missing_event_name` | Controller/service reason filter test | same | UI uses `filters-toggle`, `field-reason`, `filter-search`. |
| filter:replayStatus | filter | Deep | After replay, `replayStatus=replayed` filter shows target and hides decoy | Controller/service replayStatus filter test | same | Covers the second DSL filter field. |
| table:reason/event/detail/rawEvent | table columns | Deep | Row asserts reason, event ID, detail, raw event route template | Service list returns real `BehaviorQuarantine` rows | same | Screenshot captured before replay. |
| rowAction:replay | action | Deep | Click row replay action; status changes to `replayed`; page reload shows replayed status | `BehaviorQuarantineReplayIT`; `BehaviorQuarantineServiceTest` | same | Uses DB poll as backend assertion after UI click. |
| replay:idempotency | backend action | Deep | Browser asserts one behavior row after UI replay | `BehaviorQuarantineReplayIT` calls replay twice and asserts one durable row | `BehaviorQuarantineReplayIT` | No duplicate row on repeated replay. |
| api:replayPending(reason,limit) | backend action | Deep | Not exposed as a visible page button | `BehaviorQuarantineControllerTest` and `BehaviorQuarantineServiceTest` cover reason and limit | controller/service tests | Operator batch endpoint is API-level. |
| metrics:accepted/enqueued/persisted/quarantined/failures/lag | observability | Deep | N/A | `BehaviorIngestMetricsTest`; `docs/operations/behavior-kafka-alerts.yaml` YAML parse | metrics tests | Non-UI operations feature. |
| kafka:topic policy and native smoke | operations | Deep | N/A | `BehaviorKafkaTopicPolicyTest`; `BehaviorIngestKafkaIT` | Kafka tests | Native broker, no Docker. |
| schema:Avro v1/v2 | compatibility | Deep | N/A | `BehaviorEventAvroCompatibilityTest` | Avro test | Validates backward compatibility. |
| load:public keyed batches | performance | Deep | N/A | `scripts/behavior-keyed-load-test.mjs` slot 77 run: 500 accepted, 500 persisted, p95 324ms | load harness | No dropped good events, no failed requests. |
| outcome:transactional outbox | backend action | Deep | N/A | `BehaviorOutcomeOutboxIT` rollback/commit and relay-once assertions | outbox IT | Publisher requires active transaction. |
| trace:Kafka traceparent | observability | Deep | N/A | `W3cTraceparentTest`; publisher/consumer tests; native Kafka trace round-trip IT | trace tests | Payload trace fields win over header backfill. |
| retention:quarantine TTL cleanup | operations | Deep | N/A | `BehaviorQuarantineRetentionJobIT`; migration index | retention IT | Config `behavior.quarantine.retention.*`. |

## E2E Truth Audit

Target spec: `web-admin/tests/e2e/behavior/behavior-quarantine-admin.golden.spec.ts`.

| Dimension | Score | Evidence |
| --- | ---: | --- |
| Path authenticity | 2/2 | `click_or_fill=10`, `page_request=0`; setup uses psql/curl only for seed/auth lookup and DB assertions. |
| Assertion strength | 2/2 | No threshold/baseline assertions; hard DB polls assert replay status and exactly one behavior row. |
| Skip/Fixme health | 2/2 | Target spec has 0 `test.skip` / `test.fixme`. The full run's 1 skip is from shared setup gating, not this spec. |
| Redline audit | 2/2 | Direct `/p/` goto: 0; `waitForTimeout`: 0; literal timeout > 5000: 0. |
| Threshold/retry tightening | 2/2 | No `toBeLessThanOrEqual`, `toBeGreaterThanOrEqual`, or `retries:` in target spec. |

Truth result: 10/10 for the target quarantine admin golden. No feature/action rows remain `draft`, `unknown`, missing, or semantic-shallow for the BK-F1/BK-F2 browser surface. BK-F3 through BK-F9 are non-UI operations/backend features and have targeted backend or host-first evidence above.
