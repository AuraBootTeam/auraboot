---
type: system-reference
status: active
created: 2026-08-23
updated: 2026-10-25
---

# Reliable Integration Runtime v1

Core owns one reliable cross-domain event runtime. Business plugins own source facts and consumer
effects; they do not copy the dispatcher, receipt, lease, DLQ, or replay tables.

## Public contract

- Java API: `IntegrationEventEnvelope`, `ReliableIntegrationAccessor`, and
  `ReliableEventConsumerExtension` in `platform-plugin-api`. The `ReliableIntegrationDispatcher` port
  provides bounded dispatch for a trusted current tenant, source, and registered event types.
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
and a 30-second expiry. Completion/failure updates require the exact token and an unexpired lease
using `clock_timestamp()`. Every reliable consumer requires an active, writable independent transaction,
locks its exact current lease before receipt/effect work, and rechecks wall-clock expiry before
marking its receipt applied. Expiry during the callback rolls back its effect and receipt.

Global, tenant-scoped, and internal exact-event claims share the same crash budget. Reclaiming
expired processing increments `retry_count` once; claiming a due pending row does not charge a
second failure. Exhaustion atomically clears ownership, marks the row failed, and writes its durable
DLQ in the same SQL statement. Workers do not invoke consumers for those terminal claim results.
Reconcile uses bounded `FOR UPDATE SKIP LOCKED` recovery, counts each abandoned attempt, and
materializes terminal DLQs in its transaction; it cannot reset the budget by returning a row to pending.
 Retry delay is deterministic bounded exponential backoff from one second
to fifteen minutes. A row that reaches `max_retries` enters `failed` and receives one durable
`ab_integration_dead_letter` row.

Replay is explicit and attributed (`replayed_by`). It resets the original outbox row to `pending`;
it never mutates the envelope or silently retries an open poison event. Reconcile only recovers
expired leases and materializes missing DLQ rows.

## Host-authorized tenant dispatch

`ReliableIntegrationDispatcher.dispatchCurrentTenant(source, eventTypes, limit)` requires a
trusted tenant/user frame, 1–32 registered event types and a batch limit of 1–100. Source/type codes
are canonical, nonblank and bounded to 128 characters. Callers cannot supply a tenant, operator or
lease; host business action authorization must run before invoking this port. It rejects an active
business transaction because claim ownership must commit before independent effects start.

The claim binds the current tenant, exact source and allowed types, excludes unversioned legacy
rows and preserves tenant-local ordering. Its result contains claimed/delivered/failed/lost-lease
counts and terminal public event ids; it exposes no payload or internal numeric ids. Fenced-out
terminal writes are reported as lost leases rather than successful delivery or recorded failures.
Global polling remains the native scheduler's responsibility. Reliable JSON floating-point values
are decoded as `BigDecimal`, including non-resource event sources.

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

## Registered Inventory intake writer authority

The host binds the started PF4J `com.auraboot.inventory` artifact, exact implementation class and registered consumer instance `inventory.public-command-adapter.v1` to the sole purchase event `procurement.purchase-order.receipt-requested.v1` from `procurement`. The body command is a consistency check; it cannot choose a command, plugin, row scope or model authority. Artifact observation retains the existing STARTED/classloader/JAR-drift checks.

For this callback only, the claimed envelope tenant and fixed owner command `inv:receive_source_receipt` authorize create operations on `inv_inbound` and `inv_inbound_line`. Reads are limited to those two models and `inv_warehouse`, `inv_warehouse_location`, `prod_product`, `pe_product`, matching current intake prerequisites. Update, delete, batch claim and cross-owner/foreign-tenant access are rejected. The existing `ALL` row grade is bound to each approved model rather than a null/global target; normal tenant enforcement and exact metadata field-writer guards still run. Other reliable consumers retain their prior behavior and receive no new owner authority.

Model names alone are not authorization. Each scoped access resolves the tenant's current published, non-deleted physical model through `MetaModelMapper`, its effective definition through `MetaModelService`, and its actual plugin owner through `PluginRecordMapper`. Inventory models must belong to `com.auraboot.inventory`; product prerequisite reads retain their actual tenant plugin owner rather than pretending to be Inventory. The effective code/id/version/status/table must agree with the persisted tenant model. The approved intake mappings use the canonical `SystemFieldConstants.generateTableName` (`mt_`) because their manifests do not declare a custom table; nullable persisted table names resolve through that generator. Unknown models, wrong owner/table, virtual sources and foreign/unpublished/non-current definitions fail closed. An immutable per-effect binding captures model id/PID/version/row version/owner/table and is rechecked before and after delegated access, rejecting metadata drift without relaxing the field-writer guard.

Effect and receipt remain in the existing independent transaction. Duplicate receipt claims skip the consumer. The host restores prior identity and command permit state on success/failure; nested unrelated delivery cannot borrow the private owner scope. `InventoryOwnerCommandScopeTest` provides hermetic scope/guard counterexamples, alongside the existing delivery isolation, background accessor, MetaContext and field-writer tests. Assembled PF4J, PostgreSQL delivery and posted receipt lineage require separate native evidence; these tests alone do not prove the Procurement received-quantity consumer or customer ERP contracts.

## Inventory posted-fact enqueue provenance

`inventory.receipt.confirmed.v1` receives host-generated `core.inventory.producer.*` headers and `ab_outbox.command_code=inv:confirm_warehouse_in` only while the host pipeline executes the actual registered Inventory handler instance. The private invocation binds current tenant, permitted `inv_inbound` receipt PID, actual model metadata and started PF4J artifact. Enqueue checks the first observed PF4J caller Class identity, reobserves the artifact and metadata binding, and validates the publisher's deterministic receipt/version UUID and envelope identity. Same-Class unregistered instances, foreign nested callers, missing command scopes and client-supplied reserved headers cannot create this provenance. Other event types receive no new producer authority.

The internal delivery verifier requires the complete persisted envelope, stamp and command to match `ab_outbox`, with the current processing lease still valid. A header alone is not proof; unstamped historical events are not automatically upgraded, and the current JAR cannot attest an older posting artifact. The verifier is prepared for the subsequent fixed Procurement received-quantity consumer; this enqueue slice alone does not implement or validate that consumer. No new business receipt table or public raw-apply endpoint is introduced. Actual PG/PF4J producer invocation, lease/receipt atomicity and physical-table existence remain native admission requirements.

## Validation composition and legacy compatibility

Native PostgreSQL evidence must use an explicitly composed test application. The shared
`TestApplication` excludes unrelated Boot test configurations and other application entry points
from its component scan, and registers its required mapper packages directly. Case-specific
fixtures remain explicit imports. A scheduling mock in an exact-count worker test must not leak
into other applications or be mistaken for evidence that the production scheduler ran.

The unversioned JVM-event path remains a compatibility contract: a committed `OutboxWriter`
event preserves its public event ID, tenant, payload and result through publication; a subsequent
poll does not republish a delivered row. PostgreSQL delivery tests, synthetic PF4J JAR discovery
and business-plugin admission remain separate evidence scopes. Passing one does not attest the
others or every external ERP contract.

## Observability and rollback

Micrometer counter `auraboot_integration_events_total{outcome=...}` exposes enqueue, claim, receipt,
duplicate suppression, retry, DLQ, replay, lease recovery, delivery, and lost-fence outcomes. The
`reliableIntegration` health contributor is DOWN for expired leases, open DLQ rows, or an
undelivered backlog older than five minutes.

Rollback is additive: stop new v1 producers, drain or replay pending rows, then revert consumers.
Do not drop the receipt/DLQ data during rollback; it is the audit and idempotency record.

The registered `procurement.confirmed-receipt-counter.v1` delivery has a separate host-private
`pr:consume_confirmed_inventory_receipt` authority. It requires the persisted Inventory producer
stamp, the complete matching outbox envelope and the current delivery lease before and after
access. The authority does not grant general update: it permits only `pr_purchase_order_line`
CAS on an event-qualified target, comparing its server-read `row_version`, with exactly one
submitted field, `pr_pol_received_qty`. The framework advances the version itself. The current
row must belong to this tenant, be collaboration-managed, and retain the actual Procurement
metadata owner/storage binding. Ordinary update/create/delete, other records, extra patch fields,
non-version CAS, non-increasing quantity and quantity above ordered are rejected. Existing field
and managed-record writer checks still execute. The consumer must independently verify the
original immutable Procurement request and full line lineage before computing the quantity;
this scope does not replace those business checks. Ordinary non-collaboration PR initialization
and its received-quantity default remain unchanged.

`OutboxEventMapper.claimKnownEvent` is an internal exact tenant/event lease claim for controlled
owned-event delivery. It retains the global claim's due-pending/expired-processing conditions,
tenant-local ordering predecessor check and `FOR UPDATE SKIP LOCKED`, with a fixed one-row cap.
Both selection and update bind the two identity parameters. It never changes event content,
producer headers or command authority, and does not replace the global dispatcher. The lease
claim must commit independently before a consumer's `REQUIRES_NEW` effect transaction starts;
holding that same outbox row lock in an outer transaction would self-block the inner delivery.
The annotation/BoundSql contract tests are not PostgreSQL lease or ordering acceptance.

The trusted receipt counter closes the lease-check/receipt-commit race by locking the exact
`tenant_id/event_id/lease_token` processing outbox row with `lease_until > clock_timestamp() FOR UPDATE`
inside its independent T01 effect transaction. It requires an active Spring transaction,
checks the complete persisted envelope both before and after acquiring the row lock, and
holds the lock through effect and receipt commit. Missing or replaced leases reject before
plugin work. Per-access and post-effect envelope/metadata verification remains in place.
All reliable consumers now acquire this lease lock in the common delivery executor; the receipt
counter retains its additional owner/provenance checks. Native two-transaction
verification must prove that a competing exact claim skips the held row, and that a lease replaced
before lock acquisition causes rollback with no plugin effect or applied receipt. The separate
lease-claim transaction must already have committed; an outer held row lock followed by this
`REQUIRES_NEW` delivery is explicitly forbidden.

The fixed Inventory handler/intake consumer and Procurement receipt counter use a host-discovered
instance proof. PF4J's current Spring factory creates fresh objects on repeated enumeration;
re-enumerating and comparing with a registry-cached object is not a registration proof. The
existing AuraPluginManager discovery methods record only returned applicable extensions in a
weak **identity** ledger, bound to the actual implementation Class, current STARTED wrapper,
classloader and observed JAR artifact. Read-only lookup cannot register an object. Arbitrary
same-Class instances (including objects with permissive equals/hashCode) have no proof. Plugin
lifecycle changes invalidate prior discovery; unload/reload and changed physical artifacts fail
closed. Other extension types and the global factory's creation semantics remain unchanged.
Real PF4J factory tests with synthetic local JARs prove this primitive, not full business PG/Auth
or Inventory/Procurement artifact admission.

The receipt counter's five PR reads (`pr_purchase_order`, `pr_purchase_order_line`,
`pr_purchase_receipt`, `pr_purchase_receipt_line`, `pr_inventory_operation_request`) require
the exact `com.auraboot.procurement` owner declared by the canonical Procurement manifest
and models. No prefix grants ownership. Ambiguous, deleted, unpublished or mismatched effective
metadata cannot authorize these reads. Product reads retain their actual legitimate owners;
this change does not require product models to belong to Procurement. Standalone counterexamples
cover wrong PR ownership and invalid current definitions; assembled tenant metadata and PG
permission acceptance still require native verification.

## Transactional command replay retention

`IdempotencyService.claimScopedIdempotency` keeps its existing 24-hour policy.
`claimRetainedScopedIdempotency(key, operation, canonicalPayload, tenantId)` requests a claim
without automatic expiry. Both require an active transaction and a trusted non-null tenant; the
retained entry point rejects blank keys and operations. Its default interface implementation throws
`UnsupportedOperationException`, so older providers cannot silently downgrade to finite retention.

The implementation reuses the tenant/request unique fence, canonical JSON hash, transactional
ownership and stored completion outcome in `ab_idempotency_record`. Retained rows use PostgreSQL
`'infinity'::timestamptz` in the existing `expires_at` column and survive ordinary expiry cleanup.
A retained claimant does not delete or upgrade existing finite claims, including expired rows.
Use a dedicated operation namespace. Rollback leaves no committed claim; a concurrent claimant
waits for the unique fence and observes the original committed outcome. Changed intent conflicts.

A replay key is not authorization. Callers must authorize the current actor and target resource
before returning a cached outcome, bind server-derived actor/resource context into canonical intent,
and keep raw credentials out of keys and stored outcomes. Callers own retention and privacy policy;
the internal expiry marker is not a business deadline. This API does not authorize carts or change
event receipts, subscriber policy or physical inventory.
