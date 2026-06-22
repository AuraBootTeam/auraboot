---
type: system-reference
status: active
---

# Agent Readiness

AuraBoot is designed so that AI agents act on the business through the **same execution contract** that humans use — not through a separate, weaker, free-text shell. This page explains the design choices that make that contract AI-safe, and how an agent (or the developer wiring one up) should reason about it.

If you are looking for the *runtime* surfaces that expose this contract to agents, see [MCP and Tools](/docs/en/ai/mcp-and-tools), [Agent Builder](/docs/en/ai/agent-builder), [Aura Bot](/docs/en/ai/aura-bot), and the [ACP Protocol](/docs/en/ai/acp-protocol). This page is the *concept* page that sits underneath all of them.

## Why AI agents need a different call surface

A generic LLM agent that is given "an API token and the OpenAPI spec" can do a great deal of damage in an enterprise system. The failure modes are not theoretical; they are the predictable consequence of three properties of free-text API access:

1. **Unbounded blast radius.** REST endpoints typically accept any combination of fields a caller can construct. There is no declaration that "this call writes to ledger entries" or "this call cannot be undone". An LLM that hallucinates a parameter, or that retries on a transient timeout, will happily perform an irreversible cross-object write because nothing tells it not to.
2. **No declared semantics.** An OpenAPI summary like `POST /orders` does not tell the agent that this is a write, that it is *not* idempotent on the natural key, that submitting it twice creates duplicate inventory reservations, or that recall requires a different endpoint with a different permission.
3. **No audit identity for the agent itself.** When the model invokes a token-authenticated endpoint, the audit trail says "user X did the thing". There is no record that user X is in fact an agent acting on behalf of user X, what prompt produced the call, or which conversation it belonged to.

The right answer is not to bolt a guard rail onto the REST layer. The right answer is to expose a **call surface whose unit of action is a governed business command**, declared with enough metadata that an agent can reason about whether to call it, retry it, or escalate to a human. That surface is what AuraBoot calls *Agent Readiness*.

The shape of the contrast:

| Property | Free-text REST access | AuraBoot command surface |
|---|---|---|
| Unit of action | An endpoint path + JSON body | A published `Command` with a stable `cmdCode` |
| Declared intent | None | `agentHint`, `displayName`, `description` |
| Declared risk | None | `riskLevel`, `idempotent`, `reversible` |
| Authorization | API token scope | Per-command permission inherited from the calling user |
| Validation | Whatever the controller enforces | The full 20-stage command pipeline |
| Audit | HTTP access log | Structured audit record with command, payload, agent identity, user |
| Reversibility hint | None | `reversible: true` + a paired recall/cancel command |

Everything below explains how each of those rows is delivered.

A second consequence is that **the agent surface and the human surface co-evolve**. A new command added by a plugin becomes callable by humans through the page designer and by agents through the tool catalog *on the same day*, with no separate "expose to AI" step. The four declaration fields are part of the command authoring workflow, not a downstream concern.

A third consequence is that **the contract is portable**. Whether an agent runs inside the AuraBoot deployment (Aura Bot, an Agent Builder agent), connects from outside over MCP, or calls another AuraBoot deployment over ACP, it speaks the same vocabulary: `cmdCode`, `agentHint`, `riskLevel`, `idempotent`, `reversible`, `inputSchema`. There is no agent runtime that sees a different shape.

## Commands as the AI-safe execution unit

The single most important design decision in AuraBoot's Agent Readiness story is this: **agents do not get a private execution path.** When an agent invokes `expense.report.submit`, the request travels through the same pipeline as the same command invoked from the web UI, from a workflow task, or from a mobile client. The pipeline is described in detail in [Command Pipeline](/docs/en/core-concepts/command-pipeline); the part that matters for agents is the *guarantee that follows from it*.

The guarantee is that the following stages run, in order, regardless of who is calling — a human, an agent, or a workflow:

1. **Load** the published command definition. Drafts and deleted commands are not callable.
2. **Schema validate** the payload against the command's `inputSchema`. Hallucinated fields are rejected here, not after they have been written.
3. **Idempotency check.** If the command is declared `idempotent: true` and the request carries an idempotency key, a duplicate request returns the original result rather than executing again.
4. **Entitlement check.** Feature gates and license checks happen before any business logic.
5. **Separation of Duties (SoD).** The same user cannot both submit and approve, regardless of permission grants.
6. **State guard.** Lifecycle transitions are enforced by the state graph, not by the caller.
7. **Assertions, invariants, cross-field validation.** Pre- and post-conditions evaluated before and after the write.
8. **Field map and handler.** The actual write or handler logic, inside a transaction.
9. **Side effects, roll-ups, post-actions.** All inside the transaction boundary.
10. **Effect, audit, change tracking.** Audit records are written as part of the same commit.
11. **Domain events, API calls, webhooks.** After the transaction commits successfully.

Because an agent is never allowed to skip these stages, the **system's safety properties do not weaken when an agent calls in**. An agent that has been told to "submit five expense reports as fast as possible" cannot bypass SoD, cannot skip approval workflows, cannot duplicate-write on retry if the command is declared idempotent, and cannot produce an audit trail that hides who did what.

The transaction boundary deserves special attention. Every state-changing stage — `FIELD_MAP`, `COMPUTED_FIELDS`, `CHANGE_TRACKING`, `HANDLER`, `SIDE_EFFECT`, `ROLL_UP`, `POST_ACTION`, `EFFECT`, `POST_INVARIANT` — runs inside a single database transaction. If any of them fails, *all* of them roll back, including the audit record. Stages that should run only after a successful commit (`DOMAIN_EVENT`, `API_CALL`, `WEBHOOK`, `GOVERNANCE_SNAPSHOT`) run outside the transaction. This means a failed agent call leaves no half-applied write, no orphan audit row, and no leaked webhook. Either the whole call took effect or none of it did.

The same property holds in the other direction: a successful agent call cannot have its audit silently dropped on retry. The audit row is part of the same commit as the data write; the database guarantees they appear or disappear together.

### What an agent sees when a stage rejects the call

An agent that calls a command and receives a structured error gets back the **stage name** that rejected the call, plus a structured reason. The vocabulary is stable:

- `schema_validate` — payload shape was wrong. The agent should refine its arguments.
- `idempotency_check` — a duplicate of an in-flight request. The agent should wait and re-read state.
- `entitlement_check` — the feature is not licensed in this tenant. Not retriable by the agent.
- `sod_check` — Separation of Duties forbids this user from doing this action on this record. The agent should escalate to a different user, not retry.
- `state_check` — the target record is in a state from which this command is illegal. The agent should re-read the record's status before continuing.
- `assert` / `pre_invariant` / `post_invariant` — a business rule failed. The agent should report the rule message, not retry.

Returning the stage name gives the agent's reasoning loop something more useful than "HTTP 400". It enables prompts like "if `state_check` fails, fetch the current status before responding to the user", which is a vastly better recovery loop than "retry on any error".

## The four declaration fields

Every `CommandDefinition` in AuraBoot carries four first-class fields that an agent reads before deciding whether and how to call it. They are stored on the command itself, surfaced through the MCP tool descriptor, and surfaced in the in-product agent runtime.

### `agentHint`

A short, natural-language description of the *business intent* of the command, written for an LLM reader rather than a developer reader. It is **not** the API description; it is the answer to "if you were going to tell an agent when to call this, in one sentence, what would you say?"

Examples:

| `cmdCode` | `agentHint` |
|---|---|
| `expense.report.submit` | "Submit a drafted expense report for manager approval. Use only after the employee has confirmed totals and attached receipts." |
| `expense.report.recall` | "Withdraw an in-flight expense report so the submitter can edit it again. Only valid before final approval." |
| `inventory.transfer.execute` | "Move stock between two locations in a single atomic operation. Use only after sourcing and destination have been confirmed by the user." |
| `customer.account.deactivate` | "Mark a customer account as inactive. This hides the account from list pages but does not delete history." |

The hint is rendered into every MCP tool descriptor and every internal tool registration. A good `agentHint` reduces hallucinated calls more than any other single intervention.

Good `agentHint` discipline:

- Write it in the natural language the target users will use, not in product jargon.
- State the preconditions that a human would check before clicking the button.
- Name the paired recall or cancel command if one exists.
- Do *not* repeat the parameter schema — that is already in `inputSchema`.
- Do *not* describe the implementation — agents do not need to know which handler runs.

### `riskLevel`

A graded classification of the operation's impact. The platform uses five levels:

| Level | Meaning | Example commands |
|---|---|---|
| `L0` (read) | No state change. Safe to call freely. | `customer.list`, `order.detail.get` |
| `L1` (write) | Single-object write inside one tenant. Standard CRUD. | `customer.create`, `note.append` |
| `L2` (cross-object) | Writes that touch multiple records or aggregates. | `order.submit`, `inventory.transfer.execute` |
| `L3` (external) | Writes that have an external-system effect via webhook or API call after commit. | `payment.refund.dispatch`, `email.campaign.send` |
| `L4` (irreversible) | Writes whose effect cannot be cleanly reversed inside AuraBoot. | `record.permanent.delete`, `tenant.archive` |

An agent should treat the levels as a confirmation gradient: `L0` and `L1` may be called as part of an agent's normal reasoning; `L2` warrants a brief summary to the user before calling; `L3` and `L4` require explicit user confirmation in the same turn.

The mapping is intentionally coarse. Five levels is enough to separate "free to call" from "requires summary" from "requires confirmation"; a finer grade would invite arguments about which subcategory applies to a given command without changing the agent's actual behavior. The grade is set once, in the command definition, by the same author who wrote the handler.

### `idempotent`

A boolean that declares whether re-executing the command with the same payload (and the same idempotency key) produces the same business state. Agents and clients use this to decide retry policy:

- `idempotent: true` — the command can be retried on transient failure. The pipeline's idempotency stage will return the original result for duplicate requests.
- `idempotent: false` — the command must not be retried automatically. The agent should re-prompt the user, or check downstream state first, before issuing again.

Most reads are idempotent. Most submits, transitions, and external dispatches are not.

### `reversible`

A boolean that declares whether there exists a *paired command* that undoes the effect. Reversibility is a property of the *business domain*, not of the database — it means "if the user changes their mind, is there a documented command they can call to put things back?"

`reversible: true` is normally paired with an `agentHint` on a sibling command:

```json
{
  "cmdCode": "expense.report.submit",
  "reversible": true,
  "agentHint": "Submit a drafted expense report. Reversible via expense.report.recall before final approval."
}
```

`reversible: false` means the agent must obtain explicit user confirmation before calling, *even if* the user previously approved similar calls in the same conversation.

The reason consent does not transfer across irreversible commands is straightforward: an irreversible action is, by definition, the last point at which the user can change their mind. A blanket "yes to everything" earlier in the conversation is not a substitute for "yes to this specific irreversible action right now".

There is also a documentation effect. Pairing `reversible: true` with the recall command's `cmdCode` in the `agentHint` makes the safety net **discoverable**. An agent that knows about the recall command can confidently issue the submit; an agent that doesn't will (correctly) be more cautious.

## Tool exposition

The four declaration fields are how a command becomes an *agent-callable tool*. The mechanism is described in detail in [MCP and Tools](/docs/en/ai/mcp-and-tools); the summary is:

The platform's `DslToolProvider` walks the set of published commands the calling user has permission to invoke, and for each one emits a tool descriptor that exposes:

- `cmdCode` — used as the stable tool name.
- `displayName` — used as the tool's human-friendly label, localized.
- `agentHint`, `riskLevel`, `idempotent`, `reversible` — surfaced as structured metadata the agent can reason over.
- `inputSchema` — the command's JSON Schema, used directly as the function-call schema. Required fields, enums, format constraints, and descriptions all flow through.
- `requiredFeature` — present when entitlement gating applies, used to filter the catalog.

What is **not** exposed:

- Handler class names, Spring bean wiring, internal SPI implementations.
- The 20-stage pipeline configuration. The agent sees the command, not the orchestration.
- Other tenants' commands. The catalog is per-tenant.
- Commands the calling user lacks permission for. Agents never see tools they couldn't call.

The result is a small, typed, semantically labeled catalog rather than a sprawling REST surface. An agent connecting via MCP, ACP, or the in-product Agent Builder sees the same shape.

### Catalog filtering

The catalog the agent sees is **the intersection of**:

- Commands published in this tenant.
- Commands whose required feature, if any, is entitled in this tenant.
- Commands the calling user has the listed permission for.
- Commands whose target model is within the user's data and org scope.

This intersection is computed when the agent's tool list is materialized. The agent never sees a tool it could not, in principle, call. The corollary is that you can shrink an agent's effective surface by **changing the user's permissions** rather than by maintaining a parallel agent allow-list.

### Declared agent tools in runtime paths

Agent definitions may also declare an explicit tool allow-list, such as `cmd:expense.report.submit`
or `nq:customer_open_items`. That declaration is part of the runtime contract, not merely a prompt
hint. Both the chat path and the dispatch/run path materialize declared tools in addition to the
tools inferred from the task's grounded business object.

This matters for cross-model agents. A customer-support agent may ground an inbound email to a
`crm_complaint` object while still declaring a follow-up activity command on
`crm_activity_common`. The runtime resolves each explicitly declared tool with its own provider and
model hint, discovers the matching descriptor, then merges it additively into the available-tool set.
Grounding can narrow the catalog, but it must not make a declared cross-model tool disappear.

Approval metadata travels with the descriptor. Custom tools and DSL commands expose their
`inputSchema`, `requiresApproval`, and `riskLevel` during discovery so the runtime can pause before
high-risk or external-effect tools. When a paused run resumes after approval, it replays the exact
approved tool input rather than asking the LLM to regenerate arguments. That preserves the human
approval boundary and prevents duplicate external effects such as sending the same customer reply
twice.

### What an MCP tool descriptor looks like

```json
{
  "name": "expense.report.submit",
  "description": "Submit Expense Report — Submit a drafted expense report for manager approval. Use after the employee has confirmed totals and attached receipts. Reversible via expense.report.recall before final approval.",
  "inputSchema": {
    "type": "object",
    "required": ["operationType", "targetRecordId"],
    "properties": {
      "operationType": { "type": "string", "enum": ["UPDATE"] },
      "targetRecordId": { "type": "string", "description": "ID of the draft report to submit." }
    }
  },
  "annotations": {
    "auraboot.riskLevel": "L2",
    "auraboot.idempotent": false,
    "auraboot.reversible": true
  }
}
```

The `annotations` block is the agent's machine-readable copy of the four declaration fields. The natural-language hint is merged into `description` so that LLMs that only consume the standard MCP fields still get it.

## Worked example: an agent submitting an expense report

To make the rules concrete, here is the decision flow when an agent receives the instruction "submit my October trip expense report".

**Step 1: search the tool catalog.**

The agent searches its tool catalog and finds two relevant commands:

```json
{
  "cmdCode": "expense.report.submit",
  "displayName": "Submit Expense Report",
  "agentHint": "Submit a drafted expense report for manager approval. Use after the employee has confirmed totals and attached receipts.",
  "riskLevel": "L2",
  "idempotent": false,
  "reversible": true
}
```

```json
{
  "cmdCode": "expense.report.recall",
  "displayName": "Recall Expense Report",
  "agentHint": "Withdraw an in-flight expense report so the submitter can edit it again. Valid only before final approval.",
  "riskLevel": "L1",
  "idempotent": true,
  "reversible": false
}
```

**Step 2: reason about risk.**

`riskLevel: L2` means the write touches more than one record (the report header, its line items, and a workflow instance). The agent decides to summarize the planned call to the user before issuing it.

**Step 3: reason about idempotency.**

`idempotent: false` means the agent must not silently retry on timeout. If the call fails, the agent re-reads the report status before retrying.

**Step 4: reason about reversibility.**

`reversible: true`, with `expense.report.recall` available, tells the agent that an honest mistake is recoverable. The agent does *not* need to demand a second confirmation; one is enough.

**Step 5: call.**

The agent calls `expense.report.submit` with the report's `targetRecordId` and the correctly typed `operationType` (`UPDATE`, since the command transitions an existing record). The pipeline runs all 20 stages. Audit records are written. A domain event fires after commit and triggers the approval workflow.

**Step 6: handle failure.**

If the pipeline rejects the call — for example, because the state guard refuses the transition, or SoD bars the agent's user from submitting a report they previously approved — the agent receives a structured error with the stage name and reason. It surfaces the cause to the user rather than retrying.

The whole sequence relies on no agent-specific code. The same flow happens whether the call originates from MCP, ACP, the in-product agent, or the web UI's "Submit" button.

**Counter-example: what goes wrong without the declarations.**

Imagine the same `expense.report.submit` exposed as a plain `POST /api/expense-reports/{id}/submit`. The agent reads only `POST`, a path, and a JSON schema. It has no signal that the call is `L2` (so it does not summarize before calling); no signal that it is non-idempotent (so it retries on timeout, producing duplicate submissions and a duplicate workflow instance); no signal that a recall exists (so a user who changes their mind asks the agent to "undo" and the agent guesses at a DELETE that does not exist). Every one of these failure modes is closed by adding three booleans and a sentence of hint text — which is precisely the trade the four declaration fields encode.

## Permission inheritance

An agent in AuraBoot acts **on behalf of** a user. It does not have a permission set of its own. When user Alice opens a conversation with the in-product agent and asks it to submit a report, the resulting command call carries Alice's identity through the authentication and authorization stages. The agent cannot do anything Alice could not do.

This is enforced at three layers, the same three layers that apply to human users (see [Permissions](/docs/en/core-concepts/permissions)):

1. **Layer 1 — Permission.** Does Alice's role grant the permission attached to `expense.report.submit`? If not, the call is rejected at the authorization stage. The agent does not see this command in its catalog in the first place.
2. **Layer 2 — Data scope.** Among the records this command could target, which can Alice see? Filters at this layer prune the candidate set before the write.
3. **Layer 3 — Org scope.** If Alice is in Org A and the target record belongs to Org B, the call is rejected regardless of permissions at layer 1.

There is no agent-specific bypass at any of these layers. There is no service-account mode in which "the agent" has elevated rights independent of the user. Cross-tenant operations are denied at layer 3 for agents in exactly the way they are denied for humans.

The practical consequence is that **an agent cannot be made more powerful than its user**, and the safest way to constrain an agent is to constrain the user it runs as. A read-only auditing agent runs as a read-only user; a frontline support agent runs as a support agent's account; a regulator-facing agent runs as a regulator's account.

This also resolves a class of governance questions that would otherwise be hard. "Can the agent see this record?" reduces to "can the user see this record?". "Can the agent delete this customer?" reduces to "can the user delete this customer?". The existing user-access controls are the agent-access controls. Compliance teams who have already approved a user model do not need to approve a separate, parallel agent model.

It also means that **revoking agent access is the same operation as revoking user access**. Disabling the user disables the agent acting on their behalf. Rotating the user's roles takes effect on the agent's next tool-catalog materialization.

## Audit and traceability

Every command invocation produces an audit record as part of the same database transaction that performed the write. This is true for human callers, and it is true for agents. The difference for agents is that the audit record additionally captures the agent layer:

- **User identity** — the human the agent is acting on behalf of.
- **Agent identity** — which agent definition was active, which model and version it used.
- **Prompt source** — the conversation or trigger that produced the call.
- **Approving user** — when an irreversible or high-risk command required user confirmation, the user who gave it.

Auditors get a queryable surface — the same audit log API that powers the in-product audit views — rather than a pile of chat transcripts. Compliance questions like "which commands did agents invoke last quarter that touched ledger entries", "which were L3 or L4", and "which required and received user approval" are answerable as SQL or as filters in the audit UI.

Because the audit record is written inside the command's transaction, it cannot drift from the write. There is no "audit fired but the write rolled back" or vice versa.

A representative audit row, conceptually:

| Field | Value |
|---|---|
| `cmdCode` | `expense.report.submit` |
| `payload` | `{ "operationType": "UPDATE", "targetRecordId": "01HQ..." }` |
| `userId` | Alice |
| `agentId` | `aura-bot/v3` |
| `agentVersion` | `2026.04.18` |
| `conversationId` | `conv_01HQ...` |
| `riskLevel` | `L2` |
| `approvedBy` | Alice (explicit confirm in the same turn) |
| `stage` | `completion` |
| `outcome` | `success` |

For an `L4` command, `approvedBy` is mandatory; the pipeline refuses the call without it. For an `L0` or `L1` agent call, `approvedBy` is null and the row still includes the agent identity and conversation. The audit log thus answers, in one query, both "what did Alice approve?" and "what did her agent do without asking?".

## Integration with BPM

The command surface is also where AuraBoot's workflow engine and its agent layer meet.

- **Agents trigger workflows.** Any `Command` that emits a domain event can trigger a workflow. An agent that calls `expense.report.submit` indirectly starts the approval flow because the workflow is subscribed to the submission event, not because the agent invoked the workflow directly.
- **Agents are assigned workflow tasks.** A workflow task can be modeled as a service task that resolves to a `Command`. When an agent picks up the task, it calls the same command a human user would have called at that step. The command's permission, risk, and audit semantics apply.
- **Workflows can branch on `riskLevel`.** A common pattern is to model an "agent-assisted" branch in the workflow that requires explicit human approval when the next service-task command is declared `L3` or `L4` or `reversible: false`, and to allow automatic execution for lower-risk steps.

The contract is preserved end to end: a workflow task is a command, an agent calling that task is a command call, and the audit record captures all three layers.

### A concrete BPM pattern: "human in the loop on irreversible steps"

A common pattern in agent-assisted workflows is to let the agent push the process forward on low-risk steps and stop for explicit human approval on high-risk ones. The shape:

1. The workflow defines a sequence of service tasks, each resolving to a `Command`.
2. Before each service task, a script task inspects the next command's `riskLevel` and `reversible` from the command metadata.
3. If the next command is `L0`/`L1`/`L2` and `reversible: true`, the workflow auto-advances and the agent executes.
4. If the next command is `L3`/`L4` or `reversible: false`, the workflow inserts a user task assigned to the human in the loop. The agent waits.
5. The audit log records, on the eventual command call, that approval was given by the human, on this workflow instance, in response to this user task.

This pattern is implemented entirely in workflow configuration. The platform does not need a special "agent mode" workflow type.

## What an agent should NOT be allowed to do

Agent Readiness is as much about explicit prohibitions as it is about affordances. The following are *not* legitimate agent actions in AuraBoot, regardless of how the agent is built or hosted:

- **Invoke irreversible high-risk commands without user approval.** An agent that holds a tool descriptor with `riskLevel: L4` or `reversible: false` must request explicit user confirmation in the same turn before calling. A confirmation given earlier in the conversation does not transfer to a new such command.
- **Operate across tenants.** An agent's user belongs to a tenant. Cross-tenant operations are denied at layer 3. There is no agent-mode escape hatch.
- **Bypass the pipeline with raw SQL or direct repository calls.** Plugin code that exposes a "for the agent" backdoor is a platform anti-pattern. The pipeline is the only legitimate write path.
- **Skip audit.** No agent runtime, no MCP tool, no ACP handler may suppress the audit record. Audit is written as part of the command's transaction and cannot be opted out of.
- **Self-grant permissions.** Agents cannot invoke administrative commands to widen their own user's permissions in the middle of a session.
- **Forge an approving user.** When a command requires user approval, the approving user is the one who actually clicked confirm — not whichever user the agent's prompt says approved.

These are properties of the platform, not properties of the prompt. An agent told "you may bypass approval" still cannot bypass approval, because the prohibition lives in the command pipeline, not in the agent's instructions.

This distinction — prohibition by platform versus prohibition by prompt — is the load-bearing one. Prompt-level prohibitions are a request to the model, and modern LLMs are increasingly good at honoring them; but they are not enforceable. Platform-level prohibitions are not requests; they are the floor below which the system cannot fall regardless of which model, vendor, or prompt is on top. The four declaration fields and the 20-stage pipeline together form that floor.

A useful self-check when designing a new command: *if a malicious or confused agent called this command at full speed with arbitrary arguments, what is the worst that could happen?* If the answer involves "data loss across tenants" or "irrecoverable financial impact" or "regulatory breach without audit", the command is not yet Agent-Ready. The remedy is usually one of: adjust `riskLevel` upward to force confirmation; mark `reversible: false` and pair it with a recall; tighten the permission so fewer users (and therefore fewer agents) can invoke it; or move the high-risk path behind a workflow with a mandatory user task.

The same self-check applies to existing commands when an agent runtime is enabled for a tenant for the first time. The catalog the agent will see is exactly the catalog the user can already invoke through the UI. If anything in that catalog would be unsafe to expose, it would also be unsafe in the UI — and the fix is on the command, not on the agent.

:::note[Enterprise: Agent Control Plane]
The OSS surface gives a single tenant a safe, audited agent contract. The enterprise edition adds the **Agent Control Plane**: cross-tenant agent orchestration, agent identity provisioning that is independent of human user accounts, audit replay with risk-graded approval gates, per-agent token and cost budget caps, multi-agent coordination, and prompt provenance signing so audit records can be replayed against the exact prompt and model version that produced them. The contract on the page above does not change — Agent Control Plane is governance and operations *on top of* it.
:::

## A short authoring checklist

When you add a new command and want it to be Agent-Ready on day one, work through this list:

1. **Write a `displayName` and `description` for humans first.** They become part of the agent's prompt; treat them as documentation, not as boilerplate.
2. **Write a one-sentence `agentHint`.** State the business intent and any precondition a human would check before clicking.
3. **Set `riskLevel`.** Default to one step *higher* than feels obvious. The agent will summarize before calling; the cost of an extra summary is low and the benefit of catching a mistake is high.
4. **Set `idempotent` honestly.** If two identical calls produce two different business outcomes — two reservations, two emails, two ledger entries — the command is not idempotent and must say so.
5. **Decide if a paired recall exists.** If yes, set `reversible: true` and name it in the `agentHint`. If no, set `reversible: false` and consider whether one *should* exist.
6. **Define `inputSchema` tightly.** Required fields really required; enums where possible; descriptions on every property. The schema is the agent's only structured guide to the call.
7. **Set `requiredFeature` if the command is licensed.** Agents will only see it in entitled tenants.
8. **Pick the permission code with care.** It is what gates the catalog. Over-broad permissions on a high-risk command quietly increase the agent's surface.
9. **Add an example payload.** `exampleInput` is consumed by the tool provider and by humans reading the command in the studio.
10. **Run a dry agent invocation.** Open Aura Bot, ask for the action in natural language, and confirm the agent finds and calls the command. If it doesn't, the `agentHint` is wrong.

If a command passes all ten checks, it is callable safely by humans, by Aura Bot, by an Agent Builder agent, by an external MCP client, and by an ACP peer — without any additional plumbing per channel.

## Next steps

- [Command Pipeline](/docs/en/core-concepts/command-pipeline) — the 20-stage execution contract that every agent call traverses.
- [Permissions](/docs/en/core-concepts/permissions) — the three layers an agent inherits from its user.
- [MCP and Tools](/docs/en/ai/mcp-and-tools) — how commands are exposed as MCP tools to external clients.
- [ACP Protocol](/docs/en/ai/acp-protocol) — agent-to-agent calls between AuraBoot deployments.
- [Agent Builder](/docs/en/ai/agent-builder) — author in-product agents that use this contract.
- [Aura Bot](/docs/en/ai/aura-bot) — the platform's default agent that already runs under this contract.
