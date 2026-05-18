# Agent 12-Factor Runtime State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 AuraBoot Agent runtime 从“过程式 tool loop + 分散状态”逐步收敛为可审计、可重放、可测试的执行状态模型。

**Architecture:** 不替换现有 `ConversationTurnService` / `AgentChatPortImpl` / `AgentRunService` 主链路，先增加只读运行态快照与上下文清单，再把确认、审批、handoff、错误恢复逐步接入 reducer 风格状态机。第一阶段不做数据库迁移，不改变 LLM wire request，不改变 tool 执行语义。

**Tech Stack:** Java 21, Spring Boot, Jackson, JUnit 5, Mockito, AssertJ, AuraBoot `platform` Agent runtime。

---

## 背景与参考

HumanLayer 的 [12-factor-agents](https://github.com/humanlayer/12-factor-agents) 对我们的主要启发不是引入新框架，而是把 Agent 当作普通后端系统治理：prompt、context、tool schema、执行状态、暂停恢复、人工确认、错误恢复都必须有显式契约。

对应到 AuraBoot，已有基础包括：

- `ConversationTurnService.runTurn/resumeTurn` 是对话入口 chokepoint。
- `AgentChatPortImpl` 自己驱动 named-agent tool loop。
- `AgentRunService` 已有 run、plan、trace、approval、heartbeat。
- `ChatSessionStore` 已能保存 pending tool 和 message tape。

主要缺口：

- Context window 不是一等对象，prompt / tools / messages / memory 分散在多个服务里。
- Tool loop 还不是 `state + event -> state + effects` 的 reducer 形态。
- fallback 与错误恢复没有统一策略和审计。
- pending 状态还携带 provider secret，应迁移为 provider config reference。

## 目标

- 建立 `AgentExecutionState` 快照，先覆盖 named-agent chat turn。
- 每次 LLM round 能生成上下文清单：prompt hash、message hash、tool schema hash、token 估算、tool choice、pending 信息。
- Pending confirmation / approval 内部状态附带安全快照，不再只靠散落字段排查问题。
- 后续把 `AgentChatPortImpl` 和 `AgentRunService` 重构为 reducer 风格时，有稳定 DTO 和测试基线可依赖。

## 非目标

- 不替换 LLM provider。
- 不引入 LangGraph、CrewAI 或新的 Agent framework。
- 第一阶段不做数据库 schema 迁移。
- 第一阶段不删除现有 Redis pending 字段，避免破坏 resume 兼容性。
- 第一阶段不改变前端 SSE / WS 协议。

## 文件结构

- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentExecutionState.java`
  - Agent 执行状态顶层 DTO，包含 schema version、identity、model、context、tools、loop、pending、安全 hash。
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentContextManifest.java`
  - 上下文窗口清单，记录 prompt/message/tool 的长度、hash、token 估算和来源。
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentToolManifestItem.java`
  - LLM 暴露的 tool schema 摘要，记录 tool code、tool name、type、risk、confirmation、schema hash。
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactory.java`
  - 从 `TurnContext`、provider/model、system prompt、messages、tools 构造安全快照。
- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java`
  - 每个 LLM round 构造 `AgentExecutionState`。
  - pending confirmation / approval / AuraBot skill preview 的 `extension._runtime_state` 附带快照。
  - 快照不包含 API key、baseUrl、raw system prompt、raw messages。
- Test: `platform/src/test/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactoryTest.java`
  - 覆盖 hash 稳定性、token 估算、tool manifest、安全字段。
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java`
  - 覆盖 pending entry 带 `_runtime_state`，且不包含 `apiKey`。

## 分阶段方案

### Phase 0: Runtime State DTO 与 named-agent pending 快照

验收标准：

- `AgentRuntimeStateFactory` 可对同一输入产生稳定 `stateHash`。
- pending confirmation / approval / AuraBot skill preview 里有 `extension._runtime_state`。
- `_runtime_state` 只存 hash、长度、schema 摘要和 identity，不存 secret 或 raw prompt/messages。
- 原有 named-agent tool loop 测试继续通过。

### Phase 1: Context manifest 持久化与 fallback policy 审计

验收标准：

- ACP run 与 named-agent chat 都能记录同一种 `AgentExecutionState`。
- provider fallback、tool discovery expansion、legacy runtime fallback 都写入 manifest。
- 高风险 / write-capable agent 支持 fail-closed policy。

### Phase 2: Reducer 风格 tool loop

验收标准：

- 新增 `AgentReducer` 接口：`reduce(AgentExecutionState state, AgentRuntimeEvent event)`。
- `AgentChatPortImpl` 的 model response、tool use、tool result、confirm required、handoff 变成事件驱动。
- 单元测试能 replay 一组事件并得到相同最终状态。

### Phase 3: Error compaction

验收标准：

- 新增 `AgentErrorFrame`：tool name、args hash、error class、retryability、user-safe message、model recovery hint。
- 可重试错误才进入下一轮 context，不可重试错误 fail fast。
- Provider / tool / validation 三类错误都有测试覆盖。

### Phase 4: Pending secret cleanup

验收标准：

- `ChatSessionStore.PendingTool` 不再保存 `apiKey`。
- resume 时保存 provider code / config version，执行前重新解析 provider config。
- 老 pending payload 在 TTL 内兼容读取，新 payload 不含 secret。

## Task 1: 文档与运行态 DTO

**Files:**

- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentExecutionState.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentContextManifest.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentToolManifestItem.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactory.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactoryTest.java`

- [x] **Step 1: Write failing factory tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest
```

Expected: FAIL because runtime DTO/factory classes do not exist.

- [x] **Step 2: Implement DTO and factory**

Implement immutable Java records with these contracts:

```java
public record AgentExecutionState(
        String schemaVersion,
        String executionKind,
        String turnId,
        String runPid,
        String taskPid,
        Long tenantId,
        Long userId,
        String agentCode,
        String sessionId,
        String providerCode,
        String model,
        int round,
        String toolChoice,
        AgentContextManifest context,
        List<AgentToolManifestItem> tools,
        Map<String, Object> pending,
        String stateHash) {
}
```

The factory must expose:

```java
public AgentExecutionState chatTurnState(
        TurnContext ctx,
        String agentCode,
        String sessionId,
        String providerCode,
        String model,
        int round,
        String toolChoice,
        String systemPrompt,
        int maxTokens,
        List<LlmChatRequest.Message> messages,
        List<LlmChatRequest.Tool> llmTools,
        List<ToolDefinition> toolDefinitions,
        Map<String, Object> pending)
```

- [x] **Step 3: Run factory tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest
```

Expected: PASS.

## Task 2: Integrate runtime state into named-agent pending entries

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java`

- [x] **Step 1: Build round state before provider call**

Inside `doAgentToolLoop`, create `AgentExecutionState roundState` after `toolChoice` is resolved and before `provider.chat(...)`.

- [x] **Step 2: Add runtime state to pending extension**

For confirmation-required tools, approval-required tools, and AuraBot skill preview pending entries, add:

```java
extension.put("_runtime_state", runtimeState.toSnapshotMap());
```

The snapshot map must exclude `apiKey`, `baseUrl`, raw prompt, and raw messages.

- [x] **Step 3: Update pending tests**

Extend existing `AgentChatPortImplToolLoopTest` pending assertions:

```java
assertThat(stored.getExtension()).containsKey("_runtime_state");
assertThat(stored.getExtension().toString()).doesNotContain("test-key");
```

- [x] **Step 4: Run named-agent targeted tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest \
  --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest
```

Expected: PASS.

## Task 3: Compile gate

**Files:**

- All files modified in Task 1 and Task 2.

- [x] **Step 1: Run Java compile**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :compileJava :compileTestJava
```

Expected: PASS.

- [x] **Step 2: Inspect diff**

Run:

```bash
git -C /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state diff --stat
git -C /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state diff -- platform/src/main/java/com/auraboot/framework/agent platform/src/test/java/com/auraboot/framework/agent docs/superpowers/plans/2026-05-19-agent-12factor-runtime-state.md
```

Expected: diff only contains runtime DTO/factory, named-agent pending integration, tests, and this plan.

## Task 4: Persist ACP run runtime metadata

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactory.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/runtime/AgentRuntimeStateFactoryTest.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentRunServiceSyncTest.java`

- [x] **Step 1: Document Phase 1 execution scope**

Persist Phase 1 evidence into existing `ab_agent_run.metadata` as a JSON string. Do not add a schema migration in this phase.

- [x] **Step 2: Write failing ACP run state factory test**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest
```

Expected: FAIL until `acpRunState(...)` exists.

- [x] **Step 3: Write failing AgentRunService metadata test**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest
```

Expected: FAIL until `AgentRunService` writes `metadata.runtimeState` and `metadata.fallbackAudit`.

- [x] **Step 4: Implement ACP runtime state factory path**

Add:

```java
public AgentExecutionState acpRunState(
        Long tenantId,
        Long userId,
        String runPid,
        String taskPid,
        String agentCode,
        String providerCode,
        String model,
        String systemPrompt,
        String userMessage,
        int maxTokens,
        List<AgentToolDefinition> tools,
        Map<String, Object> pending)
```

- [x] **Step 5: Implement AgentRunService metadata write**

After grounding/tool selection and before plan generation, write one metadata JSON payload:

```json
{
  "runtimeState": { "...": "secret-free snapshot" },
  "fallbackAudit": {
    "provider": {"preferred": "...", "resolved": "...", "chain": ["..."], "fallbackUsed": false},
    "toolDiscovery": {"mode": "bif|quality_gate_expanded|candidate_skills|registry_all", "qualityIssue": null}
  }
}
```

- [x] **Step 6: Run Phase 1 targeted tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest \
  --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest
```

Expected: PASS.

## Task 5: Add reducer/event foundation for named-agent tool loop

**Files:**

- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeEvent.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeEffect.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentReducer.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/DefaultAgentReducer.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/runtime/DefaultAgentReducerTest.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java`

- [x] **Step 1: Write failing reducer replay test**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.DefaultAgentReducerTest
```

Expected: FAIL until reducer/event classes exist.

- [x] **Step 2: Implement reducer/event/effect model**

Add one reducer contract:

```java
public interface AgentReducer {
    Result reduce(AgentExecutionState state, AgentRuntimeEvent event);
}
```

The default reducer must:

- keep state immutable;
- increment `pending.eventCount`;
- write `pending.lastEventType`;
- hash event payloads instead of storing raw inputs;
- emit deterministic effects for `model_response`, `tool_use`, `tool_result`, `confirmation_required`, `handoff_requested`, `turn_completed`, and `turn_failed`.

- [x] **Step 3: Wire AgentChatPortImpl to reducer events**

Record reducer events without changing existing provider request, sink event, pending, or tool execution semantics.

- [x] **Step 4: Add AgentChatPortImpl event wiring test**

Use a spy reducer and assert a confirmation-required turn records:

- `model_response`
- `tool_use`
- `confirmation_required`

- [x] **Step 5: Run Phase 2 targeted tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.DefaultAgentReducerTest \
  --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest
```

Expected: PASS.

## Task 6: Add compact error frames for named-agent recovery

**Files:**

- Create: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentErrorFrame.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/runtime/AgentRuntimeEvent.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/runtime/AgentErrorFrameTest.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java`

- [x] **Step 1: Write failing compact error frame tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentErrorFrameTest
```

Expected: FAIL until `AgentErrorFrame` exists.

- [x] **Step 2: Write failing named-agent error compaction tests**

Cover provider, tool, and validation errors:

- provider exception produces a fail-fast `provider` error frame and a user-safe error message;
- tool execution exception produces a retryable `tool` error frame in `tool_result` and continues to the next model round;
- unknown tool produces a retryable `validation` error frame instead of leaking raw tool args.

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest
```

Expected: FAIL until error compaction is wired.

- [x] **Step 3: Implement `AgentErrorFrame`**

The frame must include:

- `schemaVersion`
- `category` (`provider`, `tool`, `validation`)
- `toolName`
- `argsHash`
- `errorClass`
- `retryable`
- `userSafeMessage`
- `modelRecoveryHint`

It must hash args via canonical SHA-256 and never store raw args, secrets, raw prompt, or raw provider exception text.

- [x] **Step 4: Wire `AgentChatPortImpl` error compaction**

Keep existing success, approval, confirmation, and sink semantics. Only replace raw model-facing tool error payloads with `errorFrame`, and record provider/validation/tool failures through `AgentRuntimeEvent.turnFailed(...)` or `toolResultRecorded(...)`.

- [x] **Step 5: Run Phase 3 targeted tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentErrorFrameTest \
  --tests com.auraboot.framework.agent.runtime.DefaultAgentReducerTest \
  --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest
```

Expected: PASS.

## Task 7: Remove provider secrets from new pending payloads

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java`
- Modify: `platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/port/AgentChatPort.java`
- Modify: `platform/src/main/java/com/auraboot/framework/aurabot/service/ChatSessionStore.java`
- Test: `platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java`
- Test: `platform/src/test/java/com/auraboot/framework/aurabot/service/AuraBotChatServiceResumeSnapshotTest.java`

- [x] **Step 1: Write failing pending-secret tests**

Cover confirmation, approval, and AuraBot skill pending entries from `AgentChatPortImpl`:

- `PendingTool.providerCode` and `model` are still present;
- `PendingTool.apiKey` and `baseUrl` are null;
- `_runtime_state` remains secret-free.

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest
```

Expected: FAIL until new pending builders stop writing secrets.

- [x] **Step 2: Write failing resume config re-resolution test**

Cover `AuraBotChatService.resumeApprovedTurnFromPending` with a pending entry that has no `apiKey` or `baseUrl`. Resume must resolve provider config by `providerCode` and call the provider using the fresh config.

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest
```

Expected: FAIL until resume path re-resolves provider config.

- [x] **Step 3: Stop writing secrets to new pending payloads**

Remove `.apiKey(...)` and `.baseUrl(...)` from all new `ChatSessionStore.PendingTool.builder()` writes in named-agent and AuraBot resume paths. Keep the fields on `PendingTool` for TTL-window backward compatibility with already serialized pending entries.

- [x] **Step 4: Re-resolve provider config on resume**

In `AuraBotChatService.resumeApprovedTurnFromPending`, use stored `providerCode` as a config reference:

- prefer old pending `apiKey/baseUrl` when present for compatibility;
- otherwise resolve fresh `ProviderConfig` from `LlmProviderFactory`;
- fail closed if no usable provider credential exists.

- [x] **Step 5: Run Phase 4 targeted tests**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.service.AgentChatPortImplToolLoopTest \
  --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest
```

Expected: PASS.

## Task 8: Closeout hardening and branch readiness

**Files:**

- All files modified by Tasks 1-7.

- [x] **Step 1: Run broader related backend tests**

Cover runtime DTO/reducer/error frame, named-agent variants, ACP run metadata, and resume chokepoint behavior without running Page Designer or browser E2E.

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test \
  --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*Test' \
  --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest \
  --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest
```

Expected: PASS.

- [x] **Step 2: Run compile and diff gates**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :compileJava :compileTestJava
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --check
```

Expected: PASS.

- [x] **Step 3: Verify no new pending secret writes remain**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
rg -n '\.apiKey\(|\.baseUrl\(' \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
```

Expected: no matches for pending builder secret writes.

- [x] **Step 4: Inspect branch diff and status**

Run:

```bash
git -C /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state status -sb
git -C /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state diff --stat
git -C /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state ls-files --others --exclude-standard
```

Expected: only agent runtime, named-agent/AuraBot resume service changes, tests, and this plan are changed.

## Task 9: Self-review and validation evidence

**Files:**

- All files modified by Tasks 1-8.

- [x] **Step 1: Review changed file set and focused diff**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git status -sb
git diff --stat
git diff -- platform/src/main/java/com/auraboot/framework/agent/runtime \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/ChatSessionStore.java \
  platform/src/test/java/com/auraboot/framework/agent/runtime \
  platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java \
  platform/src/test/java/com/auraboot/framework/agent/service/AgentRunServiceSyncTest.java \
  platform/src/test/java/com/auraboot/framework/aurabot/service/AuraBotChatServiceResumeSnapshotTest.java
```

Expected: diff remains scoped to runtime state, reducer/error-frame foundation, pending snapshot, ACP metadata, pending secret cleanup, and tests.

- [x] **Step 2: Run focused red-line greps**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
rg -n '\.apiKey\(|\.baseUrl\(' \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
rg -n 'catch \(Exception|ensure[A-Z]|repair|backfill' \
  platform/src/main/java/com/auraboot/framework/agent/runtime \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
```

Expected: no new pending secret writes; no startup/self-healing/backfill pattern introduced.

- [x] **Step 3: Fix self-review findings**

If focused review finds a real gap, write a failing targeted test first, implement the minimal fix, and run the targeted test back to green.

Current self-review finding:

- `AgentRuntimeStateFactory.sanitizePending(...)` must drop sensitive pending keys such as `apiKey`, `baseUrl`, and preview tokens instead of preserving string values.
- New diagnostic catch blocks must avoid broad `catch (Exception)` and state the fail-closed / instrumentation-isolation intent.

- [x] **Step 4: Record fresh validation evidence**

Update the Validation section with the exact targeted backend, compile, diff, and grep commands used in this branch.

- [x] **Step 5: Re-run final targeted verification**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test \
  --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*Test' \
  --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest \
  --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest
./gradlew :compileJava :compileTestJava
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --check
```

Expected: PASS.

## Task 10: Integration readiness package

**Files:**

- All files modified by Tasks 1-9.

- [x] **Step 1: Check branch provenance and canonical workspaces**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git branch --show-current
git status -sb
git rev-parse --abbrev-ref --symbolic-full-name @{u}
git merge-base HEAD origin/main
git log --oneline --decorate --max-count=5
git -C /Users/ghj/work/auraboot/auraboot branch --show-current
git -C /Users/ghj/work/auraboot/auraboot-enterprise branch --show-current
```

Expected: feature work remains in this worktree; canonical repos remain on `main`.

- [x] **Step 2: Capture changed file inventory**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --name-status
git ls-files --others --exclude-standard
git diff --stat
```

Expected: inventory is limited to agent runtime state, named-agent/AuraBot resume service changes, tests, and this plan.

- [x] **Step 3: Capture implementation entry points**

Run focused line lookups for the PR/merge summary:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
rg -n 'class AgentRuntimeStateFactory|record AgentExecutionState|record AgentErrorFrame|class DefaultAgentReducer|class AgentRuntimeEvent' platform/src/main/java/com/auraboot/framework/agent/runtime
rg -n 'runtimeStateFactory|reduceRuntimeState|compactToolErrorResult|providerErrorFrame|confirmationPending|approvalPending|aurabotSkillPending' platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java
rg -n 'runtimeState|fallbackAudit|acpRunState' platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java
rg -n 'resumeApiKey|resolveResumeProviderConfig|suspendForAurabotSkill' platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
```

- [x] **Step 4: Run final lightweight gate**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --check
rg -n '\.apiKey\(|\.baseUrl\(' \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
```

Expected: whitespace clean and no new pending secret writes.

Latest integration readiness:

- Worktree branch: `feat/agent-12factor-runtime-state`.
- Upstream: `origin/main`.
- After `git fetch origin main`, `HEAD`, `origin/main`, and `FETCH_HEAD` all resolve to `f79e8f1fc054b922e1d90c8200e7432932a412a1`.
- Ahead/behind against `origin/main`: `0/0`; all implementation is currently uncommitted working tree state.
- Canonical workspaces `/Users/ghj/work/auraboot/auraboot` and `/Users/ghj/work/auraboot/auraboot-enterprise` are both on `main`.
- Lightweight gate: `git diff --check` passed; pending secret write grep returned no matches; new-line broad-catch/self-healing grep returned no matches.

## Task 11: Commit and PR package

**Files:**

- All files modified by Tasks 1-10.

- [x] **Step 1: Prepare commit message**

Suggested commit:

```text
feat(agent): add runtime state snapshots and reducer audit
```

Suggested body:

```text
- add secret-free AgentExecutionState/context/tool manifests plus reducer events
- persist ACP runtime metadata and fallback audit into run metadata
- compact provider/tool/validation errors and remove provider secrets from new pending payloads
- re-resolve provider config on AuraBot resume while keeping old pending payload compatibility
```

- [x] **Step 2: Prepare PR body**

Suggested PR body:

```markdown
## Summary
- Added secret-free agent runtime state snapshots, context/tool manifests, reducer events/effects, and compact error frames.
- Wired named-agent tool loop, ACP run metadata, and AuraBot resume to carry auditable runtime state without changing provider/tool execution semantics.
- Removed provider secrets from new pending payloads and re-resolve provider config during resume, while retaining backward compatibility for old pending entries.

## Test Plan
- `./gradlew :test --tests 'com.auraboot.framework.agent.runtime.*' --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*Test' --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest`
- `./gradlew :compileJava :compileTestJava`
- `git diff --check`
- Pending secret write grep returned no matches.
- New-line broad-catch/self-healing grep returned no matches.

## Not Run
- Page Designer tests
- Web E2E / full gate
```

- [x] **Step 3: Dry-run staging inventory**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git add --dry-run .
git diff --name-status
git ls-files --others --exclude-standard
```

Expected: dry-run includes only the files listed in Tasks 1-10.

- [x] **Step 4: Final pre-submit lightweight verification**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --check
rg -n '\.apiKey\(|\.baseUrl\(' \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
git diff -U0 -- platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java \
  platform/src/main/java/com/auraboot/framework/agent/runtime \
  | rg '^\+.*(catch \(Exception|ensure[A-Z]|repair|backfill)'
```

Expected: whitespace clean; no pending secret writes; no newly introduced broad `catch (Exception)` / self-healing / repair / backfill pattern.

Latest pre-submit package result:

- `git add --dry-run .` includes only expected agent runtime/service/test/plan files.
- `git diff --check`: no output.
- Pending secret write grep: no matches.
- New-line broad-catch/self-healing grep: no matches.

## Task 12: Submit execution runbook

**Files:**

- All files modified by Tasks 1-11.

- [x] **Step 1: Confirm clean index before real submit**

Run:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git status -sb
git diff --cached --name-status
```

Expected: working tree has only expected modified/untracked files; index is empty before explicit submit.

- [x] **Step 2: Document real submit commands**

Only run after an explicit submit instruction:

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git add \
  docs/superpowers/plans/2026-05-19-agent-12factor-runtime-state.md \
  platform/src/main/java/com/auraboot/framework/agent/port/AgentChatPort.java \
  platform/src/main/java/com/auraboot/framework/agent/runtime \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/ChatSessionStore.java \
  platform/src/test/java/com/auraboot/framework/agent/runtime \
  platform/src/test/java/com/auraboot/framework/agent/service/AgentChatPortImplToolLoopTest.java \
  platform/src/test/java/com/auraboot/framework/agent/service/AgentRunServiceSyncTest.java \
  platform/src/test/java/com/auraboot/framework/aurabot/service/AuraBotChatServiceResumeSnapshotTest.java
git commit -m "feat(agent): add runtime state snapshots and reducer audit"
```

Optional PR path after commit:

```bash
git push -u origin feat/agent-12factor-runtime-state
gh pr create --title "feat(agent): add runtime state snapshots and reducer audit" --body-file <prepared-pr-body>
```

- [x] **Step 3: Document post-submit verification**

After commit, verify:

```bash
git status -sb
git log --oneline --decorate --max-count=3
git merge-base --is-ancestor HEAD feat/agent-12factor-runtime-state
```

If pushing, verify:

```bash
git rev-parse HEAD
git rev-parse origin/feat/agent-12factor-runtime-state
```

Expected: local commit is present on the feature branch; pushed branch points to the same commit when push is requested.

Latest runbook check:

- `git diff --cached --name-status`: no output, index is empty.
- `git diff --check`: no output.
- Pending secret write grep: no matches.
- New-line broad-catch/self-healing grep: no matches.
- Latest targeted test XML summary: `tests=68 failures=0 errors=0 skipped=0`.

## 风险与控制

- **风险:** 改动 `AgentChatPortImpl` 影响已有 named-agent chat 路径。
  **控制:** 只增加快照构造和 pending extension，不改变 provider request、tool execution、sink event。

- **风险:** 快照意外保存 secret 或 raw prompt。
  **控制:** factory 测试断言 snapshot map 不包含 `apiKey`、`baseUrl`、raw prompt、raw message。

- **风险:** `extension` 已被 AuraBot skill preview 使用。
  **控制:** 用 `LinkedHashMap` 合并，不覆盖已有 `_aurabot_skill`、`previewToken`、`preview`、`riskLevel`。

- **风险:** 后续 reducer 重构过大。
  **控制:** 先用状态 DTO 和测试固定契约，再逐步迁移 loop 分支。

## Validation

本分支执行的是后端 runtime targeted 验证，不运行 Page Designer、Web E2E 或 full gate。

### TDD red/green evidence

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test --tests com.auraboot.framework.agent.runtime.AgentRuntimeStateFactoryTest
```

- Red: 新增 `drops sensitive pending keys from snapshots` 后先失败，证明旧 `sanitizePending(...)` 会保留敏感 pending key。
- Green: 修复 sensitive-key sanitizer 后同一命令通过。

### Targeted backend regression

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :test \
  --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*Test' \
  --tests com.auraboot.framework.agent.service.AgentRunServiceSyncTest \
  --tests com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplResumeTest \
  --tests com.auraboot.framework.conversation.ConversationTurnServiceImplAcpResumeTest
```

### Compile and diff gates

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state/platform
./gradlew :compileJava :compileTestJava
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
git diff --check
```

### Focused red-line greps

```bash
cd /Users/ghj/work/auraboot/.worktrees/agent-12factor-runtime-state
rg -n '\.apiKey\(|\.baseUrl\(' \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java
git diff -U0 -- platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java \
  platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java \
  platform/src/main/java/com/auraboot/framework/aurabot/service/AuraBotChatService.java \
  platform/src/main/java/com/auraboot/framework/agent/runtime \
  | rg '^\+.*(catch \(Exception|ensure[A-Z]|repair|backfill)'
```

Expected: no new pending secret writes and no newly introduced broad `catch (Exception)` / self-healing / repair / backfill pattern.

### Latest result

- Targeted backend regression: `tests=68 failures=0 errors=0 skipped=0`.
- Compile gate: `./gradlew :compileJava :compileTestJava` returned `BUILD SUCCESSFUL`.
- `git diff --check`: no output.
- Pending secret write grep: no matches.
- New-line red-line grep for broad `catch (Exception)` / self-healing / repair / backfill: no matches.
