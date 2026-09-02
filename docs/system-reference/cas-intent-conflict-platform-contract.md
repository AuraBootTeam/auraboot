---
type: system-reference
status: active
created: 2026-08-31
updated: 2026-08-31
---

# CAS and request-intent conflict platform contract

## Decision

Conflict handling is a platform contract, not a per-form or per-business-module feature. Every
mutating command uses the same optimistic-concurrency and request-intent rules. Business modules
add domain invariants, but they do not reinvent version checks, conflict codes, or UI behavior.

## Request contract

A mutating command carries:

- `clientRequestId`: caller-scoped idempotency key.
- `targetRecordPid`: aggregate or record target.
- `expectedVersion`: row/aggregate version observed before the edit.
- `payload`: stable business intent.

The platform computes the request intent from `tenantId`, actor, command code, target, operation
type, `expectedVersion`, dry-run flag, and normalized payload. `auditContext` is excluded because
it is telemetry, not business intent.

## Conflict contract

| Code | Meaning | Customer meaning |
| --- | --- | --- |
| `CAS_VERSION_REQUIRED` | A strict existing-target mutation omitted `expectedVersion`. | The platform cannot prove the record is unchanged. |
| `CAS_VERSION_CONFLICT` | The target version differs from `expectedVersion`. | Someone updated the record after this form loaded. |
| `REQUEST_INTENT_CONFLICT` | Same `clientRequestId`, but a different request intent. | The retried request is not the same operation. |
| `IDEMPOTENT_REPLAY` | Same request ID and same intent. | Return the original successful result; do not create another effect. |

Conflict responses use HTTP `409` and a stable machine-readable code in the response context.
The context includes `modelCode`, `recordPid`, `expectedVersion`, and `currentVersion` where
available. Responses do not expose another tenant's data.

## Enforcement

1. The idempotency claim happens before mutations. A durable claim with the same key and intent
   may replay. A different intent fails closed.
2. Targeted commands declare whether they mutate an existing record or aggregate. For strict
   commands, an omitted `expectedVersion` is `CAS_VERSION_REQUIRED`, not a silent legacy write.
3. Target verification uses tenant-scoped optimistic concurrency and a transactional row lock.
   A version mismatch fails before plugin handlers mutate state.
4. Aggregate commands verify the aggregate root, not only the child row. A child mutation that
   changes business state bumps the aggregate version or records a derived aggregate version.
5. Handlers must run in the command transaction so a CAS failure, state failure, or downstream
   validation failure rolls back all related effects.

## UI behavior

The DSL action/form layer maps these codes centrally:

- `CAS_VERSION_CONFLICT`: open the standard "record changed" conflict panel; do not silently retry
  or overwrite.
- `REQUEST_INTENT_CONFLICT`: tell the caller the retry does not match the original request.
- `CAS_VERSION_REQUIRED`: treat as a development/integration defect; production UI always supplies
  a version for strict commands.

Customer copy must not expose `PID`, `row_version`, `expectedVersion`, or CAS terminology. Use
business wording such as "This record was updated by someone else. Refresh and review before
saving."

## Test obligations

The platform test matrix must prove:

1. A stale expected version returns `CAS_VERSION_CONFLICT` and mutates nothing.
2. A missing required version returns `CAS_VERSION_REQUIRED` and mutates nothing.
3. The same request ID with the same intent is an idempotent replay.
4. The same request ID with a different intent returns `REQUEST_INTENT_CONFLICT`.
5. A concurrent aggregate mutation is visible to the next attempt as a conflict.
6. The UI maps the platform code to a visible business-friendly conflict state.

## Migration

Legacy update paths may temporarily omit `expectedVersion`; those paths remain in compatibility
mode and must log the omission. New or changed strict commands must set the concurrency policy.
After owners inventory existing commands, the default flips to fail-closed for existing-target
mutations. Silent legacy updates are not allowed after enforcement.
