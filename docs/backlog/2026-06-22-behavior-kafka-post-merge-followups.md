---
type: backlog
status: active
created: 2026-06-22
area: behavior-telemetry
relates_to:
  - docs/superpowers/specs/2026-06-21-behavior-kafka-decouple-ingestion-design.md
  - docs/operations/behavior-kafka-ingest-runbook.md
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
---

# Behavior Kafka Post-Merge Follow-Ups

## Context

OSS PR #1023 completed the Kafka-decoupled behavior ingest slice:

- collect endpoints enqueue via `BehaviorIngestPublisher`.
- `BehaviorIngestConsumer` persists events asynchronously with idempotency.
- malformed and constraint-violating events route to `aura.behavior.quarantine.v1`.
- `BehaviorQuarantineConsumer` persists `ab_behavior_quarantine`.
- memory, native Kafka, keyed collect, authenticated collect, and browser goldens have post-merge verification evidence.

The items below are not blockers for #1023. They are product and operations enhancements that should be scheduled explicitly instead of being treated as unfinished work from the decouple slice.

## Follow-Up Queue

| ID | Item | Value | Suggested verification |
| --- | --- | --- | --- |
| BK-F1 | Quarantine query API and admin-facing list page for `ab_behavior_quarantine` | Operators can inspect bad events without direct SQL | Controller/service tests + browser golden showing reason/raw event |
| BK-F2 | Quarantine replay/redrive flow with reason filters and idempotency guard | Lets operators recover after producer/schema fixes | IT: replay valid quarantined event writes one behavior row and marks replay result |
| BK-F3 | Metrics and alerts: accepted, enqueued, persisted, quarantined, publish failures, consumer lag | Makes ingest health observable | Micrometer unit tests + Prometheus scrape golden/fixture |
| BK-F4 | Production Kafka topic policy runbook: partitions, retention, DLQ retention, consumer group naming | Reduces deploy-time ambiguity | ops doc review + smoke check against native broker |
| BK-F5 | Schema registry / Avro hardening for behavior events | Aligns with SoT `BACKWARD_TRANSITIVE` direction | compatibility test with old/new payloads |
| BK-F6 | Load and backpressure test for large public batches | Protects `/api/collect/keyed` latency and memory | host-first perf harness with p95 and no dropped good events |
| BK-F7 | Server outcome publisher via transactional outbox | Separate SoT line for durable business outcome events | outbox IT: business state and outcome commit atomically, relay idempotent |
| BK-F8 | Consumer trace propagation / Kafka traceparent round-trip | Completes A-G4 consumer-side trace continuity | native Kafka IT asserting downstream span/link continuity |
| BK-F9 | Quarantine TTL / cleanup policy | Prevents indefinite growth of raw bad payloads | migration/config + retention job test |

## Value Split

High-value near-term:

- BK-F1/BK-F2 if operators need quarantine visibility or recovery.
- BK-F3/BK-F4 before production traffic is routed through Kafka provider.
- BK-F7 when behavior analytics must count backend business outcomes, not only browser/client events.

Lower-risk hardening:

- BK-F5/BK-F6/BK-F8/BK-F9 can follow once traffic shape and operational ownership are clear.
