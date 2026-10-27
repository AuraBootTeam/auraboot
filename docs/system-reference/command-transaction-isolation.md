---
type: system-reference
status: active
created: 2026-10-23
updated: 2026-10-27
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

## Explicit record action scopes

For `handlerParams.targetRecordAction: "update"` or `"delete"`, the pre-idempotency target gate checks readability and the declared action's data scope. An unrestricted read scope does not remove an update/delete restriction. An unavailable scope decision denies. A direct share fallback must grant that exact action; a previously authorized `record-share:...:read` verdict cannot satisfy an explicit update target, and an update verdict cannot satisfy delete. The command's function authorization remains separate: this scope gate does not require or grant the unrelated generic model update operation. Explicit handler-owned target authorizers retain their own domain authorization contract.

An authorized synchronous human command can use the paired host/API `RecordActionScopeAccessor.permits(modelCode, recordPid, action)`. `CommandInvocationAuthorizer.InvocationContext.recordScopes()` is available only inside the pre-idempotency invocation-authorizer callback; `CommandContext.recordActionScopes()` is available only in the original writable, synchronized physical Core transaction. An absent or untyped bridge denies by consumer policy. The existing eight-argument invocation-context constructor remains available with a null capability. Older hosts still require a paired API/runtime upgrade before a consumer calls the new method.

Trusted handler code selects the model and one exact `read`, `update`, or `delete` action; a client-supplied public PID is only an assertion. The host reads the exact current-tenant record internally, checks PID/tenant/deletion and readability, then the requested action's scope and exact-action share. It returns only a boolean. Numeric identity aliases, hidden or unavailable records, mismatched actors, and expired capability lifetimes do not create authority; storage/policy failures propagate. Each evaluation uses a fresh read-only REQUIRES_NEW transaction so committed scope/share changes are not hidden by the original command's read snapshot. No business writes or independent business commit are performed by this check.

The callback bridge is thread-bound and expires when the callback returns or throws. The handler bridge is bound to the original physical Core connection and transaction synchronization; a later or independent transaction cannot reuse it, while returning from a separately authorized nested transaction restores the original lifetime. Installed integrations and async execution do not receive this human capability. Never cache the bridge or reuse an earlier boolean as a later decision.

Create commands have no root target: consumers must opt into invocation authorization and check every client-selected parent/reference object before idempotency lookup, then recheck relevant objects in their actual handler transaction before effects. A tenant projection or an existence lookup is not this authorization. The scalar check does not lock role/scope/share policy, prove membership or vessel binding, authorize a resource service call, or replace domain locks, versions, safety rules and command permissions. Current component evidence and pending product consumption are recorded in the [action-scope acceptance report](../retro/2026-10/2026-10-27-record-action-scope-acceptance.md).

## Client command payload isolation

DSL command actions may declare `includeRecordPayload: false`. The shared action hook then sends only explicitly configured `payload` fields and values collected by `inputFields`; the ambient record remains available to expressions, target resolution and optimistic-version checks, but its fields are not copied into the request payload. This also applies after the pre-dialog fresh-record read. Omission or `true` retains legacy record merging; any other supplied value fails before a command request. This client option does not change server input validation, permissions or command CAS semantics.

## Exact tenant identity locks

On a paired supporting Core/API, synchronous native command handlers obtain `CommandContext.commandMembershipLockAccessor()`. A missing or untyped bridge returns null and consumers must fail closed. This does not establish binary compatibility with hosts whose API predates the method. Installed integration settings remove the capability; asynchronous settings do not install it. The existing `IdentityDirectoryAccessor` retains its read-only directory responsibility.

`CommandMembershipLockAccessor.requireEnabledMemberships(List<MembershipAssertion(userPid, memberPid)>)` accepts one to thirty-two distinct exact pairs. The batch must include the authenticated current actor, with tenant, numeric user ID, user PID and member incarnation checked against trusted MetaContext. Recipient pairs are identity assertions within that tenant, not permission grants. Business function, vessel binding, object/action scope and grant validity remain independent mandatory decisions; numeric directory IDs are server-side results, not UI/REST identifiers.

The call requires an existing writable, synchronized physical transaction on the actual Core MyBatis datasource. An unrelated datasource transaction, even against the same database, is insufficient. It neither creates an independent transaction nor writes identity/status/audit/version fields to acquire a lock. Resolve exact pairs, lock every user row in ascending numeric ID order, then every member row in ascending numeric ID order, and re-read enabled, active, non-deleted identities with no leave date. An old member PID is never replaced by a later incarnation. Exact-pair reads explicitly flush/bypass MyBatis caches so an earlier lookup cannot conceal concurrent or caller-owned changes.

Declare the complete participant set before the first identity lock. Subsequent calls in the same physical transaction may reuse a subset, preserving their request order, but cannot add participants after member locks; this rejects a user/member order inversion. The transaction's proof is associated with its current synchronization and connection. A separately authorized Core REQUIRES_NEW transaction has its own proof and cannot carry the original locks or make independently committed effects atomic with the original command. Business consumers must keep admission and all resulting effects in their original command transaction.

Locks survive until that transaction commits or rolls back. The beforeCommit check revalidates every captured user/member pair, including changes made by the caller itself; any invalidated identity refuses commit. This does not lock role assignments or substitute for command authorization. Domain owners must also use a consistent domain-root order. Maritime consumers use sorted vessel roots, the complete identity batch, then sorted announcement roots; reverse composition or late batch growth is not supported.

Announcement acknowledgement proofs now reuse this shared identity capability before taking announcement roots. Their current-actor-only, exact-version/hash and sealed-recipient semantics remain as documented in [announcement management](announcement-management.md#19-command-scoped-exact-version-acknowledgement-proof). The identity capability does not acknowledge anything or authorize a reviewer to act as the applicant. Targeted component evidence, exact producers and pending product consumption are recorded in the [membership acceptance report](../retro/2026-10/2026-10-27-command-membership-lock-acceptance.md).


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
layers are recorded in the [file capability verification report](./2026-10-27-maritime-combined-files-testing-gate-acceptance-report.md).
