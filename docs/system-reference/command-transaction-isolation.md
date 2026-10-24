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
