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

## Client command payload isolation

DSL command actions may declare `includeRecordPayload: false`. The shared action hook then sends only explicitly configured `payload` fields and values collected by `inputFields`; the ambient record remains available to expressions, target resolution and optimistic-version checks, but its fields are not copied into the request payload. This also applies after the pre-dialog fresh-record read. Omission or `true` retains legacy record merging; any other supplied value fails before a command request. This client option does not change server input validation, permissions or command CAS semantics.

## Exact tenant identity locks

On a paired supporting Core/API, synchronous native command handlers obtain `CommandContext.commandMembershipLockAccessor()`. A missing or untyped bridge returns null and consumers must fail closed. This does not establish binary compatibility with hosts whose API predates the method. Installed integration settings remove the capability; asynchronous settings do not install it. The existing `IdentityDirectoryAccessor` retains its read-only directory responsibility.

`CommandMembershipLockAccessor.requireEnabledMemberships(List<MembershipAssertion(userPid, memberPid)>)` accepts one to thirty-two distinct exact pairs. The batch must include the authenticated current actor, with tenant, numeric user ID, user PID and member incarnation checked against trusted MetaContext. Recipient pairs are identity assertions within that tenant, not permission grants. Business function, vessel binding, object/action scope and grant validity remain independent mandatory decisions; numeric directory IDs are server-side results, not UI/REST identifiers.

The call requires an existing writable, synchronized physical transaction on the actual Core MyBatis datasource. An unrelated datasource transaction, even against the same database, is insufficient. It neither creates an independent transaction nor writes identity/status/audit/version fields to acquire a lock. Resolve exact pairs, lock every user row in ascending numeric ID order, then every member row in ascending numeric ID order, and re-read enabled, active, non-deleted identities with no leave date. An old member PID is never replaced by a later incarnation. Exact-pair reads explicitly flush/bypass MyBatis caches so an earlier lookup cannot conceal concurrent or caller-owned changes.

Declare the complete participant set before the first identity lock. Subsequent calls in the same physical transaction may reuse a subset, preserving their request order, but cannot add participants after member locks; this rejects a user/member order inversion. The transaction's proof is associated with its current synchronization and connection. A separately authorized Core REQUIRES_NEW transaction has its own proof and cannot carry the original locks or make independently committed effects atomic with the original command. Business consumers must keep admission and all resulting effects in their original command transaction.

Locks survive until that transaction commits or rolls back. The beforeCommit check revalidates every captured user/member pair, including changes made by the caller itself; any invalidated identity refuses commit. This does not lock role assignments or substitute for command authorization. Domain owners must also use a consistent domain-root order. Maritime consumers use sorted vessel roots, the complete identity batch, then sorted announcement roots; reverse composition or late batch growth is not supported.

Announcement acknowledgement proofs now reuse this shared identity capability before taking announcement roots. Their current-actor-only, exact-version/hash and sealed-recipient semantics remain as documented in [announcement management](announcement-management.md#19-command-scoped-exact-version-acknowledgement-proof). The identity capability does not acknowledge anything or authorize a reviewer to act as the applicant. Targeted component evidence, exact producers and pending product consumption are recorded in the [membership acceptance report](../retro/2026-10/2026-10-27-command-membership-lock-acceptance.md).
