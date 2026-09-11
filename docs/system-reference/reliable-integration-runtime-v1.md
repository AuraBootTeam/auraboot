---
type: system-reference
status: active
created: 2026-08-23
updated: 2026-09-04
---

# Reliable Integration Runtime v1

Core owns one reliable cross-domain event runtime. Business plugins own source facts and consumer
effects; they do not copy the dispatcher, receipt, lease, DLQ, or replay tables.

## Public contract

- Java API: `IntegrationEventEnvelope`, `ReliableIntegrationAccessor`, and
  `ReliableEventConsumerExtension` in `platform-plugin-api`.
- Envelope version: `1.0`.
- Delivery: at-least-once, with a unique receipt fence on
  `(tenant_id, event_id, consumer_code)`.
- Ordering: events sharing `tenantId + orderingKey` are eligible by ascending `sequence`.
- Source atomicity: `enqueue` fails without an active transaction and writes `ab_outbox` in the
  caller's business transaction.
- Payload snapshot: legal JSON null is preserved. Nested JSON objects and arrays are recursively
  copied into unmodifiable collections, so later caller mutation cannot change the envelope.
- Consumer atomicity: one consumer effect and its `applied` receipt commit in one independent
  transaction. A thrown failure rolls both back.
- Compatibility: legacy rows with `schema_version IS NULL` retain the former JVM-event path.
  New cross-domain contracts must use a stable `.v1` event type and must not use JVM class names.

## Lease, retry, and terminal failure

The dispatcher atomically claims eligible rows with `FOR UPDATE SKIP LOCKED`, a random lease token,
and a 30-second expiry. All completion/failure updates are fenced by that token. Expired leases are
recovered by reconcile. Retry delay is deterministic bounded exponential backoff from one second
to fifteen minutes. A row that reaches `max_retries` enters `failed` and receives one durable
`ab_integration_dead_letter` row.

Replay is explicit and attributed (`replayed_by`). It resets the original outbox row to `pending`;
it never mutates the envelope or silently retries an open poison event. Reconcile only recovers
expired leases and materializes missing DLQ rows.

## Tenant-admin DLQ operator API

Core exposes the following authenticated tenant-administrator endpoints behind the existing
`/api/admin/**` guard and admin action log:

- `GET /api/admin/reliable-integration/dead-letters` lists tenant-scoped failures and accepts
  `status`, `eventType`, `correlationId`, `pageNum`, and `pageSize` filters. Page size is bounded to
  100.
- `GET /api/admin/reliable-integration/dead-letters/{eventId}` returns the failure, consumer
  receipts, and append-only replay history for one public event id.
- `POST /api/admin/reliable-integration/dead-letters/{eventId}/replay` requires `reason` and
  `expectedReplayCount`. The expected count is an optimistic concurrency fence. An exact retry with
  the same actor, reason, and expected count returns the original replay result without appending a
  second history row; a changed reason, different actor, or otherwise stale request returns conflict
  instead of issuing another delivery attempt.

The public contracts never expose internal numeric ids, payload, or headers. Replay accepts no
replacement payload, so operators can only retry the immutable original envelope. Every successful
replay appends one `ab_integration_dead_letter_replay` row with a ULID record id, tenant, public
event id, correlation id, monotonic attempt, actor PID, mandatory reason, and request timestamp.
Cross-tenant and missing event ids have the same not-found response.

## Procurement to Inventory pilot

The first contract is `procurement.purchase-order.receipt-requested.v1`. Procurement remains the
source owner; Inventory must consume it through its public receipt command and records the runtime
receipt only in the same transaction as the Inventory effect. The reproducible fixture is
[`procurement-inventory-receipt-request.json`](./fixtures/integration-runtime/v1/procurement-inventory-receipt-request.json).

T02 owns the real Procurement producer and Inventory command adapter. T05 consumes the same runtime
for Quality Hold contracts after T04 freezes those event types. Until those PR heads are composed,
the Core pilot proves the runtime contract and PostgreSQL lifecycle, not the assembled business
journey.

## Observability and rollback

Micrometer counter `auraboot_integration_events_total{outcome=...}` exposes enqueue, claim, receipt,
duplicate suppression, retry, DLQ, replay, lease recovery, delivery, and lost-fence outcomes. The
`reliableIntegration` health contributor is DOWN for expired leases, open DLQ rows, or an
undelivered backlog older than five minutes.

Rollback is additive: stop new v1 producers, drain or replay pending rows, then revert consumers.
Do not drop the receipt/DLQ data during rollback; it is the audit and idempotency record.
