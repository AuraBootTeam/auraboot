---
created: 2026-06-22
type: backlog
status: closed
area: framework/agent
distilled_to:
  - docs/core-concepts/agent-readiness.md
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

## Resolution

Fixed in `fix/agent-run-declared-tools`.

- `DeclaredAgentToolResolver` is now the shared resolver for declared agent tools. It resolves each
  explicit tool with its own provider/model hint and merges the resulting tool definitions
  additively into the run path's grounded tool set.
- `AgentRunService` now includes explicitly declared cross-model tools in dispatch/run tool
  discovery, deduped by the effective tool name.
- `CustomToolProvider.discover()` now includes `input_schema`, `requires_approval`, and
  `risk_level`, so custom tools are discovered with their real approval/schema metadata.
- Generic `get:` / `list:` DSL read tools now execute through the provider registry, not the named
  query path.
- Approval pause/resume now preserves the exact approved tool input and replays that input after
  approval instead of asking the LLM to regenerate the step.

## Follow-up resolution: declared tool cap

Fixed on 2026-06-23 in `codex/ci-smartengine-cleanup`.

The earlier fix merged declared tools after grounded discovery, but one lower-level edge remained:
`ToolProviderRegistry.discoverAll(ctx)` and provider-specific SQL can cap discovery before a
declared `custom:` / provider-prefixed tool appears. `DeclaredAgentToolResolver` now treats declared
tools as pinned:

- `custom:` tools are loaded directly from `ab_agent_tool` by exact `tool_code`, preserving schema,
  approval, risk, source, and description metadata.
- provider-prefixed tools without a direct table lookup, such as `platform.`, `mcp:`, and
  `aurabot:`, fall back to provider-specific discovery instead of the globally capped aggregate
  catalog.
- Regression tests cover a declared `custom:send_customer_reply` missing from the first 100
  aggregate-discovered tools and a declared `platform.delegate_task` recovered by provider-specific
  discovery.

## Follow-up resolution: planning prompt tool choice

Fixed on 2026-06-23 in `codex/ci-smartengine-cleanup`.

The run-path planning prompt previously exposed tools as a comma-separated code list only. We now
render a structured tool catalog with each tool's code, type, risk, approval/confirmation marker, and
description. The planning prompt also explicitly requires exact `toolCode` values, prefers
purpose-built DSL/query/custom tools, and reserves `platform.execute_sql` for cases where no listed
DSL/query/custom tool can satisfy the task. This is a deterministic prompt-contract hardening for
DeepSeek's tendency to pick generic SQL over declared CRM/custom tools; it is not a substitute for a
fresh live-model gold run.

## Gold evidence

Host-first isolated stack `cs-inbound-gold-77` (backend `6477`, DB `auraboot_77`) verified the full
support-agent path on `deepseek-chat`:

- inbound event `01KVQ67W9WATQ9DK35AT894AC7`
- original run `01KVQ67WA6W89R3DXR0NJWYGTZ` paused for approval
- approval `01KVQ689497S675WW20NBVP8T2` approved by `327350679712698368`
- resumed run `01KVQ68T789G0218TJMPZWX73M` completed with status `success`
- `ab_notification_send_log` row `id=1`, `status=sent`, subject `Re: Gold inbound approval flow 77e`
- `ab_agent_action` recorded both `custom:send_customer_reply.provider` and `crm_activity.create`
  as `success`
- `mt_crm_activity` row `id=4`, pid `01KVQ6918WMPGPJWE5WTZDG6D9`, type `email`, subject
  `Agent Reply: Gold inbound approval flow 77e`

The isolated runtime was destroyed after verification.

## Verification

- `bash scripts/check-reset-init-contracts.sh`
- `node scripts/check-agent-eval-boundary.mjs`
- `bash scripts/check-oss-boundary.sh`
- `git diff --check`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.provider.CustomToolProviderTest --tests com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest --tests com.auraboot.framework.agent.service.DeclaredAgentToolResolverTest --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest --no-daemon`
- `cd platform && ./gradlew :test --tests com.auraboot.framework.agent.CustomerServiceAgentIntegrationTest --no-daemon`
- `cd platform && ./gradlew bootJar -x test --no-daemon`

## Remaining live-model caveat

- `deepseek-chat` reliability on the full 9-step cs_agent prompt is still model-dependent. The
  prompt now strongly biases toward declared DSL/custom tools and away from generic SQL, but a fresh
  live-model run is required before claiming deterministic full-flow reliability.
