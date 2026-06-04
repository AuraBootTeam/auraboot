---
type: system-reference
status: active
---

# Command Pipeline

The command pipeline is the execution contract that every business write in AuraBoot flows through. It is not a router and not a middleware chain; it is a fixed, ordered sequence of stages that resolves metadata, enforces access, validates input, executes the change, records audit data, and dispatches side effects — under one transactional boundary, with one principal, one audit trail, and one event surface.

This is the contract that ties the rest of the system together. Models describe *what* exists. Pages describe *how* it is shown. Commands describe *what can change* and *under what conditions*. Every other capability — permissions, processes, automations, AI tool calls — eventually issues a command, and every command takes the same path.

## The contract

Without a unified pipeline, every module would eventually grow its own version of the same five concerns: payload validation, permission and entitlement checks, transaction boundaries, audit, and event emission. Each implementation would drift from the others. The drift would show up as inconsistent error messages, missing audit records, events fired for writes that later rolled back, AI agents calling operations no human button could ever reach, and customer-specific patches that became impossible to upgrade through.

A unified pipeline rejects that drift on the way in. The contract guarantees five properties for every business write:

1. **One entry point.** A business operation is a `cmdCode`. The UI button, the BPMN task, the automation rule, the external integration, and the AI tool all dispatch the same code and traverse the same stages.
2. **Deterministic ordering.** Authorization always runs before validation; validation always runs before mutation; mutation always runs before audit; events always fire from committed state. The order does not depend on who is calling.
3. **Atomicity across the in-transaction stages.** Either every in-transaction stage succeeds and the change is committed with its audit record, or nothing is persisted.
4. **Outbox-based side effects.** External calls and webhooks never fire from inside the transaction. They fire only after commit, from durable records, so external systems never see effects of writes that rolled back.
5. **Declarative semantics.** A command's risk, idempotency, reversibility, and side-effect summary are first-class metadata. They are visible to operators, to approval policies, and to AI agents — not buried inside handler code.

Data-app builders and packaged business suites tend to leave these properties to convention. AuraBoot encodes them in the pipeline itself, so they hold across every plugin, every industry pack, and every integration.

## Pipeline stages

The pipeline has twenty in-transaction stages plus four after-commit phases. The diagram below groups them by intent. Stage names are the canonical names used inside `CommandStage`; if you read backend logs, traces, or audit records, you will see exactly these names.

```text
                    Client / UI / Automation / Process / AI
                                   |
                                   v
+--------------------------------------------------------------+
|                     RESOLUTION PHASE                         |
|                                                              |
|   1  load                  Load CommandDefinition            |
|   2  schema_validate       Validate payload shape and types  |
|   3  idempotency_check     Return cached result on replay    |
+--------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------+
|                    AUTHORIZATION PHASE                       |
|                                                              |
|   4  entitlement_check     Plugin / feature license gate     |
|   5  sod_check             Separation of Duties enforcement  |
+--------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------+
|                     VALIDATION PHASE                         |
|                                                              |
|   6  state_check           State transition guard            |
|   7  assert                Preconditions and assertions      |
|   8  pre_invariant         Pre-mutation business invariants  |
|   9  cross_field_validation Cross-field dependency rules     |
+--------------------------------------------------------------+
                                   |
                                   v
+--------------------------------------------------------------+
|                     EXECUTION PHASE                          |
|                                                              |
|  10  auto_set              Inject codes, timestamps, user    |
|  11  field_map             Map payload to columns, persist   |
|  12  computed_fields       Recompute SpEL formula fields     |
|  13  change_tracking       Field-level change records        |
|  14  handler               Custom handler(s) and extensions  |
|  16  side_effect           Related records, AGGREGATE        |
|  17  roll_up               Parent summary recalculation      |
|  18  post_action           Child records, post-processing    |
|  19  effect                Outbox write, audit entry         |
|  20  post_invariant        Post-mutation business invariants |
+--------------------------------------------------------------+
                                   |
                                   v
                       === TRANSACTION COMMITS ===
                                   |
                                   v
+--------------------------------------------------------------+
|                  POST-COMMIT PHASE                           |
|                                                              |
|  21  domain_event          In-process listeners              |
|  22  api_call              External API connectors           |
|  23  webhook               Outbound webhooks                 |
|  24  governance_snapshot   Versioned-model snapshot capture  |
+--------------------------------------------------------------+
```

### Resolution stages

The resolution stages turn a request into an executable command instance.

**1. load.** Load the published `CommandDefinition` for the requested `cmdCode`. Unpublished or unknown codes fail here before any other work happens.

**2. schema_validate.** Validate the payload against the command's input schema. Types, required fields, enums, and basic shape. Temporal normalization runs in this stage as a sub-step so downstream stages always see typed date and datetime objects, not strings.

**3. idempotency_check.** If the request carries an idempotency key and a result is already cached for it, return the cached result and short-circuit the rest of the pipeline. Retries from flaky clients or auto-retried automations never produce duplicate writes.

### Authorization stages

The authorization stages decide whether this principal is allowed to execute this command at this moment. Functional and data permissions are handled inside the wider permission contract documented separately; the pipeline stages below are the command-specific guards.

**4. entitlement_check.** Verify that the plugin and feature behind this command are licensed for the current tenant. A command that exists in the metadata but is not entitled to this tenant fails here.

**5. sod_check.** Enforce Separation of Duties. If the current actor has already performed a conflicting command on the same record, model, or globally — for example, the actor who created a purchase order is forbidden from approving it — this stage either blocks the request or records the violation, depending on policy.

### Validation stages

The validation stages enforce business rules before any state changes.

**6. state_check.** Validate the lifecycle transition. A command that targets a record in a state the command does not allow as a source state fails here. State transitions are first-class metadata, not conditional branches in handler code.

**7. assert.** Evaluate ASSERT-type rules: preconditions declared in the command's `executionConfig`, field validation rules, and binding-rule assertions contributed by plugins.

**8. pre_invariant.** Evaluate business invariants that must hold *before* the mutation. Examples include uniqueness constraints across composite keys, "may not delete a record that has open children", and any custom invariant the model declares. Failures at this stage throw a typed validation error; nothing has been written yet.

**9. cross_field_validation.** Evaluate cross-field dependency rules — rules that compare two or more payload values against each other or against the existing record. Kept as a distinct stage so error messages can refer to the rule by name.

### Execution stages

The execution stages perform the actual change inside the database transaction.

**10. auto_set.** Inject auto-generated values: business codes, current timestamps, current user ID, current organization, fixed defaults declared by the command.

**11. field_map.** Map the payload to database columns and execute the primary operation — `CREATE`, `UPDATE`, or `DELETE`. For `DELETE`, cascade-delete rules execute here too. A before-snapshot is captured for change tracking.

**12. computed_fields.** Recompute SpEL-formula fields after the primary operation. Computed fields can depend on the newly written row.

**13. change_tracking.** Compare the before-snapshot with the post-mutation row and record field-level deltas for the audit trail.

**14. handler.** Run custom command handlers — Spring beans implementing the handler interface and plugin handler extensions. HANDLER-type binding rules execute here. This is where plugin authors put domain logic that does not fit into declarative DSL.

**16. side_effect.** Execute `sideEffects` configured on the command: create related records, update related records, aggregate child rows into a parent field. Each side effect carries its own SpEL condition.

**17. roll_up.** When the current model is the child side of a roll-up relationship, automatically recompute the parent's summary fields. No manual aggregate side effect is needed for the common case.

**18. post_action.** Execute `postActions` — typically `CREATE_CHILDREN` to materialize a fixed set of child records (months in a plan, default task list for a workflow, line items derived from a template).

**19. effect.** Write events to the outbox, record the audit log entry, and run EFFECT-type binding rules. After this stage, the durable record of what happened exists, but external systems have not yet been told.

**20. post_invariant.** Evaluate invariants that must hold *after* the mutation, like "inventory cannot be negative" or "approved amount cannot exceed credit line". Post-invariant violations create alarms but do not roll the transaction back — they are escalations, not rejections.

### Post-commit phases

Everything after stage 20 runs outside the transaction, from records that were already committed.

**21. domain_event.** Publish `CommandCompletedEvent` to in-process listeners through the event bus. Listeners that need to run *after* the transaction commits subscribe with the appropriate phase; listeners that need to participate in the transaction subscribe synchronously inside the boundary.

**22. api_call.** Invoke external API connectors. The binding-rule lookup happened inside the transaction; the actual HTTP call happens here, so network latency never blocks the database.

**23. webhook.** Dispatch outbound webhooks to subscribers.

**24. governance_snapshot.** Capture a governance snapshot for versioned models, so audit and compliance views can reconstruct the state of the world at this point in time.

## Transaction boundary

The transaction boundary is the single most important property of the pipeline. The rule is simple and absolute:

> Stages 1 through 20 run inside one database transaction. The transaction either commits all of them or none of them. Stages 21 and beyond run only after the commit succeeds, and they run from durable records — the outbox, the event store, the webhook subscription table — not from in-memory state.

This is the outbox pattern, applied at the runtime level rather than per service. The consequences:

- **No external system ever sees an event for a write that rolled back.** If `field_map` succeeds but `post_invariant` triggers a rollback, the outbox row goes away with the transaction, and the webhook never fires.
- **No partial writes leak into audit.** The audit entry is written at stage 19, inside the same transaction as the data change. Either both are visible or neither is.
- **Listeners pick their own phase.** Synchronous listeners (e.g. "if this fails, roll back the whole command") subscribe in-transaction. After-commit listeners (e.g. "send an email", "update a search index") subscribe to the post-commit phase. The pipeline does not force a choice.
- **Retries are safe.** If the post-commit HTTP call to a webhook receiver fails, the outbox record stays. A dispatcher retries. The retry is not a re-execution of the command — the command already committed — so it does not reopen any validation or state-check.

This is also why side effects belong in stages 16-19 (in-transaction, for related records the system owns) and external integrations belong in stages 22-23 (post-commit, for systems the platform does not own). Mixing the two is a category error.

## Three command shapes

Every command in AuraBoot is one of three shapes. The shape determines which stages have meaningful configuration; all three shapes traverse the full pipeline.

### Action

An Action mutates data. Creating, updating, or deleting a record without changing its lifecycle state is an Action. Most "save" buttons are Actions.

```json
{
  "cmdCode": "CreateCustomerCommand",
  "displayName": "Create customer",
  "modelCode": "customer",
  "inputSchema": {
    "type": "object",
    "properties": {
      "customerName": { "type": "string", "required": true },
      "creditLimit":  { "type": "number" }
    }
  },
  "executionConfig": {
    "operationType": "CREATE",
    "autoSetFields": [
      { "field": "createdBy", "strategy": "CURRENT_USER" },
      { "field": "customerCode", "strategy": "AUTO_GENERATE" }
    ]
  },
  "idempotent": true,
  "reversible": false,
  "riskLevel": "L1",
  "agentHint": "Create a new customer record",
  "sideEffectDescription": "Inserts one customer row; no external calls."
}
```

### StateTransition

A StateTransition moves a record from one lifecycle state to another. Submitting a draft for review, approving an order, marking an invoice paid, cancelling a shipment — all StateTransitions. The pipeline's `state_check` stage uses the command's declared source states to refuse out-of-order calls.

```json
{
  "cmdCode": "SubmitPurchaseOrderCommand",
  "displayName": "Submit purchase order",
  "modelCode": "purchase_order",
  "executionConfig": {
    "operationType": "STATE_TRANSITION",
    "stateField": "status",
    "fromStates": ["draft", "rejected"],
    "toState": "pending_approval",
    "preconditions": [
      {
        "expression": "#record.totalAmount > 0",
        "message": "Cannot submit a purchase order with zero total."
      }
    ]
  },
  "idempotent": false,
  "reversible": true,
  "riskLevel": "L2",
  "agentHint": "Submit a draft purchase order for approval"
}
```

### FlowStep

A FlowStep is a command whose primary purpose is to advance an orchestrated process. The command runs through the same pipeline as any other, but its completion signals the process engine to move forward. FlowSteps are how a BPMN task stays inside the governance contract — the user task is not arbitrary form data, it is a typed command with permissions, audit, and a declared risk level.

```json
{
  "cmdCode": "ApprovePurchaseOrderCommand",
  "displayName": "Approve purchase order",
  "modelCode": "purchase_order",
  "executionConfig": {
    "operationType": "FLOW_STEP",
    "stateField": "status",
    "fromStates": ["pending_approval"],
    "toState": "approved",
    "processBinding": {
      "processCode": "po_approval_v1",
      "taskCode": "approve"
    }
  },
  "idempotent": false,
  "reversible": true,
  "riskLevel": "L2",
  "agentHint": "Approve a purchase order that is pending approval",
  "sideEffectDescription": "Notifies requester; advances the approval process."
}
```

## Preconditions and state guards

Preconditions and state guards are business rules expressed in metadata, not in handler code. They are evaluated by the pipeline at known stages, with known error semantics.

A precondition is a SpEL expression against the payload, the target record, and the current context. It fails the command before any mutation runs.

```json
{
  "preconditions": [
    {
      "expression": "#record.creditUsed + #payload.amount <= #record.creditLimit",
      "message": "Order would exceed customer credit limit."
    },
    {
      "expression": "#payload.deliveryDate >= T(java.time.LocalDate).now()",
      "message": "Delivery date cannot be in the past."
    }
  ]
}
```

A state guard is the combination of `stateField`, `fromStates`, and `toState`. The pipeline rejects any attempt to run the command against a record whose current state is not in `fromStates`. For multi-branch transitions, `stateTransitionRules` declares guard expressions per branch:

```json
{
  "stateField": "status",
  "fromStates": ["pending_approval"],
  "stateTransitionRules": [
    { "guard": "#payload.amount <= 10000", "toState": "approved" },
    { "guard": "#payload.amount > 10000",  "toState": "needs_director_approval" }
  ]
}
```

The point is that the rule lives in the command definition, not in a switch statement inside a handler. The reviewer of a plugin can see, by reading metadata alone, what states a command moves between and under what conditions.

## Idempotency, reversibility, risk

Four fields on every command describe how the command behaves under retry, undo, and exposure to automation. These are not advisory — they are read by the runtime, by approval policies, and by the AI tool surface.

| Field | Meaning |
|---|---|
| `idempotent` | A retry of the same command with the same payload and idempotency key produces the same result and does not duplicate effects. The `idempotency_check` stage relies on this. |
| `reversible` | A subsequent command can fully undo this one. Used by orchestration and by AI rollback flows. |
| `riskLevel` | One of `L0` (read-only), `L1` (single-object write), `L2` (cross-object write), `L3` (external system call), `L4` (irreversible). |
| `sideEffectDescription` | Human-readable summary of what happens beyond the primary mutation. Surfaced in approval UIs and to AI agents. |

These fields matter because the command surface is shared. A command is callable from a UI button, from an automation, from a BPMN flow, and from an AI tool. The UI can render a confirmation modal for `L4` commands. An automation rule can be configured to refuse to invoke any command with `riskLevel >= L3`. An AI agent can be restricted to commands marked `idempotent: true, riskLevel <= L1` for autonomous use, and required to ask for human confirmation on anything higher.

Without these fields as first-class metadata, every caller would have to maintain its own list of "operations I'm allowed to run". With them, the command itself declares its blast radius, and every caller can apply policy uniformly.

## Error semantics

Pipeline errors are direct and named. The pipeline does not have a generic catch-all that converts unknown failures into success. It does not have silent fallback. A configuration error is not absorbed by retry; it is raised as a specific class.

| Error class | When | Stage |
|---|---|---|
| `CommandNotFound` | The `cmdCode` does not exist or is not published | load |
| `SchemaValidationFailed` | Payload is wrong shape, missing required field, wrong type | schema_validate |
| `IdempotencyConflict` | Same idempotency key was used with a different payload | idempotency_check |
| `EntitlementDenied` | Plugin or feature not licensed for this tenant | entitlement_check |
| `SodViolation` | Actor has performed a conflicting command on the same scope | sod_check |
| `InvalidStateTransition` | Record is not in any of the command's `fromStates` | state_check |
| `PreconditionFailed` | A precondition expression evaluated false | assert |
| `InvariantViolation` | Pre- or post-invariant evaluated false | pre_invariant / post_invariant |
| `CrossFieldValidationFailed` | Cross-field rule failed | cross_field_validation |
| `HandlerFailed` | A custom handler threw an exception | handler |
| `SideEffectFailed` | A required side effect could not execute | side_effect |

Each error carries the stage name, the rule or field that triggered it, and a localized message. Audit records capture the failure as well as the success: a failed command is just as much a recorded business event as a successful one. The pipeline never silently "tries the other code path"; if a command cannot proceed, it fails with a specific class, and the caller — UI, automation, or agent — gets to decide what to do.

## Design guidelines

The pipeline is opinionated about how plugins and applications should be built around it.

- **Commands are the only write path.** UI buttons map to `cmdCode`. Automations dispatch `cmdCode`. BPMN tasks resolve to `cmdCode`. AI tools call `cmdCode`. There is no "fast path" that bypasses the pipeline to write data directly.
- **Express rules in metadata when you can.** Preconditions, state transitions, invariants, side effects, post-actions, and roll-ups are all declarative. Reach for a custom handler only when the rule cannot be expressed declaratively.
- **Keep handlers focused.** Custom handlers run at stage 14, after authorization, validation, and primary persistence. They are the place for domain logic that *follows* the change, not for re-implementing checks that earlier stages already ran.
- **Side effects vs. external calls.** Anything that writes to records the platform owns goes in `sideEffects` and runs in-transaction. Anything that calls a system the platform does not own goes through an API connector or a webhook and runs post-commit.
- **Declare the four semantic fields.** Every command should set `idempotent`, `reversible`, `riskLevel`, and `sideEffectDescription`. These are read by the AI surface and by approval policies; commands that omit them are treated as high-risk by default.
- **Use idempotency keys on external dispatch.** When an automation or a webhook receiver triggers a command, pass an idempotency key. The pipeline will deduplicate retries automatically.
- **Test commands through the API.** UI testing is necessary but not sufficient. A command's contract — payload schema, error classes, audit entries, events emitted — is a runtime contract, not a UI contract. Test it at the runtime boundary.

> **Enterprise note.** The commercial distribution layers additional governance on top of the same pipeline contract: strict entitlement enforcement with marketplace gating, declarative Separation-of-Duties policy expressions, risk-graded approval gates that hold commands at known stages until approvers act, and distributed trace spans per pipeline stage for end-to-end observability. The contract that plugins and applications write against does not change — the open-source pipeline is the surface; the enterprise distribution adds policy and observability on top.

## Next steps

- [Permissions](/docs/core-concepts/permissions) — the layered model the authorization stages call into
- [Plugin manifest](/docs/core-concepts/plugin-manifest) — how commands are declared and shipped
- [Agent readiness](/docs/core-concepts/agent-readiness) — why command metadata is what makes AuraBoot AI-safe
- [System overview](/docs/architecture/system-overview) — where the pipeline sits in the runtime
