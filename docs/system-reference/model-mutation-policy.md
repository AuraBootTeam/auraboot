---
type: system-reference
status: active
created: 2026-10-03
updated: 2026-10-25
---

# Model mutation policy

Model policies apply inside the server write boundary, including requests from administrators. They complement command permissions and capability/record scopes; they do not grant authority to a command.

| Declaration | Contract |
| --- | --- |
| `immutable: true` | Reject later updates, state transitions and deletion, including authorized command deletion. The existing exact same-transaction insert-completion exception remains unchanged. |
| `commandOnlyCreate: true` | Reject generic creation without the trusted command permit scope. |
| `extension.commandOnlyDelete: true` | Reject generic single and batch deletion without that scope; permit lifecycle updates subject to field ownership and other policies. |

Deletion protection is declared in the existing model extension map, which plugin import, metadata normalization and Application Release definitions preserve:

```json
{
  "code": "oi_disclosure_package",
  "commandOnlyCreate": true,
  "extension": {
    "commandOnlyDelete": true
  }
}
```

The deletion value must be a JSON boolean. An absent value or `false` retains existing mutable-model deletion behavior. An invalid value fails closed when deletion is attempted. `immutable` takes precedence even when a command permit is present. A request payload cannot supply a permit; the command pipeline creates it after authorization.

`ModelMutationGuard.assertDeleteAllowed` runs before record reads and deletion SQL in `DynamicDataServiceImpl.delete` and `batchDelete`. Command field-map deletion and cascade deletion use the same guard. Soft and hard deletion share the single-record boundary. This policy does not replace entity-specific freeze/revoke rules, content integrity checks, audit retention, or independent download authorization.

The disclosure package and audience grant use command-only deletion while retaining review, freeze and revocation updates. They currently declare no deletion command. The access log remains immutable, so a command permit cannot erase its history.

Hermetic contracts reside in `ModelMutationGuardTest`, `DynamicDataServiceAtomicIncrementGuardTest` and `ModelDefinitionDTOTest`. A passing unit contract is not evidence of deployed plugin metadata or HTTP refusal; the AMOS fresh-runtime probe must verify both delete routes and unchanged retained records against the new Core and Plugins commits.


## Record ownership in shared models (implementation in progress)

`extension.recordCommandWriters` selects records by a stored physical boolean marker. The marker's `true` value requires both the published command permit scope and an exact command code from the operation's list. False/null markers retain ordinary model behavior; an empty list denies that operation for marked records. The policy grants no tenant, role or record permission.

```json
{
  "extension": {
    "recordCommandWriters": {
      "field": "collaboration_managed",
      "commands": {
        "create": ["app:dispatch"],
        "update": ["app:save", "app:return"],
        "delete": []
      }
    }
  }
}
```

The model owner must declare the boolean marker as a stored field, make ownership immutable after creation, restrict its writers, and keep the marker hidden from business forms. A malformed policy, absent/non-boolean marker definition, virtual or mutable marker, or invalid identifier fails closed. New markers cannot be forged through generic create/update. On updates, checks use the persisted row rather than the submitted payload. An unchanged marker may round-trip, but even an exact command cannot transfer ownership after creation. Logical/physical aliases must agree. The final dynamic, FIELD_MAP and side-effect UPDATE predicates require the stored marker to equal any materialized marker value; CAS rejects immutable selector fields. Legacy raw relation/rollup writes cannot materialize the selector. Final UPDATE/DELETE predicates also retain ownership alongside tenant and optimistic-version conditions. A zero-row FIELD_MAP or side-effect mutation on a policy-bearing model fails rather than reporting success.

The current implementation covers dynamic single update/delete, CAS and CAS batch, integer increments, bulk deletion, import/create field guards, FIELD_MAP and side-effect SQL, and legacy scalar relation/rollup/cascade/child replacement target checks. Legacy target checks require a transaction, tenant context and row locks. Structured ownership/junction mapper methods bind tenant conditions and validated identifiers; they do not use the generic SELECT-only query gateway, which intentionally continues to reject FOR UPDATE. Background batch claims fail closed unless the exact update writer is authorized.

Many-to-many synchronization and joint replacement resolve every affected target by numeric ID or PID inside the current tenant, reject missing or ambiguous targets, and lock existing targets before inspecting their ownership. Joint replacement also locks the existing junction rows before deleting them. These checks require an active transaction; an exact writer still cannot authorize a target outside its tenant. Ordinary targets retain their replacement behavior. The targeted hermetic regression includes a mutation that removes the joint replacement guard and makes the marked-target refusal assertion fail.

Declarations are validated before extension/model persistence. After imported model/field bindings compose, the import gate validates physical immutable markers before publication, reading database metadata explicitly rather than the active application release snapshot. `getModelDefinitionFromDb` bypasses runtime-primary release lookup. Legacy single-target checks resolve numeric ID/PID to a stored identity inside the tenant and reject missing/ambiguous results; empty bulk child sets retain their ordinary behavior.

This contract is not yet fully accepted. Paired native Plugins import, production-equivalent interceptor behavior, command/HTTP refusal and consumer migration still require real-runtime verification before enabling the policy on procurement records. Native sourcing markers are being developed in the Plugins repository; the runtime must upgrade Core and Plugins together after these remaining checks. Current unit contracts are `RecordCommandWriterGuardTest` and `RecordCommandWriterSqlBoundaryTest`; they do not prove deployment or complete write-path coverage.


### Parent ownership on shared child models

A child with its own valid `recordCommandWriters` policy and standard `extension.parentModel` / `parentField` also respects the parent's record policy. The parent field must be a physical reference to that exact model using `pid`; numeric or display identities cannot substitute for the persisted reference. A missing metadata resolver or malformed reference fails closed. Models without a child record policy retain their existing behavior.

Create and proposed parent transfers lock the parent by PID in the current tenant and inspect its stored marker inside the write transaction. The parent's exact **update** writer is required to change its aggregate through a child; an authorized child writer alone does not confer parent permission. Ordinary parents remain writable. Soft-delete filters apply only when the referenced model declares that capability.

Final scoped UPDATE/DELETE SQL includes a correlated parent-ownership predicate, protecting an existing managed parent even if the child marker is false. This is applied to dynamic writes, single and batch CAS, increments, bulk delete, FIELD_MAP and side-effect writers. Legacy relation, rollup, cascade and joint replacement checks lock existing child targets and validate their persisted parents before mutation; proposed new parent references are checked separately. Junction target checks also resolve the stored parent.

`RecordCommandWriterParentTest` supplies 10 hermetic policy cases, and `RecordCommandWriterPostgresIT` includes three parent cases using real transactions, temporary tables and the actual mapper/scoped writer: unmarked-child update/delete refusal, managed/foreign parent transfer refusal with ordinary-parent success, and legacy bulk deletion refusal. Together with the existing SQL boundary and PostgreSQL checks, the targeted selection executes 31 cases without skips. Disabling parent resolution makes 12 of 18 selected unit/PostgreSQL cases fail; restoring it restores the 31-case selection. This bounded evidence does not establish complete concurrency coverage or product HTTP/import acceptance; those remain in the procurement product report.


File import checks the materialized physical parent reference before its raw mapper insertion. Direct junction create/remove checks all target records and the source facade validates its stored source record; automatic state transitions use locked legacy target checks. Handler result persistence on a policy-bearing model always uses scoped SQL, with both ownership and marker invariants and zero-row rejection, even if a legacy caller lacks ordinary user scope.

Computed fields normalize their expression context with the same declared field conversion used by FIELD_MAP, so JSON decimal strings yield decimal arithmetic. Expression errors fail the command instead of silently retaining a null amount. Computed persistence locks and checks the actual target and its parent, and requires one affected row. Client-provided computed amounts are overwritten by the evaluated expression.

The extended targeted selection has 79 hermetic cases and ten PostgreSQL cases using explicit enterprise_22 and temporary tables. File import and computed writes have real parent-denial/ordinary-or-exact positive controls. Removing input conversion fails three of four computed unit cases; disabling import, direct junction, state and handler predicates fails seven of 35 selected cases. Restored targeted runs pass. Seven separate Spring metadata-query cases were rerun with explicit enterprise_22/Redis DB 13; they are not procurement business acceptance. An earlier invocation omitted the database override and used default aura_boot, so those seven earlier executions are excluded from owned-runtime proof and no zero-effect claim is made. The paired native runtime initially failed ordinary order amount persistence before this repair; a new source-set and HTTP run are still required.

## Durable-operation aggregate mutation lock

A model may declare extension.recordMutationLock with a physical nullable reference
or string field and exact create/update/delete command lists. Any non-null stored
claim freezes ordinary mutations and deletion. Claim acquisition requires both a
published command permit and an exact allowed command; caller payloads cannot
clear a persisted claim or authorize themselves. This policy complements tenant,
row-scope, field ownership and optimistic-version guards.

The child explicitly declares extension.inheritParentMutationLock=true together
with parentModel and a physical parentField reference whose valueField is pid.
Missing metadata, wrong parent target, nonphysical fields, conflicting aliases,
missing tenant or missing transaction fail closed. Children lock their submitted
and stored parents before mutation; a claimed parent denies child creation,
update, deletion and relation changes even when the child has no record ownership
marker. Ordinary models without either declaration retain existing behavior.

Existing-target guards run before dynamic scoped writes, CAS batches, increments,
bulk deletion, FIELD_MAP, side effects, handler result persistence and legacy
state/relation/rollup/computed mutations. Bulk insert checks each proposed parent.
Checks use the existing structured mapper lock with tenant predicates and FOR UPDATE;
no arbitrary SQL expression is accepted in the declaration. The receipt request
command already locks its named source before reading lines, so the child guard
and source claim can share that lock. The product acceptance report qualifies the deployed declaration separately from
this generic contract and retains complete write-path coverage gaps.

Example root declaration:

    "recordMutationLock": {
      "field": "inventory_request",
      "commands": {"create": [], "update": ["receipt:request"], "delete": []}
    }

Candidate round118 compiles and passes223 targeted tests without skips, including
the explicitly bound enterprise_22/Redis13 Spring suite. Removing the parent-claim
check fails three of nine guard tests; the original implementation is restored.
The broad prior attempt retains an initialization failure against nonexistent
default aura_boot and five stale fixture failures. The fixture now injects the
real empty ThreadContextScopes dependency without removing permission assertions.

The product's round118 subsequently upgraded its original runtime and model
declarations, verified six public/generic mutation denials and two actual PostgreSQL
ordering cases. Those bounded results do not prove every import writer or receipt
lifecycle. Exact runtime identity and failed attempts remain in the product report.

## Confirmed receipt source transition

The private ProcurementReceiptCommandScope may grant one additional CAS on
pr_purchase_receipt, only inside the verified Inventory confirmed-fact consumer
and its active persisted delivery lease. All qualified PO-line targets of that
event must already have successful counter CAS results. The only accepted patch
is pr_rcpt_status=confirmed with the source's server-read row_version. The source
PID and type must match the fact, and tenant, draft state, enqueued integration
state, durable request PID/key/hash, original source version and event identity
must match the persisted Procurement request. Ordinary update/create/delete,
other sources, extra fields, repeated source writes and missing claims are denied.

The source CAS retains normal tenant/version/mutation-lock checks and exact
internal command identity. Model ownership and the delivery lease are rechecked
after the delegate. The consumer throws on a source conflict so the host
transaction rolls back prior counters and cannot mark the receipt applied.
It does not set Inventory state, post stock, change order approval or turn
request delivery into receipt confirmation. Ordinary non-collaboration events
retain their previous no-counter/no-source-write behavior. Deployment and native
transaction qualification are separate from the candidate's hermetic tests.

## Delete side effects and the original target

The command pipeline passes its server-captured before snapshot to post-execution
side effects. A delete with side effects requires that snapshot to match the exact
target identity and tenant. The deleted row's stored fields override caller payload
fields when resolving an aggregate parent; an empty payload still recalculates the
original parent's total, including zero after its last child is removed. Missing or
mismatched snapshots reject the command transaction instead of trusting caller
lineage or silently skipping the aggregate. Non-delete side-effect context remains
unchanged. This is an internal pipeline boundary, not a new HTTP input or write grant.

Sub-table command row actions may opt into expectedVersion, idempotent request keys,
explicit update/delete operation types and projected payloads. The target remains
the selected child. Configured input defaults resolve against that row and its
parent form; user cancellation sends no write. Opted-in missing/invalid versions
fail closed, and option-loading errors remain visible instead of being treated as
cancellation. Legacy actions without these options retain their existing protocol.
