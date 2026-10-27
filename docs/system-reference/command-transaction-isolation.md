---
type: system-reference
status: active
created: 2026-10-23
---

# Command transaction isolation

A server-owned command definition may declare `handlerParams.transactionIsolation: "serializable"`. Absence retains the ordinary executor's transaction semantics. This is an internal command metadata capability, not a request/payload option or a new HTTP endpoint. No other explicit value, including null or an uppercase spelling, is accepted at invocation.

The host advisor resolves the same tenant-scoped effective command sources as the ordinary load phase: the current metadata cache or, when absent there, the published release catalog. It selects SERIALIZABLE with REQUIRED propagation before the executor's existing transactional advice. Metadata lookup failures propagate; an unknown command is left to the ordinary pipeline's existing not-found handling. An opted-in command cannot also select host async handler dispatch, which would move work outside the admitted transaction. The policy does not alter current IAM, target authorization, field writers, plugin/model ownership, idempotency or audit.

An existing transaction must already be explicitly SERIALIZABLE; a weaker or unknown outer isolation is rejected. The advisor does not start an independent transaction to escape the caller's transaction. Invocation failures roll back the admitted transaction and retain their original cause. There is no automatic retry.

The load phase verifies the effective declaration again against the actual Spring transaction state. This rejects metadata that becomes stricter between selection and load, or an opted-in invocation that bypasses the proxy without an adequate existing transaction. Stronger outer transactions can be joined; undeclared commands keep their existing behavior. A missing tenant context is left to ordinary command authentication/authorization, without metadata discovery.

Plugins use this policy for guarded multi-record decisions only when the full operation, including their domain approval history, must share a transaction. The existing platform audit and external dispatch after-commit lifecycles are unchanged; the declaration does not make independent checkpoints or downstream effects atomic. A declaration does not itself authorize writes or prove business correctness. PostgreSQL/handler concurrency and the complete user action require separate real-stack qualification. Test-only Spring configurations must stay outside application component scanning and require an explicit private test profile; mocked executors are never production registrations.

## Concurrent meta-command response

A meta-controller request that fails with the exact Spring `CannotSerializeTransactionException` returns HTTP409 and the existing wire code `40900`. The transaction has rolled back; the response asks the caller to reload current state before making a new decision. It contains no SQL, relation names, database exception text or automatic write retry. The public adapter does not classify by diagnostic message. Other persistence failures, including deadlocks and unavailable connections, retain their existing classification. This response mapping does not change transaction selection, IAM, idempotency or database behavior. Runtime qualification and its limits remain in the product's procurement acceptance report.


## Exact related bookkeeping updates

A synchronous command may additionally declare `handlerParams.relatedUpdateAuthorization: true` and `tenantProjectionRead: true`. Its public target must already have an explicit successful readability decision and a permitting command authorization plan; `targetRecordAction: "update"` forces that boundary for a handler-managed command. Missing authorization, projection or handler fails closed. The adapter is not combined with installed-machine or related-delete adapters, and async declarations are rejected before task dispatch.

The host supplies immutable current root/related records and the intended change map to `CommandHandlerExtension.authorizeRelatedUpdate`. Older handlers deny by default. The original tenant, actor, command and root applicability must still match. A positive handler decision temporarily installs an exact related-model/PID/version target around the native update, preserving model/field writer, aggregate, optimistic-version, transaction and audit enforcement. The original root plan is restored on success or failure. Missing, fractional, negative and stale versions do not reach persistence. No query, raw read, create, delete, role or global model permission is granted by this adapter; tenant projection remains a separate read-only capability. The host API is internal metadata/SPI, not a new HTTP interface or a request-payload opt-in.

## Client command payload isolation

DSL command actions may declare `includeRecordPayload: false`. The shared action hook then sends only explicitly configured `payload` fields and values collected by `inputFields`; the ambient record remains available to expressions, target resolution and optimistic-version checks, but its fields are not copied into the request payload. This also applies after the pre-dialog fresh-record read. Omission or `true` retains legacy record merging; any other supplied value fails before a command request. This client option does not change server input validation, permissions or command CAS semantics.

## Original command target attachments

`CommandContext.fileAccessor().appendAndRetainExistingToTarget(filePid, fieldCode)` appends an
existing owned finalized upload to the original authorized human command's root record. The typed
API accepts only a public file PID and a real published physical multi-file field code. It accepts
neither a target model/PID nor a storage path, external URL, byte copy or child relation. Older hosts
and the ordinary `FileAccessorImpl` constructor fail closed with `UnsupportedOperationException`.

The capability is installed by the host handler phase from its trusted pipeline context. It binds
the tenant, internal user ID, member ID, user PID, exact authorized command, original target, thread,
handler lifetime, Core connection holder, JDBC connection and transaction synchronization. It rejects
impersonation, claimed asynchronous tasks, nested authorized command frames, identity/target changes,
read-only transactions and `REQUIRES_NEW`. Original command provenance is server thread state and
is excluded from identity snapshots; saturated executor `CallerRunsPolicy` does not inherit it.

The caller first performs its parent CAS, complete attachment projection and domain evidence writes
in the same physical transaction. The host locks and rechecks the scoped parent, current command
version and model/record/field mutation invariants before locking a file. Distinct new upload PIDs
must be passed in ascending string order; repeated already-locked PIDs are permitted. Under the
file lock the host verifies current-tenant visibility, the actual internal user as owner, finalized
upload and active state, applies the official monotonic retention fence, and appends one exact
tenant/model/record/field/file relation. Existing relations remain. Exact repeats are idempotent;
field capacity is enforced under the parent lock. The method does not update the business field.

An append failure on the original thread marks the original Core transaction rollback-only, even
if plugin code catches it. Parent projection, evidence, retention and relation cannot partially
commit. Retention and deletion use the existing file-row arbitration: deletion first rejects append;
committed retention rejects subsequent deletion. Physical object deletion remains after-commit.

Legacy relationship replacement, removal and append use the same model/record and actual-file-field
writer/immutability invariants. The guard locks and authorizes the parent before relation writes;
physical column aliases resolve to the actual field policy. Old `linkExisting` checks authorization
before its already-linked short circuit. Generated save-and-link methods authorize before uploading.
An empty replacement array cannot remove a command-owned field's relations without its exact writer.
Ordinary mutable legacy file fields retain replacement semantics. Existing opaque export slots remain
subject to model/record authorization and invariants; they do not override an actual file field's
policy. No new generic relation permission is granted by the target-bound capability.

The existing multipart upload is the staging path: `/api/file/upload` accepts the file without a
business target and persists an owned, finalized, unbound file. `FileAttachmentField` sends only the
`file` multipart member and passes its returned public PID to the command form. The domain command
then consumes the staged upload through the target-bound capability. No alternate upload API or
pre-command relation exemption is needed. Image/PDF semantics remain in the consuming domain.

The existing download authorizer requires read access to every active relation target. Consumers
must avoid unrelated child relations merely to make an attachment appear; the capability itself
links only its original root target. Backend qualification and explicitly unexecuted consumer
layers are recorded in the [file capability verification report](./2026-10-27-command-target-files-testing-gate-acceptance-report.md).


## Original command created-record files

`CommandContext.fileAccessor().linkAndRetainExistingToCreatedRecord(filePid, modelCode, recordPid, fieldCode)` completes an exact single-file association after the original synchronous human command has successfully called its `DataAccessor.create` or `tryCreate`. The create payload and returned persisted row must already contain this same public file PID in the formal physical single-file field. This allows an immutable verification, grant or import-batch record to receive its retained relation without updating the business row. Older and unscoped hosts reject the default method.

The host records successful singleton creation privately; callers cannot register receipts or establish provenance with a lookup. The receipt copies the scalar projection and row identity/version, and binds the original command scope, tenant, user/member, thread, physical Core holder/connection and fresh PostgreSQL transaction identity. Nested commands, other transactions, rollback-only/closed/read-only/autocommit connections, escaped scopes, machine/installed/impersonated and asynchronous contexts are refused. Bulk/batch creation does not produce these receipts.

At association the host requires the current CREATE and field writer, a published physical single-file field, and the exact newly created row under its writer lock. It rechecks tenant, creator, identity/version and scalar projection. An internal exact-row read under host scope is limited to this already-created receipt; the void API grants the caller no general READ or UPDATE capability. Formal field codes are required; string placeholders, multiple/virtual fields and replacement of an occupied different relation are refused.

The host then locks the owned finalized active file, applies official retention and inserts one tenant/model/record/field/file relation. An exact repeat is idempotent. Retention, relation and original create share the physical SQL transaction; a caught association failure marks that transaction rollback-only. Distinct file PIDs follow the existing ascending lock order. The scalar business field and its row version are unchanged by this operation.

A permitted original create command with no target can receive this creation-only capability. Original-parent append still requires its explicit readable root target. Existing rows, nonowner historical evidence, empty-slot generated export workflows and arbitrary model/PID relationships are outside this API. Its CREATE checks reuse the existing model policy, including the integrating host's command-writer semantics; it does not relax immutable models or introduce a generic relation permission. No schema or HTTP endpoint is added.

[Component verification and integration limits](./2026-10-27-command-created-files-testing-gate-acceptance-report.md) records the actual selected tests, falsification, exact source/native identity and unexecuted consumer layers. A consumer must integrate the small delta into its richer host/API and qualify its actual public command and download journey.


## Query result reuse

A published query command can explicitly declare `executionConfig.cacheResult: false`:

```json
{
  "code": "example:current_records",
  "type": "query",
  "executionConfig": { "cacheResult": false }
}
```

`query` is the existing registered command type. The declaration is trusted command metadata, never a request or payload option. It disables both the command idempotency claim/replay lookup and completion's stored result. A supplied `clientRequestId` therefore does not return an older result: the handler and its current result-record READ checks run again. Existing stored outcomes are ignored, not deleted. An absent setting or explicit `true` preserves the existing scoped idempotency contract, including actor/intent conflict checks for writes.

Only a boolean is valid; explicit null, strings and numbers are rejected. `false` requires `type: "query"`, cannot coexist with `requireIdempotencyKey: true`, and cannot declare a non-read `handlerParams.targetRecordAction`. Import validation and both runtime phases enforce the same policy. Plugin DTO consolidation preserves the boolean in the stored execution configuration. Mutation commands must retain their idempotency contract; their authors must not relabel them as queries to disable result reuse.

This is a result-reuse policy, not a database read-only transaction mode. It grants no command capability, result-record READ, target UPDATE, file access or broader scope, and does not disable existing invocation authorization, audit, effects or invariant handling. A handler remains responsible for checking every returned record through the established READ authority; the declaration cannot make an unsafe query handler safe. Public HTTP/browser and consumer-specific wiring require their own qualification.
