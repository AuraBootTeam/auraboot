---
type: system-reference
status: active
---

# Observability correctness testing-gate acceptance report

Date: 2026-07-27

## Scope

This report covers the observability recovery change set:

- controller authorization baseline reason preservation;
- SQL-count response streaming;
- request/async trace correlation;
- system-management authorization for operational reads;
- deployment-configured GenAI pricing, explicit `unpriced` provenance, and ledger failure metrics;
- permission-denial and admin-request trace correlation;
- Flyway migrations and the generated OSS schema snapshot.

The hand-written React operational pages were intentionally not changed. The
backend response contract is extended, while UI rendering remains a separately
authorized React exemption task.

## Layer matrix

| Layer | Evidence | Result |
| --- | --- | --- |
| Hermetic unit | pricing prefix/config/cache cases; ledger provenance/failure metrics; TraceCorrelation live-span and async snapshot fallback | Pass |
| Servlet/filter | response is not wrapped in a buffering wrapper; body and SSE flush reach the real response before chain completion | Pass |
| Authorization contract | operational endpoints use `system_management`; IM unread summary declares authenticated self-scope | Pass |
| Service composition | correlation view assembles command, cost, behavior, admin event, permission denial, and admin request domains | Pass |
| Real PostgreSQL | permission and admin async writers persist trace ids on `auraboot_97` | Pass |
| Flyway/schema | 46 OSS migrations applied through `V20260727110100`; generated snapshot matches fresh migration output | Pass |
| Browser E2E | No frontend code changed | Not applicable |

## Commands and results

- `../gradlew testClasses --no-daemon` — pass.
- Targeted `:test` suite for pricing, ledger, SQL streaming, trace propagation,
  authorization, and correlation — pass.
- `AdminAuditServiceIntegrationTest` and
  `PermissionAuditTraceIntegrationTest` against isolated runtime database
  `auraboot_97` — pass.
- `node scripts/check-controller-authz.mjs --json` — no added drift.
- `node scripts/validate-permission-codes.mjs` — no new drift.
- `scripts/db/check-schema-drift.sh --edition oss` — pass.
- Repository search for `gpt-4o-mini` outside generated/build dependencies — no matches.

## Acceptance

The covered backend and migration scope is accepted for review. The servlet
test proves immediate write/flush behavior without a response-body buffer; no
claim is made here about a browser-visible UI change.
