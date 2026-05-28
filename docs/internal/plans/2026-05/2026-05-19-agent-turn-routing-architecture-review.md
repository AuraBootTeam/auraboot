# Agent Turn Routing Architecture Review

Date: 2026-05-19

Status: Architecture review, implementation blueprint, and current branch progress

Related plan:

- `docs/superpowers/plans/2026-05-19-agent-12factor-runtime-state.md`

## Implementation Progress

2026-05-19:

- Added shared `ToolLoopResultNormalizer` and replaced local tool-loop result parsers in `AgentChatPortImpl`, `AuraBotChatService`, and `ChatToolExecutor`.
- Added architecture coverage to prevent `parseToolLoopResult` / `normalizeToolLoopResult` from returning outside the shared normalizer.
- Added shared `LlmRuntimeResolver` for agent-definition provider/model resolution and replaced duplicated logic in `AgentRunService`, `AgentChatPortImpl`, and `StepLoopService`.
- Added architecture coverage to prevent private agent-definition `resolveProviderCode` / `resolveModel` implementations from returning outside the shared resolver.
- Removed the stale `AgentRunService.attemptReplan(...)` provider-call implementation after replan ownership moved to `StepLoopService`, and added architecture coverage that keeps `AgentRunService` as run/task orchestration rather than a step-loop LLM caller.
- Made `AgentRunService` require `AgentRuntimeStateFactory` through constructor injection instead of silently falling back to `new AgentRuntimeStateFactory()`, with architecture coverage preventing that fallback from returning.
- Added `PendingContinuationService` as the shared chat pending-continuation boundary and wired `ConversationTurnServiceImpl.resumeTurn` through it instead of directly calling AuraBot resume internals.
- Added `AgentTurnRouter` as the deterministic router for `CHAT_TURN`, `DURABLE_RUN`, and `NAMED_AGENT_CHAT` runtime selection, then wired `ConversationTurnServiceImpl.runTurn` through it.
- Moved the approved pending-tool continuation implementation out of `AuraBotChatService` into `AuraBotPendingContinuationService`; `AuraBotChatService` now stays on the light chat stream path and no longer owns `resumeApprovedTurnFromPending`.
- Added architecture coverage that prevents `AuraBotChatService` from regaining pending continuation entrypoints.
- Added shared `LlmChatRuntimeSupport` for generation trace payloads and offered-tool checks, so pending continuation no longer depends on `AuraBotChatService` internals and `AuraBotChatService` no longer exposes those shared helpers as its own API surface.
- Added shared `LlmMessageTapeSupport` for assistant message construction, tool-result message construction, server-side tape serialize/deserialize, and final response text extraction. `AgentChatPortImpl` and `AuraBotPendingContinuationService` now share this layer instead of carrying duplicate private helpers.
- Added architecture coverage that prevents chat adapters from reintroducing private LLM message tape helper implementations outside `LlmMessageTapeSupport`.
- Preserved typed AuraBot Skill confirmation failures such as `PREVIEW_TOKEN_INVALID` in the safe tool-result envelope, so continuation callers see a recoverable token cause instead of a generic `Tool execution failed.` message.
- Final diff review hardening: pending continuation now treats null/empty LLM responses as the intended `Empty response from LLM` failure before trace token accounting, and chat tool executor exception paths now sanitize returned/logged error text without letting multi-line secret values swallow the next key.

2026-05-20:

- Removed the owner-blind pending consume API from `PendingToolStore`; runtime callers now use `consumePendingForOwner(...)`, and architecture coverage prevents the unsafe API from returning.
- Upgraded `AgentTurnRouter` from bucket-only routing to typed policy input. `CONTEXTUAL_ANSWER` now routes to chat only when pre-grounding triage supplied an explicit read-only tool allowlist; ownerless/precomputed contextual buckets fail closed to ACP durable runtime.
- Propagated `allowedReadOnlyTools` through `TurnContext` so `ConversationTurnServiceImpl` can make routing decisions from explicit policy metadata instead of coarse triage labels.
- Added chokepoint coverage for read-only contextual triage: normal web triage with page context routes to AuraBot chat runtime, not ACP durable run.
- Moved named-agent chat round-loop control flow into `ChatTurnRuntime.runToolLoop(...)`. `AgentChatPortImpl` is now a named-agent adapter for agent definition loading, tool discovery, tool execution callbacks, pending snapshot storage, and handoff/approval outcome shaping; it no longer owns the `for round` LLM/tool-loop itself.
- Added architecture coverage that prevents `AgentChatPortImpl` from re-owning chat tool-loop control flow and requires named-agent chat to delegate the loop to `ChatTurnRuntime`.
- Removed the default `new AgentRuntimeStateFactory()` fallback from `PendingToolSnapshotFactory`; runtime-state snapshot creation now requires an injected collaborator, with architecture coverage preventing the fallback from returning.
- Moved approved chat pending-continuation follow-up rounds into `ChatTurnRuntime.runToolLoop(...)`. `AuraBotPendingContinuationService` still owns the initial confirmed tool execution and AuraBot-specific pending snapshot adaptation, but no longer owns a private LLM/tool round loop.
- Extended `ChatTurnRuntime.runToolLoop(...)` with transport-neutral callbacks for provider trace spans, warnings, provider-failure message shaping, final trace completion, and exhausted-loop handling. This keeps pending resume trace semantics without making the shared runtime depend on the trace package.
- Added behavior coverage for approved pending resume where the follow-up LLM emits another `tool_use`; the test verifies the shared loop executes the stored read-only tool snapshot and then completes the final response.
- Added architecture coverage that prevents `AuraBotPendingContinuationService` from reintroducing a private chat round loop.
- Moved AuraBot light chat provider streaming into `ChatTurnRuntime.streamProviderResponse(...)`. `AuraBotChatService` now prepares provider config, prompt, history, and trace context, then delegates chunk streaming, `<think>` filtering, warning forwarding, thinking-block emission, final text extraction, and terminal sink events to the shared runtime.
- Added architecture coverage that prevents `AuraBotChatService` from re-owning provider streaming loop details such as direct `provider.streamChat(...)`, `ReasoningTagSanitizer`, or private thinking-block emission.
- Added direct `ChatTurnRuntime` behavior coverage for streamed provider responses, including split reasoning tag filtering, aggregate warnings, aggregate thinking blocks, final response, trace id, and provider transport arguments.
- Hardened ACP durable run provider resolution: `AgentRunService` now treats a configured provider without a registered runtime provider bean as a fail-closed setup error before task loading, planning, or step-loop execution.
- Added `AgentRunServiceSyncTest` coverage for provider-config-present/provider-bean-missing so this path cannot silently pass a null provider into `StepLoopService`.
- Centralized frontend-history/current-user message construction in `LlmMessageTapeSupport`. AuraBot light chat and named-agent chat now share the same filtering and append semantics: stored server tape wins when present, `system` history is excluded, and the current user message is appended at the tail.
- Added architecture coverage that prevents `AuraBotChatService` and `AgentChatPortImpl` from reintroducing private text-message construction logic.

Verification:

- `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.LlmMessageTapeSupportTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest`
- `./gradlew :test --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.aurabot.service.ChatToolExecutorCanonicalRuntimeTest --tests com.auraboot.framework.common.util.LogSanitizerTest`
- `./gradlew :test --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.integration.aurabot.LlmWarningsSseIntegrationTest --tests com.auraboot.framework.aurabot.service.AuraBotChatSkillResumeIntegrationTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest --tests com.auraboot.framework.agent.service.ToolLoopServiceSafetyTest`
- Broad agent runtime targeted suite covering router, resolver, normalizer, chat port, run service, step loop, tool loop, chat tool executor, conversation dispatch/finalize/resume, and log sanitizer.
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentTurnRouterTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest --tests com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest`
- `./gradlew :test --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffSignalTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffRealSchemaTest --tests com.auraboot.framework.agent.service.AgentChatPortImplOverridesTest --tests com.auraboot.framework.agent.service.AgentChatPortImplExtraToolsTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`
- `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.agent.runtime.PendingToolSnapshotFactoryTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.integration.aurabot.LlmWarningsSseIntegrationTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentTurnRouterTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest --tests com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.integration.aurabot.LlmWarningsSseIntegrationTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffSignalTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffRealSchemaTest --tests com.auraboot.framework.agent.service.AgentChatPortImplOverridesTest --tests com.auraboot.framework.agent.service.AgentChatPortImplExtraToolsTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`
- `./gradlew :compileJava :compileTestJava`
- `./gradlew :test --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceGroundingTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceTracePayloadTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceThinkingIntegrationTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentTurnRouterTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest --tests com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.integration.aurabot.LlmWarningsSseIntegrationTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffSignalTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffRealSchemaTest --tests com.auraboot.framework.agent.service.AgentChatPortImplOverridesTest --tests com.auraboot.framework.agent.service.AgentChatPortImplExtraToolsTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`
- `./gradlew :test --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentTurnRouterTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpDispatchTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplNamedAgentTaskTest --tests com.auraboot.framework.aurabot.service.ChatSessionStoreReliabilityTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.integration.aurabot.LlmWarningsSseIntegrationTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffSignalTest --tests com.auraboot.framework.agent.service.AgentChatPortImplHandoffRealSchemaTest --tests com.auraboot.framework.agent.service.AgentChatPortImplOverridesTest --tests com.auraboot.framework.agent.service.AgentChatPortImplExtraToolsTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.LlmMessageTapeSupportTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest`
- `./gradlew :test --tests com.auraboot.framework.agent.runtime.LlmMessageTapeSupportTest --tests com.auraboot.framework.architecture.AgentRuntimeArchitectureTest --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest --tests com.auraboot.framework.agent.service.AgentChatPortImplOverridesTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceGroundingTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceTracePayloadTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceThinkingIntegrationTest --tests com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest`

Environment note: `AuraBotChatSkillResumeIntegrationTest` requires the `skills-c2-test` isolated Postgres/Redis stack on ports `25442` and `26389`. The first run failed with `Connection to localhost:25442 refused`; after starting `auraboot-skills-c2`, the test passed. The stack was stopped after verification.

## Executive Summary

This document records the architecture discussion that started from an AuraBot runtime failure and expanded into a broader review of AuraBoot Agent execution boundaries.

The immediate production symptom was `Invalid scheme [stub]` when AuraBot handled "统计客户信息" on `http://localhost:5226`. The first fix pass addressed concrete runtime defects: stub provider config escaping into real provider execution, fallback behavior, pending runtime state, error compaction, and log sanitization. After those fixes, the deeper issue became visible: AuraBoot still has multiple partially overlapping Agent execution implementations.

The long-term direction is:

```text
ConversationTurnService
  -> AgentTurnRouter
      -> ChatTurnRuntime
      -> DurableRunRuntime / AcpRunOrchestrator

Shared runtime layer:
  -> LlmRuntimeResolver
  -> ToolLoopService
  -> ToolLoopResultNormalizer
  -> AgentRuntimeStateFactory / reducer
  -> AgentToolDiscoveryService
  -> PendingContinuation model
  -> Error compaction / observability / guardrails
```

The key decision is not to keep three large, parallel services (`AgentRunService`, `AgentChatPortImpl`, `AuraBotChatService`) as independent runtimes. Instead, AuraBoot should keep one conversation chokepoint, one deterministic router, two lifecycle-specific runtimes, and shared infrastructure.

Routing must not be finally delegated to the LLM. The LLM may classify intent or propose tool calls, but code must decide whether a turn is a normal chat turn or a durable ACP run. Side effects, approvals, resumability, and auditability are platform control flow, not probabilistic model behavior.

## How We Got Here

### 1. Initial runtime failure

The immediate bug was an AuraBot path failing with:

```text
Invalid scheme [stub]
```

The reported user action was:

```text
http://localhost:5226 aurabot 统计客户信息
```

The failure implied that a stub provider configuration or stub URL could reach a path that expected a valid provider transport. That is a low-level runtime contract failure: test/stub wiring must be confined to no-LLM paths or explicit stub providers, not leak into real HTTP provider calls.

### 2. Test and gate concerns

The follow-up question was whether this kind of issue came from missing integration tests or broken E2E coverage. The answer was: both categories matter, but the root cause is architectural. When provider resolution, fallback, tool loop normalization, pending resume, and response finalization live in multiple places, tests can cover one path while another path diverges silently.

The discussion then moved through:

- targeted and slice verification for regular and deep gates;
- excluding Page Designer from the current test scope;
- running focused QueryBuilder / View Management / showcase widget tests;
- fixing review findings;
- checking whether `AgentRunService` is obsolete;
- comparing `AgentRunService` with `ConversationTurnService`;
- reviewing whether Agent architecture still has fallback, multiple implementations, and responsibility duplication.

### 3. Concrete fixes already made in this branch

The current worktree already contains code fixes and tests for the highest-risk issues found during review:

- `ConversationTurnServiceImpl`
  - Failed turn outcomes now persist outbound messages before emitting completion events.
  - ACP runtime dispatch now fails closed if `AgentRunService` or `DynamicDataMapper` is missing instead of falling back to legacy chat.
- `AgentChatPortImpl`
  - Tool loop errors are compacted into user-safe `AgentErrorFrame` style payloads.
  - Runtime state now records effective `toolChoice` and effective prompt metadata.
  - Provider/tool exception logs are sanitized.
- `ToolLoopService`
  - Error paths use sanitized output.
- `LogSanitizer`
  - Secret redaction now covers common token/password/authorization fields.
- Tests were added or extended for:
  - failed turn persistence;
  - ACP missing-runtime fail-closed behavior;
  - named-agent tool error compaction;
  - runtime state snapshots;
  - log redaction.

These fixes reduce the immediate blast radius, but they do not fully solve the larger architecture problem.

## Current AuraBoot Architecture Findings

### Finding 1: Multiple LLM/tool loops remain

Current code still has more than one place that can own provider calls and lifecycle behavior:

- `ChatTurnRuntime` now owns named-agent chat loop control flow; `AgentChatPortImpl` still owns named-agent preparation and callback adaptation.
- `AuraBotChatService` owns AuraBot light chat behavior.
- `AuraBotPendingContinuationService` owns initial approved pending-tool execution and AuraBot-specific snapshot adaptation, while follow-up chat rounds now run through `ChatTurnRuntime`.
- `StepLoopService` owns ACP step execution loops.
- `AgentRunService` owns durable run/task orchestration; durable step execution remains split with `StepLoopService`.

The remaining risk is semantic drift between chat turn lifecycle, AuraBot light chat, and durable ACP workflow lifecycle. Named-agent chat and approved pending continuation are materially better now because their central round loops moved into `ChatTurnRuntime`, but AuraBot light chat and durable ACP execution still need more convergence around the shared runtime surface.

### Finding 2: Provider resolution must stay centralized

Provider/model resolution existed in more than one service. This is why stub/provider/fallback bugs are easy to reintroduce. The current branch centralizes the agent-definition provider/model rules in `LlmRuntimeResolver`, but the architectural rule remains: provider selection should be a shared runtime concern with a typed result:

```text
requested provider
resolved provider
model
API mode
config reference
provider bean
fallback chain
resolution audit
user-safe failure
```

### Finding 3: Tool result normalization must stay centralized

Tool outputs and tool errors were parsed in multiple places. That creates inconsistent model context and inconsistent user-visible behavior. The current branch moves this to `ToolLoopResultNormalizer`; future runtime work should keep failed tools from returning as raw strings, ad hoc JSON, full exceptions, or differently shaped compact error frames depending on the path.

### Finding 4: ACP durable runtime does not yet have full 12-factor runtime-state parity

Named-agent chat now records runtime state snapshots more consistently, but ACP step execution still needs stronger parity: context manifest, provider resolution audit, tool schema manifest, reducer/replay semantics, approval state, and compact error frames should be first-class across durable runs too.

### Finding 5: AuraBot pending resume needed its own runtime boundary

Earlier in the review, `ConversationTurnService.resumeTurn` was the chokepoint but the approved pending path still delegated into `AuraBotChatService.resumeApprovedTurnFromPending`. That made AuraBot service both a user-facing light chat service and a pending tool continuation engine.

Long term, pending continuation should be a runtime concept, not a private AuraBot method.

Current branch status: this finding has been addressed for the chat pending-tool path. `ConversationTurnServiceImpl.resumeTurn` now calls `PendingContinuationService`, and the concrete AuraBot implementation lives in `AuraBotPendingContinuationService`. The remaining long-term work is to converge the pending model with ACP approval/run continuation so both chat confirmations and durable approval gates share consistent typed state, observability, and replay semantics.

### Finding 6: Triage policy must be explicit

Routing cannot rely on `null`, missing SPI, or best-effort fallback. Absence of triage or ACP runtime wiring should fail closed for ACP/action paths, and the default behavior must be visible as policy.

## Why Three Large Services Are the Wrong Long-Term Shape

The three current service names are useful as historical entry points, but they should not remain as three large runtime implementations:

```text
AgentRunService
AgentChatPortImpl
AuraBotChatService
```

The issue is not the number three by itself. The issue is that each class currently overlaps runtime responsibility:

- provider/model resolution;
- prompt/message assembly;
- tool discovery;
- tool loop execution;
- tool result parsing;
- pending continuation;
- runtime state;
- error handling;
- persistence/audit/event behavior.

Keeping these as independent runtimes would make every safety fix require three implementations and three test suites. That is exactly how low-level bugs like `Invalid scheme [stub]` survive.

## Target Architecture

### Chokepoint

`ConversationTurnService` remains the single required entry point for:

- `/chat/stream`;
- `/execute`;
- IM @AI;
- group agent replies;
- ACP run;
- pending resume.

It owns turn lifecycle, persistence, finalization, sink wiring, and cross-channel event semantics. It must not contain provider/tool loop details.

### Router

`AgentTurnRouter` decides which runtime owns the turn. It should be a small, deterministic policy component.

It should inspect:

- explicit intent bucket: `LIGHT_CHAT`, `CONTEXTUAL_ANSWER`, `ACP_RUN`, `ACTION_TASK`;
- request entrypoint;
- pending type for resume;
- whether the turn requires durable lifecycle;
- whether the tool/action has side effects;
- whether approval, checkpoint, heartbeat, replay, or audit are required;
- platform policy and capability metadata.

It should not ask the LLM to choose the final runtime.

### ChatTurnRuntime

`ChatTurnRuntime` owns normal conversation turns:

- greetings and general answers;
- current-page explanation;
- record/customer/order summaries;
- read-only tool lookup;
- named-agent conversational turns;
- small confirmed tool actions that can complete within the current turn, if policy allows them.

Its primary state is:

```text
turn
message tape
prompt/context manifest
tool calls/results
pending chat confirmation
final answer
```

This runtime should absorb most of the named-agent chat loop currently in `AgentChatPortImpl` and the light chat parts of `AuraBotChatService`.

### DurableRunRuntime / AcpRunOrchestrator

`DurableRunRuntime` owns governed tasks:

- ACP task/run/plan execution;
- multi-step workflows;
- write/batch/external side effects;
- approval gates;
- checkpoint/resume;
- heartbeat/timeouts;
- audit/action logs;
- replay and run inspection.

Its primary state is:

```text
run
task
plan
step
approval
checkpoint
resume token
run events
audit trail
```

This is the long-term replacement shape for the durable parts of `AgentRunService`, not a generic chat service.

### Shared Runtime Layer

The two runtimes must share infrastructure:

- `LlmRuntimeResolver`
- `ToolLoopService`
- `ToolLoopResultNormalizer`
- `AgentToolDiscoveryService`
- `AgentRuntimeStateFactory`
- reducer/replay primitives
- `PendingContinuation` model
- error compaction
- guardrails
- tracing/metrics

The principle is:

```text
Unify the protocol and infrastructure.
Separate the lifecycle implementations.
```

## Real Scenarios

### Scenario A: Read-only customer summary

User says:

```text
帮我总结这个客户最近 3 个月的跟进情况。
```

This should route to `ChatTurnRuntime`.

Reason:

- it is a current-page/contextual answer;
- it may query records, but does not write;
- it should complete in the current turn;
- it does not need plan/step/checkpoint/approval/audit lifecycle.

Flow:

```text
ConversationTurnService
  -> AgentTurnRouter
  -> ChatTurnRuntime
      -> build context from current customer
      -> call read-only tool / named query
      -> summarize with LLM
      -> stream answer
      -> finalize turn
```

### Scenario B: Batch task creation with approval

User says:

```text
把最近 30 天没有跟进记录、金额大于 10 万的客户找出来，生成跟进任务，分配给对应销售，并提醒我审批后执行。
```

This should route to `DurableRunRuntime`.

Reason:

- it is multi-step;
- it writes business data;
- it needs approval before execution;
- it may run long enough to require resume;
- it must be auditable.

Flow:

```text
ConversationTurnService
  -> AgentTurnRouter
  -> DurableRunRuntime
      -> create run
      -> generate plan
      -> step 1: query customers
      -> step 2: prepare task list
      -> approval: wait for user decision
      -> resume
      -> step 3: create tasks
      -> step 4: write action/audit logs
      -> complete run
```

### Scenario C: Single follow-up task

User says:

```text
给这个客户创建一个明天下午 3 点的跟进任务。
```

This is a boundary case.

If product policy allows small, single-record writes inside chat after confirmation, it can route to `ChatTurnRuntime`:

```text
ChatTurnRuntime
  -> propose create_followup_task tool
  -> create pending confirmation
  -> user approves
  -> ConversationTurnService.resumeTurn
  -> ChatTurnRuntime.resume
  -> execute single tool
  -> finalize turn
```

If the action requires approval workflow, batch handling, external side effects, or audit/run replay, it must upgrade to `DurableRunRuntime`.

The policy should be explicit. Do not let the LLM decide this boundary freely.

## Routing Policy

`AgentTurnRouter` should use deterministic rules.

Recommended rule order:

```text
1. Resume type wins.
   ACP approval / ACP checkpoint -> DurableRunRuntime.resume
   chat tool confirmation -> ChatTurnRuntime.resume

2. Explicit entrypoint wins.
   ACP_RUN / ACTION_TASK / execute durable task -> DurableRunRuntime.start
   LIGHT_CHAT -> ChatTurnRuntime.run

3. Capability policy wins.
   write/delete/batch/external side effect -> DurableRunRuntime or approval gate
   read-only query/summarization -> ChatTurnRuntime

4. Lifecycle requirement wins.
   needs plan/checkpoint/heartbeat/audit/replay -> DurableRunRuntime
   completes inside current turn -> ChatTurnRuntime

5. Triage output is advisory.
   LLM can produce structured intent, but code applies policy.
```

Example:

```java
if (ctx.isResume()) {
    return pending.type().isAcpRun()
            ? durableRunRuntime.resume(ctx, pending, sink)
            : chatTurnRuntime.resume(ctx, pending, sink);
}

if (request.intent() == ACP_RUN || request.intent() == ACTION_TASK) {
    return durableRunRuntime.start(ctx, request, sink);
}

if (policy.requiresDurableExecution(request)) {
    return durableRunRuntime.start(ctx, request, sink);
}

return chatTurnRuntime.run(ctx, request, sink);
```

## Should The LLM Decide Routing?

No, not finally.

The LLM may classify semantic intent:

```json
{
  "intent": "CREATE_FOLLOWUP_TASKS",
  "requiresWrite": true,
  "requiresApproval": true,
  "confidence": 0.87
}
```

But code should decide runtime:

```text
requires approval / writes / batch / replay -> DurableRunRuntime
read-only summary / contextual answer -> ChatTurnRuntime
```

Why:

- safety cannot depend on model sampling;
- audit and approval policy must be deterministic;
- tests need stable routing expectations;
- users should not get different runtime semantics for the same request wording;
- side effects require fail-closed behavior.

The recommended posture is:

```text
LLM suggests intent.
Code owns control flow.
Tool guardrails enforce side-effect boundaries.
```

## Industry References And Lessons

### OpenAI Agents SDK

Reference:

- https://openai.github.io/openai-agents-python/agents/
- https://openai.github.io/openai-agents-python/running_agents/
- https://openai.github.io/openai-agents-python/multi_agent/
- https://openai.github.io/openai-agents-python/handoffs/

Relevant pattern:

- `Agent` is configuration: instructions, tools, handoffs, guardrails, output types.
- `Runner` manages turns, tools, sessions, handoffs, and orchestration.
- The docs explicitly distinguish LLM-driven orchestration from code-driven orchestration.
- Code-driven orchestration is more deterministic and predictable.
- Handoffs are exposed to the model as tool-like choices, but the SDK still has a distinct handoff path.

Lesson for AuraBoot:

- Do not let every entrypoint own its own loop.
- Keep model intelligence inside bounded tool/handoff decisions.
- Keep platform-level runtime routing in code.

### LangGraph

Reference:

- https://docs.langchain.com/oss/python/langgraph/interrupts

Relevant pattern:

- Interrupt/resume is a first-class graph/runtime concept.
- Resume restarts a node from the beginning, so side effects must be carefully separated or cached.
- Durable workflows need checkpoint semantics, not just chat history.

Lesson for AuraBoot:

- ACP approvals and resumes belong to durable runtime.
- Tool execution before/after approval must be idempotent or tracked.
- The runtime must distinguish "paused before side effect" from "side effect already executed".

### Microsoft Agent Framework

Reference:

- https://learn.microsoft.com/en-us/agent-framework/workflows/checkpoints
- https://learn.microsoft.com/en-us/agent-framework/workflows/functional

Relevant pattern:

- Workflows support checkpoint storage and resume.
- Human-in-the-loop requests suspend workflow execution.
- Step-level caching avoids re-running expensive or side-effectful work on resume.
- Agent calls can be used inside workflows, but workflow lifecycle remains separate.

Lesson for AuraBoot:

- ACP run should be workflow-like.
- Normal chat turns can call tools, but durable business actions need run/step/checkpoint semantics.

### CrewAI

Reference:

- https://docs.crewai.com/en/concepts/flows

Relevant pattern:

- `Crew` and `Flow` are separate concepts.
- Flows provide structured, event-driven workflows with state and control flow.
- Agent collaboration is not the same as workflow lifecycle.

Lesson for AuraBoot:

- Named agent chat and ACP workflow should not be collapsed into one giant runtime.
- Keep workflow state explicit.

### HumanLayer 12-Factor Agents

Reference:

- https://www.humanlayer.dev/blog/12-factor-agents

Relevant pattern:

- Own your context window.
- Unify execution state and business state.
- Launch/pause/resume through simple APIs.
- Contact humans with tool calls.
- Own your control flow.
- Compact errors into the context window.
- Use small, focused agents.

Lesson for AuraBoot:

- The current runtime-state work is aligned with 12-factor agents.
- The next step is not another framework; it is making AuraBoot runtime state, routing, pause/resume, and errors explicit.

### Hermes Agent

Reference:

- https://hermes-agent.nousresearch.com/docs/developer-guide/architecture
- https://hermes-agent.nousresearch.com/docs/developer-guide/agent-loop/

Relevant pattern:

Hermes uses multiple entrypoints:

```text
CLI / Gateway / ACP / Batch / API
  -> AIAgent.run_conversation()
      -> prompt_builder
      -> runtime_provider.resolve_runtime_provider
      -> model call
      -> tool call loop
      -> session persistence / memory / compression
```

Its useful lesson is that CLI, gateway, ACP, and cron do not each implement their own independent agent loop. They share a core loop and shared provider/tool/session infrastructure.

Its warning is also important: the documented `AIAgent` core is very large and owns provider selection, prompt assembly, tool execution, retries, fallback, callbacks, compression, and persistence. AuraBoot should not copy that as a single giant service.

Lesson for AuraBoot:

- Reuse the "many entrypoints, shared core runtime" principle.
- Avoid the "one 13k-line god agent" shape.

### OpenClaw

Reference:

- https://docs.openclaw.ai/concepts/agent
- https://docs.openclaw.ai/concepts/multi-agent
- https://docs.openclaw.ai/cli/agent
- https://docs.openclaw.ai/cli/gateway

Relevant pattern:

OpenClaw routes roughly as:

```text
Channel / CLI / Webhook
  -> Gateway
      -> normalize message
      -> pick agent + session by binding
      -> embedded Agent Runtime
      -> deliver response back to channel
```

Multi-agent routing is based on bindings, agent IDs, workspaces, session stores, and channel/account configuration. The LLM does not choose the platform routing boundary.

Lesson for AuraBoot:

- Gateway/chokepoint routing should be deterministic.
- Agent/session isolation must be explicit.
- The model can decide tool usage inside a turn, but not platform safety boundaries.

## Architecture Options Considered

### Option A: Keep the three current services and patch each path

Summary:

```text
AgentRunService
AgentChatPortImpl
AuraBotChatService
```

would remain independent large services.

Pros:

- smallest immediate refactor;
- preserves existing tests and wiring;
- fewer class renames.

Cons:

- every safety fix must be implemented multiple times;
- provider and tool semantics continue to drift;
- integration tests remain fragmented;
- pending resume and ACP resume stay inconsistent;
- bugs like stub provider leakage can reappear in another path.

Decision:

Rejected as long-term architecture. It is acceptable only as an interim migration state.

### Option B: Merge everything into one giant Agent service

Summary:

Create one runtime service that handles chat, ACP, AuraBot, named agents, tools, provider resolution, plan execution, approval, checkpointing, persistence, and resume.

Pros:

- one obvious runtime entrypoint;
- avoids duplicate service names;
- may look simpler on a diagram.

Cons:

- lifecycle branches become a giant conditional tree;
- chat turn and durable workflow concerns become tightly coupled;
- tests become combinatorial;
- risk of reproducing the Hermes-style oversized core loop;
- code becomes harder to reason about and harder to safely modify.

Decision:

Rejected. AuraBoot needs shared infrastructure, not one giant service.

### Option C: One chokepoint, deterministic router, two lifecycle runtimes

Summary:

```text
ConversationTurnService
  -> AgentTurnRouter
      -> ChatTurnRuntime
      -> DurableRunRuntime / AcpRunOrchestrator
```

Pros:

- preserves one conversation chokepoint;
- keeps chat and durable run lifecycles separate;
- centralizes provider/tool/error/runtime-state infrastructure;
- allows focused tests for routing policy;
- aligns with OpenAI Runner/orchestration, workflow checkpointing, and OpenClaw gateway routing patterns;
- supports incremental migration.

Cons:

- requires careful extraction from existing services;
- some old class names may remain temporarily as facades/adapters;
- needs architecture tests to prevent duplicate loops from returning.

Decision:

Chosen target architecture.

## Final Decision

Adopt this target:

```text
ConversationTurnService
  - sole conversation lifecycle chokepoint
  - owns turn begin/finalize/resume envelope
  - no provider/tool loop implementation

AgentTurnRouter
  - deterministic routing policy
  - routes by entrypoint, pending type, side-effect policy, lifecycle requirement
  - LLM triage is advisory only

ChatTurnRuntime
  - normal chat turn lifecycle
  - named-agent chat
  - AuraBot light chat
  - read-only and small confirmed in-turn tool actions

DurableRunRuntime / AcpRunOrchestrator
  - ACP run/task/plan/step lifecycle
  - approval/checkpoint/resume
  - heartbeat/audit/replay

Shared runtime layer
  - provider resolution
  - tool loop execution
  - tool result normalization
  - runtime state manifest/reducer
  - tool discovery
  - pending continuation model
  - error compaction
  - observability
```

Class-level future:

- `AgentRunService`
  - Long term: shrink/rename into `AcpRunOrchestrator` or `DurableRunRuntime`.
  - It should not own generic provider/tool loop behavior.
- `AgentChatPortImpl`
  - Current branch: no longer owns named-agent round-loop control flow; delegates that to `ChatTurnRuntime.runToolLoop(...)`.
  - Long term: continue shrinking into a thin `NamedAgentTurnAdapter`, or disappear when named-agent chat calls `ChatTurnRuntime` directly.
  - It should not regain duplicate provider resolution, tool normalization, or loop control.
- `AuraBotChatService`
  - Long term: shrink to `AuraBotLightChatService`, or become an adapter into `ChatTurnRuntime`.
  - Pending continuation should move out.

## Implementation Blueprint

### Phase 1: Guard the target shape with tests

Add architecture tests before large extraction:

- detect duplicate tool result parsers outside `ToolLoopResultNormalizer`;
- detect provider resolution outside `LlmRuntimeResolver`;
- detect direct `provider.chat` calls in unauthorized service layers;
- detect private pending continuation implementations outside the shared continuation service;
- assert ACP runtime absence fails closed.

### Phase 2: Extract `ToolLoopResultNormalizer`

Replace local parsers in:

- `AgentChatPortImpl`;
- `AuraBotChatService`;
- `ChatToolExecutor`;
- ACP step/tool output paths where applicable.

Expected result:

- every tool result has the same compact success/error shape;
- model context and user-visible error behavior stop drifting.

### Phase 3: Extract `LlmRuntimeResolver`

Create one shared resolver for:

- provider code;
- model;
- provider bean;
- API mode;
- config reference;
- fallback chain;
- audit metadata;
- user-safe errors.

Use it from:

- named-agent chat;
- AuraBot light chat/resume;
- ACP durable runs;
- auxiliary LLM calls where appropriate.

### Phase 4: Extract pending continuation

Introduce a shared pending continuation model/service:

```text
PendingContinuationService
  -> resume chat tool confirmation
  -> resume ACP approval
  -> validate pending owner/channel/session
  -> resolve provider/config reference
  -> emit compact outcome
```

`ConversationTurnService.resumeTurn` remains the chokepoint.

### Phase 5: Extract tool discovery

Centralize:

- built-in tools;
- explicit tools;
- registry tools;
- ACP/runtime-specific tool filtering;
- risk/side-effect metadata.

### Phase 6: Build `ChatTurnRuntime`

Move named-agent chat loop and AuraBot light chat behavior into one runtime.

Keep adapters thin until callers migrate:

```text
AgentChatPortImpl -> NamedAgentTurnAdapter -> ChatTurnRuntime
AuraBotLightChatService -> ChatTurnRuntime
```

### Phase 7: Slim `AgentRunService`

Move durable orchestration behind:

```text
DurableRunRuntime / AcpRunOrchestrator
```

Keep only run/task/plan/approval/checkpoint semantics there.

### Phase 8: Runtime reducer and replay

Convert runtime state from snapshots into replayable events:

```text
AgentExecutionState + AgentRuntimeEvent -> AgentExecutionState + effects
```

Use this to test:

- tool call;
- tool result;
- confirmation required;
- approval required;
- provider failure;
- compacted error;
- resume;
- completion.

## Validation Strategy

### Unit tests

- `AgentTurnRouterTest`
  - explicit intent routing;
  - pending resume routing;
  - side-effect upgrade;
  - ACP missing-runtime fail-closed behavior.
- `ToolLoopResultNormalizerTest`
  - raw string errors;
  - JSON success/failure;
  - malformed payloads;
  - redaction.
- `LlmRuntimeResolverTest`
  - provider/model inference;
  - stub config handling;
  - fallback chain;
  - missing config user-safe failure.
- `PendingContinuationServiceTest`
  - chat confirmation;
  - ACP approval;
  - stale/owner-mismatched pending;
  - provider config re-resolution.

### Integration tests

- Chat turn with read-only tool returns normal answer.
- Chat turn with single confirmable action creates pending and resumes through `ConversationTurnService.resumeTurn`.
- ACP action creates durable run and does not fallback to chat.
- Missing ACP runtime dependency fails closed.
- Provider stub config cannot leak into real HTTP provider execution.

### Architecture tests

- No new direct `provider.chat` calls in non-runtime service layers.
- No new duplicate provider resolution methods.
- No duplicate `parseToolLoopResult` or `normalizeToolLoopResult` implementations.
- No private resume methods that bypass `ConversationTurnService`.

### E2E/smoke tests

Do not claim full E2E completion from targeted tests. For each gate:

- run environment health checks first;
- run targeted backend tests;
- run relevant slice/smoke tests;
- run full gate only when environment is valid;
- classify service disconnects as `environment-invalid`, not product failures.

## Open Questions

1. Which small write actions are allowed inside `ChatTurnRuntime` after confirmation?
2. Should side-effect metadata live on tool definitions, command definitions, or both?
3. Does `CONTEXTUAL_ANSWER` ever auto-upgrade to durable run, or must it always ask for explicit confirmation first?
4. How much ACP plan detail should be exposed back into normal chat transcripts?
5. What compatibility window is required for existing pending payloads?

## Non-Goals

- Do not introduce LangGraph, CrewAI, Hermes, or OpenClaw as a runtime dependency.
- Do not rewrite all Agent code in one pass.
- Do not remove `ConversationTurnService`.
- Do not let LLM-driven triage override platform safety policy.
- Do not preserve multiple provider/tool loop implementations for convenience.

## Acceptance Criteria For The Architecture Refactor

The refactor is complete when:

- every conversation entrypoint enters through `ConversationTurnService`;
- `AgentTurnRouter` has deterministic tests for chat vs durable routing;
- named-agent chat and AuraBot light chat share `ChatTurnRuntime`;
- ACP run/task/plan/approval uses `DurableRunRuntime / AcpRunOrchestrator`;
- provider resolution is centralized;
- tool result normalization is centralized;
- pending continuation is centralized;
- runtime state and compact errors are consistent across chat and durable paths;
- architecture tests prevent duplicate loops from returning.
