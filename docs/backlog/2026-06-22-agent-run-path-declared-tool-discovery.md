---
created: 2026-06-22
type: backlog
status: active
area: framework/agent
---

# Agent dispatch run path ignores the agent's declared cross-model tools

## Context

Follow-up to the DeepSeek multi-tool fix (one role:tool message per tool_call_id). With that
fixed, a seeded `cs_agent` on `deepseek-chat` now runs multi-tool conversations and successfully
calls `custom:send_customer_reply` end-to-end (verified: `ab_notification_send_log` rows with
status `sent`). But it still cannot call `cmd:crm:create_activity`.

## Symptom

`PlanService` clears it as hallucinated and the run aborts:

> Plan validation: step 1 references non-existent tool 'cmd:crm:create_activity', clearing
> Hallucination circuit breaker triggered: tool=cmd:crm:create_activity, count=3

It happens even when `cmd:crm:create_activity` is the *only* declared command tool, so it is not
the tool-selector dropping it — the tool is never discovered at all.

## Root cause

`DslToolProvider.discover()` is model-scoped: it requires a single `modelHint` and only returns
commands `WHERE model_code = :modelHint` (returns nothing without a hint).

- The **chat** path (`AgentChatToolDiscoveryAdapter.discoverExplicitAgentTools`) resolves a model
  hint *per declared tool* (`resolveExplicitToolModelHint` → `loadCommandModelCode`) and unions the
  discoveries, so a declared `cmd:crm:create_activity` (model `crm_activity_common`) is found.
- The **dispatch/run** path (`AgentRunService`, around line 318) sets a single
  `modelHint = bif.getObject()` from grounding the task text (e.g. `crm_complaint`). It never
  consults the agent's declared tools. So commands on any *other* model — like
  `crm_activity_common` for `crm:create_activity` — are never discovered.

So the run path's available-tool set depends only on what the task text grounds to, not on what the
agent declared. Cross-model agents lose tools.

## Fix direction

In `AgentRunService`, make declared tools always available: after building the bif-based tool set,
also resolve each explicitly-declared tool code (agent `tools` column) to its model hint and
discover it, then merge any missing ones (dedupe by name). Reuse the chat path's logic
(`resolveExplicitToolModelHint` / `loadCommandModelCode`) rather than duplicating — e.g. extract a
shared `DeclaredToolResolver` used by both paths. The merge must be additive (only ever adds
declared tools) to keep the change low-risk. Mind `AgentToolDefinition.name` vs the raw command
code and the OpenAI tool-name sanitization when deduping.

## Verification when fixed

Host-first isolated stack + crm imported + seeded `cs_agent` (deepseek-chat) → dispatch an
inbound-email task → the agent reaches `cmd:crm:create_activity` and an
`mt_crm_activity_common` "Agent Reply" row is written (linked to the complaint). Recipe: this
session's cs-gold2 stack on slot 72.

## Also surfaced (separate, lower priority)

- The tool-selector (`d1`) caps the per-run tool set (~9), which can drop declared tools on
  multi-tool agents even when they are discovered.
- `deepseek-chat` reliability on the full 9-step cs_agent prompt is weak — it improvises with
  `platform.execute_sql` instead of the `crm:` commands and loops out. A constrained prompt /
  stronger model is needed for a deterministic full-flow run; this is model quality, not a bug.
