---
type: design
status: draft
created: 2026-06-21
topic: cs-reply-tool-boundary
related:
  - docs/superpowers/specs/2026-06-21-pluggable-agent-eval-capabilities-design.md
  - docs/plugin-development/agent-capabilities-in-plugins.md
  - scripts/check-agent-eval-boundary.mjs
---

# Design — Remove the hardcoded `crm:create_activity` from OSS core (`SendCustomerReplyToolHandler`)

## Background

The "agent-eval OSS boundary" work established the principle: **OSS core = AI *mechanism*, business = *content* (lives in plugins, injected on demand).** A boundary linter (`scripts/check-agent-eval-boundary.mjs`) now scans `framework/agent` + `framework/rag` for **quoted full business command codes** (`crm:*`, `qc:*`, `iot_*:*`, …) baked into core.

That linter surfaced exactly one real violation, left as the sole open backlog item from the previous session: `SendCustomerReplyToolHandler` — a `@Component` in OSS core that hardcodes `commandExecutor.execute("crm:create_activity", …)`. It is currently neutralized by a `// boundary-allow` comment (gate green, debt tracked).

## The user scenario (what this code actually does)

A company runs a **Customer Service AI agent** (`cs_agent`, seeded by `scripts/seed-cs-agent.sql`). Flow:

1. Inbound customer email → `InboundEmailEvent` → `CustomerServiceAgentListener` runs `cs_agent`.
2. Agent looks up the contact/account, files a **CRM complaint**, investigates.
3. Agent drafts a reply and calls the **`send_customer_reply`** tool.
4. The tool is **L2 risk** → blocked behind a human **approval gate**.
5. On approval, the tool **sends the actual email**, logs it, and (best-effort) records a CRM activity on the complaint.

The agent already orchestrates the entire CRM flow through commands in its system prompt: `cmd:crm:create_complaint`, `cmd:crm:investigate_complaint`, `cmd:crm:resolve_complaint`, `cmd:crm:close_complaint`. The inline "create activity" buried inside the core tool handler is **the one CRM action the agent does *not* drive** — it is the odd one out.

## The problem (precisely)

`SendCustomerReplyToolHandler.execute()` does three things:

| Step | Action | Nature |
|---|---|---|
| ① | `emailSender.send(...)` | **The entire point of the tool** — a platform notification mechanism (`framework/notification`) |
| ② | insert into `ab_notification_send_log` | platform notification mechanism |
| ③ | `commandExecutor.execute("crm:create_activity", …)` (try/catch, `log.warn` on failure) | **best-effort** CRM business action |

The boundary linter flags **only step ③** (the quoted business command code). Steps ①–② are legitimate core mechanism.

### Why the original "path B" is wrong

The previous handover recommended *"delete the handler, have the agent use `dsl.command → crm:create_activity`."* Verified against the code, this **destroys the feature**:
- The tool's core purpose is sending email; `crm:create_activity` is a best-effort side note.
- There is **no email-sending `dsl.command`** anywhere — `EmailSender` is used *only* by this handler.
- Deleting the handler removes email send + send-log + approval-gate keying → the customer never receives a reply.

The original plan conflated "this tool *contains* one CRM line" with "this tool *is* a CRM thing." It is not — it is an email tool with one CRM line appended.

### Supporting finding

The handler's hardcoded activity payload (`crm_act_description`, `crm_act_status`) **no longer matches** the current CRM activity model, which stores status in `crm_act_ext` (jsonb) and links via `crm_act_related_model` / `crm_act_related_id`. So the inline activity creation has likely been silently drifting / partially broken. Routing it through the real `crm:create_activity` command (whose `inputFields` are surfaced to the agent as a tool schema) is *more* correct, not less.

## Chosen approach — 1b: decouple, keep email in core

Remove the only flagged coupling; keep email send + log in core (where a platform notification mechanism belongs); hand the CRM-activity step to the agent, which already drives every other CRM command.

Considered and rejected:
- **1a** (parameterize a generic "post-send command" passed via config) — preserves determinism but adds a one-use generic mechanism to core (YAGNI).
- **2 / path A** (crm → hybrid plugin + PF4J `ToolProvider` provides the whole tool) — "purest" but heavy (crm→hybrid, IT + E2E rework, PF4J golden), and wrongly evicts email-sending (a platform mechanism) from core.
- **3 / path C** (keep `boundary-allow`) — no work, debt remains.

## Changes

### 1. Core tool slim-down — `platform/.../framework/agent/tool/SendCustomerReplyToolHandler.java`
- Delete `createReplyActivity(...)` and its `commandExecutor.execute("crm:create_activity", …)` call.
- Remove the `CommandExecutor` + `CommandExecuteRequest` dependencies/imports.
- Remove the `// boundary-allow` comment (no longer needed — no business command code remains).
- Keep: send email (`EmailSender`) + log to `ab_notification_send_log`. The tool becomes a pure "send reply email + record send-log" notification capability.
- Return contract unchanged (`{success, message}` / `{success:false, error}`).

### 2. Hand activity-logging to the agent — `scripts/seed-cs-agent.sql`
- Add `cmd:crm:create_activity` to the agent's `tools` list.
- Append to the REPLY step (step 6) of the system prompt: after the reply is sent, record a "reply sent" activity on the complaint via `cmd:crm:create_activity`, with fields aligned to that command's actual `inputFields` (verify mapping at implementation time against `plugins/crm/config/commands/crm_activity_common.json`; link to the complaint via the activity's relation fields).
- This mirrors the existing `cmd:crm:create_complaint/investigate/resolve/close` instructions — structurally identical.

### 3. Tests
- `CustomerServiceAgentIntegrationTest` (live LLM): sync the inline system prompt + tools list; **assert `EmailSender.send` is still invoked after approval** (proves email path intact). Activity creation stays diagnostic (real-LLM nondeterminism).
- E2E `web-admin/tests/e2e/cs-agent/cs-agent-email-lifecycle.spec.ts`: sync the inline prompt/tools list if present.
- Handler unit test: assert "send + log, no command executor" (add/adjust; verify `CommandExecutor` is no longer a collaborator).
- Boundary gate: confirm `check-agent-eval-boundary.mjs` is green **without** the `boundary-allow` exemption.

### 4. Acceptance (real-stack + UI, host-first, zero docker)
- CS-agent IT with live LLM (`DEEPSEEK_API_KEY`).
- E2E golden `cs-agent-email-lifecycle.spec.ts` + screenshot (UI-bearing feature — backend-only is insufficient per AGENTS §2).
- `check-agent-eval-boundary.mjs` + `check-oss-boundary.sh` green.
- Isolated `dev.sh runtime`: reset + seed → exercise send + approval + activity-on-complaint full chain.

## Out of scope (YAGNI)
- Converting crm to a hybrid plugin.
- A generic core "post-send command" mechanism.
- Renaming the tool code (would ripple into seed / IT / E2E).

## Risks
- **Activity logging becomes LLM-driven** (was inline). Mitigation: it was already best-effort; nothing asserts it; the agent already reliably drives 5+ other CRM commands the same way.
- **Live-LLM test flakiness** — handled the same way the existing CS-agent IT already handles it (diagnostic logging, status-set assertions), not by weakening the email-send assertion.
