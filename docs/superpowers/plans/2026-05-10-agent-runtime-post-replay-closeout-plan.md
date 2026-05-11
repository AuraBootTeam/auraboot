# Agent Runtime Post-Replay Closeout Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `conversation turn 全量回放 + result-contract 深链` 完成后，收口剩余语义边界、文档状态和验证证据。

**Architecture:** 继续保持单一 runtime 原则：不新增对话入口、不新增执行 runtime、不保留 legacy fallback。Replay 仍是只读 read model；没有 `turnId/conversationId/inboundMessageId` 等 turn identity 的普通 run 不返回伪造的 `conversationTurn`。

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, JUnit integration tests, React/TypeScript, Vitest, Playwright E2E, Markdown plan docs.

---

## 方案讨论

### Problems

- 上一轮已经完成 conversation/result 深链，但 master/followup/delivery 文档仍有“下一轮增强”的旧口径。
- `AgentRunController.loadConversationTurn` 当前会对没有 turn 证据的普通 run 返回一个字段基本为空的 `conversationTurn`，这会污染 Replay 语义。
- 需要补一个回归测试，防止以后把“缺失数据”包装成空对象。

### Target

- 普通 run：无 turn identity 时 `conversationTurn = null`。
- Conversation run：有 `turnId/conversationId/inboundMessageId` 时继续返回完整 projection，并保持跨租户 message 不泄漏。
- 文档状态统一：`conversation turn 全量回放 + result-contract 深链` 从“下一轮增强”改为“已完成并验证”。
- 复跑目标验证并记录结果。

### Non-Goals

- 不新增 `ab_conversation_turn` 写模型。
- 不实现 time-travel、fork-from-step、SSE historical replay。
- 不扩大为全量 agent 产品路线图重写。

## Task List

### Task 1: Document Complete Follow-Up List

- [x] 创建本 post-replay closeout plan。
- [x] 明确本轮继续任务是语义边界、文档状态和验证收口，不再新增 runtime 能力。

### Task 2: Fix Empty ConversationTurn Semantics

- [x] 在 `AgentRunController.loadConversationTurn` 中增加 turn identity guard。
- [x] 只有存在 `turnId`、`conversationId` 或 `inboundMessageId` 时才返回 `AgentConversationTurnReplay`。
- [x] 保留已有跨租户 message 空列表行为。

### Task 3: Add Backend Regression Coverage

- [x] 在 `AgentRunControllerIntegrationTest` 增加普通 run 无 turn seed 时 `conversationTurn == null` 的测试。
- [x] 保留已有 full turn/result-contract chain 测试。
- [x] 复跑 `AgentRunControllerIntegrationTest`。

### Task 4: Sync Status Docs

- [x] 更新 `2026-05-10-agent-runtime-master-task-list.md`，将 A11/C8 口径改为 full conversation/result deep link 已完成。
- [x] 更新 `2026-05-10-agent-runtime-followups.md`，移除“下一轮增强”旧口径。
- [x] 更新 `2026-05-10-agent-runtime-completion-plan.md` / `delivery-readiness.md` 的剩余边界。
- [x] 在当前文档记录本轮验证结果。

### Task 5: Verification

- [x] 后端：`./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport`。
- [x] 前端：复跑 replay drawer/list vitest。
- [x] 类型：`pnpm --dir web-admin typecheck`。
- [x] E2E truth：确认 `admin-agent-runs.spec.ts` 无 skip/fixme/only、waitForTimeout、写 API fallback、retry 覆盖。
- [x] `git diff --check`。

### Task 6: E2E Navigation Stability

- [x] 修复 `navigateAgentRunsViaSidebar` 的 AI Center 父菜单展开逻辑：仅当 `/admin/agent-runs` 叶子链接不存在或不可见时才点击父菜单，避免重复点击导致折叠。
- [x] 复跑 `admin-agent-runs.spec.ts`，确认 targeted E2E 5 passed。

## Verification Log

- Backend integration: `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport` -> `BUILD SUCCESSFUL in 34s`，15 tests passed。
- Frontend unit: `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx` -> 2 files / 11 tests passed。
- Frontend typecheck: `pnpm --dir web-admin typecheck` -> passed。
- Targeted E2E: 首次复跑 `admin-agent-runs.spec.ts` 暴露 AR-002 sidebar helper 非幂等；修复后同环境复跑 -> 5 passed in 17.1s。
- E2E truth grep: no `test.only` / `test.skip` / `test.fixme` / `waitForTimeout` / write-API fallback / retry mask hits。
- Hygiene: `git diff --check` -> passed。
- Runtime cleanup: 临时 backend/frontend/bff 端口 `16443` / `15174` / `13501` 已停止且无 listener。

## Acceptance Criteria

- Replay detail 不再为无 turn 证据的普通 run 返回空 conversation object。
- 真实 turn/result-contract 深链仍被后端、前端单测和 E2E 覆盖。
- 所有 agent-runtime 计划文档状态一致，不再把已完成能力描述为下一轮增强。
