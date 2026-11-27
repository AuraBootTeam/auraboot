---
type: system-reference
status: active
created: 2026-11-27
updated: 2026-11-27
---

# Migration convention storage scopes

The migration convention checker applies business-record columns (`pid`,
`tenant_id`, `created_at`, `updated_at`) by default. Internal state, receipts,
immutable audit events and global provider facts have different ownership and
identity semantics. Do not add artificial tenant identifiers or update timestamps
to these records to satisfy a generic business-record check.

The isolated backend CI runner first executes the checker contract tests, then
checks migrations with `scripts/migration-conventions-infrastructure.json` and
its unchanged historical baseline. These filesystem checks run before container
pulls or database startup. Running the checker without `--infrastructure-policy`
retains its original default behavior.

## Explicit classification contract

The policy binds each migration's complete SHA-256, exact table names, storage
scope, documented reason, existing Java owner path and exact missing columns.
Only listed missing-column findings are classified; SQL bytes are unchanged.
A changed migration, unexpected table, stale column list or invalid ownership
entry fails closed. New business tables retain all default column checks.
Destructive DDL checks and baseline accounting remain independent and unchanged.

Allowed scopes are `internal-state`, `internal-receipt`, `immutable-audit` and
`global-provider-fact`. Only a global provider fact can classify missing
`tenant_id`. The WeChat provider subscription facts use provider-level
application/OpenID/template identity: duplicating one provider quota per tenant
would misrepresent that quota. Callers still enforce business tenant access;
this storage classification grants no API permission.

Policy edits require reviewing the storage semantics and owner implementation,
updating the exact file identity, and running `scripts/ci/test_migration_conventions.py`.
The tests include business-table and destructive-DDL rejection, changed SQL and
table identity, invalid scope/owner/reason, duplicate columns, stale findings,
and disjointness from the historical baseline. A passing static classification
is separate from backend tests, database migrations and coverage acceptance.
