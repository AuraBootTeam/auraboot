# Agent Architecture Convergence Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans when executing this plan task-by-task. This session executes inline because the worktree already contains an active multi-file refactor and the user asked to continue until the issues are fixed.

**Goal:** 收敛 Agent 相关运行时架构，消除路由策略未接入、Chat runtime 混入 AuraBot 领域分支、pending resume 双执行路径、AuraBot light chat 过重、ACP loop 错误处理不一致等问题。

**Architecture:** `ConversationTurnService` 只做 chokepoint、生命周期和 finalization；`AgentTurnRouter` 做确定性路由；chat turn 与 durable ACP run 保留不同生命周期，但共享 provider resolution、LLM response guard、message tape、tool execution snapshot、tool result normalization、pending snapshot、错误脱敏和 runtime-state 基础能力。

**Tech Stack:** Java 21, Spring Boot, JUnit 5, Mockito, AssertJ, Gradle.

---

## Context

这次 review 的直接背景是 AuraBot 客户信息统计报错 `Invalid scheme [stub]`。表面上是 provider/config 问题，根因是 Agent runtime 里曾经同时存在多处 provider 解析、fallback、tool loop、pending continuation 和 response finalization 逻辑。测试覆盖了一部分路径，但多重实现导致另一路可以静默漂移。

当前分支已经做过一轮收敛：

- `ConversationTurnServiceImpl` 成为 `/chat/stream`、`/execute`、IM、ACP 的 chokepoint。
- `AgentTurnRouter` 已经存在，但 durable policy 信号还没有从真实 request/tool/action metadata 接入。
- `ChatTurnRuntime` 已经承接 named-agent 和 pending resume 的 tool loop，但仍带有 AuraBot skill/pending 专属分支。
- `AuraBotPendingContinuationService` 已从 `AuraBotChatService` 抽出，但 initial confirmed tool 与 follow-up tool 仍存在执行路径不一致。
- `AuraBotChatService` 已变成 light chat text stream 路径，但仍承担 prompt/schema/RAG/D1/tool hint 组装。
- `StepLoopService` 仍是 ACP durable run 的独立 loop，需要继续共享错误脱敏和 runtime helper。

## Findings To Fix

### P1. Router policy surface exists but input is hardcoded

`AgentTurnRouter.RuntimePolicyInput` 已支持 `explicitDurableRequest`、`requiresApproval`、`externalSideEffect`、`batch`，但 `ConversationTurnServiceImpl.runTurn` 传入值仍全部为 `false`。这会让需要 durable lifecycle 的 turn 过度依赖 triage bucket。

**Acceptance:**

- `ConversationTurnServiceImpl` 从 `TurnRequest.options`、`ChatRequest.options`、page context 和 server-side overrides 解析 durable policy。
- 有副作用、需要 approval、batch、显式 durable 的 AuraBot turn 即使 triage 是 `LIGHT_CHAT` 也路由到 ACP durable run。
- named-agent 不受 AuraBot durable policy 影响，仍进入 named-agent chat adapter。

### P1. ChatTurnRuntime still owns AuraBot-specific policy

`ChatTurnRuntime` 的 callback 和主循环中仍有 `storeAuraBotSkillPending`、`isAuraBotSkillTool`、`isAuraBotSkillPreviewPending` 等 AuraBot 命名与判断。共享 runtime 不应该知道 AuraBot skill provider。

**Acceptance:**

- `ChatTurnRuntime` 改为通过 `ToolExecutionDisposition` 或等价 policy hook 判断普通工具、confirmation pending、preview pending、approval pending。
- `ChatTurnRuntime` 源码不再出现 `AuraBot` / `aurabot` 命名。
- AuraBot skill preview 判断移动到 `AuraBotPendingContinuationService` 和 AuraBot/named-agent callback adapter。

### P2. Pending resume uses two execution implementations

`AuraBotPendingContinuationService` initial confirmed tool 走 `ToolLoopService.executeToolCall`，follow-up tool 走 `ChatToolExecutor.execute`。这会造成 snapshot、approval、error normalization、canonical runtime 行为不一致。

**Acceptance:**

- 非 AuraBot skill 的 pending resume initial tool 与 follow-up tool 都走 `ToolLoopService` + stored `AgentToolDefinition` snapshot。
- 缺少 snapshot 时 fail closed。
- pending resume callback 产生 runtime-state snapshot/reducer 事件，不再返回 `null`。

### P2. AuraBot light chat adapter is too heavy

`AuraBotChatService` 同时处理 provider config、D1 grounding、tool hint、prompt template、schema text、record data、RAG、user soul profile 和 streaming。light chat adapter 应该只编排 light chat turn。

**Acceptance:**

- 新增 `AuraBotPromptContextBuilder`，集中构造 system prompt 与 prompt-adjacent context。
- `AuraBotChatService` 只保留 light chat turn 编排、provider stream、trace 收尾。
- prompt fallback 的行为被命名为显式 policy，而不是隐藏在 service 内部。

### P2. ACP durable loop error handling and shared helper usage are inconsistent

`StepLoopService` 内仍存在 raw exception message 进入 logs/tool result 的路径，且 provider/tool loop helper 与 chat runtime 的共享程度不足。

**Acceptance:**

- `StepLoopService` 并行工具失败、timeout/config parse、tool call update 失败日志都使用 `LogSanitizer.safe` 或 shared error frame。
- provider response 已经通过 `LlmResponseGuard` 的路径保留并加测试。
- 不在本轮强行把 ACP run 塞进 `ChatTurnRuntime`；只共享底层 guard/tape/error policy。

### P3. Class names still encode old boundaries

`AgentRunService`、`AgentChatPortImpl`、`AuraBotChatService` 仍存在，但长期目标应该是：`AcpRunOrchestrator`、`NamedAgentTurnAdapter`、`AuraBotLightChatService`。本轮不做大规模 rename，以免扩大 blast radius；用 architecture tests 防止职责重新扩张。

**Acceptance:**

- architecture tests 明确禁止 `ChatTurnRuntime` 引用 AuraBot。
- architecture tests 明确检查 provider calls、pending continuation、tool normalization 的允许边界。
- 文档记录长期 rename 方向和本轮不 rename 的理由。

## Task List

- [x] Task 1: Add failing tests for durable policy routing from request metadata.
- [x] Task 2: Implement durable policy extraction in `ConversationTurnServiceImpl`.
- [x] Task 3: Add failing tests proving `ChatTurnRuntime` delegates pending/approval policy without AuraBot-specific branches.
- [x] Task 4: Replace AuraBot-specific branches in `ChatTurnRuntime` with generic tool disposition callbacks.
- [x] Task 5: Add failing tests for pending resume follow-up tool execution through snapshot-backed `ToolLoopService`.
- [x] Task 6: Implement unified pending resume execution and runtime-state reducer.
- [x] Task 7: Resolve prompt/context ownership by introducing provenance-labeled `AgentContextAssembler`; the original `AuraBotPromptContextBuilder` name is superseded, while future prompt-builder rename is non-blocking cleanup.
- [x] Task 8: Sanitize remaining `StepLoopService` ACP error/log result paths and add focused regression tests.
- [x] Task 9: Add/extend architecture tests for runtime boundaries.
- [x] Task 10: Run targeted compile/test suite and `git diff --check`; update this document with completion evidence.

2026-05-20 状态校准：

- `ChatTurnRuntime` 已保持 adapter-neutral：不再直接命名 AuraBot 专属 tool type 或 preview-pending marker。Adapter-specific preview confirmation 统一通过 generic `ToolResultDisposition` callbacks 承接。
- `AgentTurnRouter` decision reason 已改为执行语义命名（`DURABLE_TRIAGE_SIGNAL`、`DURABLE_EXECUTION_POLICY`、`SYNC_READ_ONLY_TURN`、`SYNC_CHAT_TURN`），不再使用 AuraBot/light/contextual 场景命名。
- `统计 / 查询 / list / count` 等只读分析意图已从 mutation triage 中拆出，进入 `CONTEXTUAL_ANSWER + readonly tools`；`CONTEXTUAL_ANSWER` 不再因为缺少 readonly whitelist 自动升级 durable。
- 主 `application.yml` 已取消缺省 stub sentinel；stub LLM 改为 test profile、`AGENT_LLM_STUB_MODE=true` 或显式 sentinel opt-in。
- `AgentRuntimeArchitectureTest` 已锁住这两个边界，防止后续在 generic runtime 重新引入 AuraBot 分支，或在 router reason 中重新引入 scenario 命名。
- Review fix verification 已覆盖：
  - `./gradlew :test --tests 'com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest' --tests 'com.auraboot.framework.agent.runtime.AgentTurnRouterTest' --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*' --tests 'com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest' --tests 'com.auraboot.framework.architecture.AgentRuntimeArchitectureTest'`
  - `./gradlew :test --tests 'com.auraboot.framework.aurabot.service.AuraBotChatSkillResumeIntegrationTest'` with isolated `auraboot-skills-c2` Postgres/Redis preflight.
  - `./gradlew :compileJava :compileTestJava`
  - `git diff --check`

## Validation Plan

Run targeted tests first:

```bash
./gradlew :test --tests 'com.auraboot.framework.agent.runtime.AgentTurnRouterTest' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImplDispatchTest' \
  --tests 'com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest' \
  --tests 'com.auraboot.framework.aurabot.service.AuraBotChatServiceResumeSnapshotTest' \
  --tests 'com.auraboot.framework.aurabot.service.AuraBotChatSkillResumeIntegrationTest' \
  --tests 'com.auraboot.framework.agent.service.StepLoopServiceLlmResponseGuardTest' \
  --tests 'com.auraboot.framework.architecture.AgentRuntimeArchitectureTest'
```

Then run compilation and focused agent/conversation suite:

```bash
./gradlew :compileJava :compileTestJava
./gradlew :test --tests 'com.auraboot.framework.agent.runtime.*' \
  --tests 'com.auraboot.framework.agent.service.AgentChatPortImpl*' \
  --tests 'com.auraboot.framework.agent.service.AgentRunServiceSyncTest' \
  --tests 'com.auraboot.framework.aurabot.service.AuraBotChat*' \
  --tests 'com.auraboot.framework.conversation.ConversationTurnServiceImpl*'
git diff --check
```

Full regular/deep gate is not part of this document unless explicitly requested after targeted suite is green, because this task is backend architecture convergence and the current instruction is to fix the Agent runtime issues.
