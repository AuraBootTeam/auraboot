# Conversation Turn Replay + ResultContract Deep Link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Agent Replay detail 中补齐 ConversationTurn 全量回放投影和 ResultContract 深链，让一次 agent run 能追溯到用户消息、turn 上下文、LLM/工具输出契约和 action 审计。

**Architecture:** 不新增对话入口，不新增执行 runtime，不绕过 `ConversationTurnService`。后端只在 `AgentRunController` 的 read model 聚合现有审计数据：`ab_agent_run -> ab_agent_task.input_data -> ab_im_message` 还原 turn，`ab_agent_action` 派生可渲染 `ResultContract`。前端复用现有 `AgentRunDetailDrawer` 和 `ResultContractView`，增加 Conversation 与 Result tabs 及 action-to-contract anchors。

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, JUnit integration tests, React/TypeScript, Vitest, Playwright targeted E2E.

---

## 方案讨论

### Problems

- Replay detail 目前只有 run/action/interrupt/child/BIF/trace，缺少 chokepoint 级的 `ConversationTurn` 证据。
- `ResultContract` 只在 SSE 中即时发送，Replay UI 不能从 action timeline 跳到对应的外部输出契约。
- 真实数据分散：turn id、conversation id、inbound message id 存在 `ab_agent_task.input_data`，用户/助手消息存在 `ab_im_message`，run/status/cost 在 `ab_agent_run`，工具审计在 `ab_agent_action`。
- 没有 `ab_conversation_turn` 专表，所以本轮不能假装存在完整事件源；必须做只读 projection，并把不可恢复的边界讲清楚。

### Target

- `GET /api/admin/agent-runs/{runId}` 返回 `conversationTurn`：
  - `turnId`、`taskPid`、`conversationId`、`inboundMessageId`
  - user message、triage bucket/confidence/reason codes
  - inbound/outbound `ab_im_message` rows
  - run outcome/status 与 result contract id 列表
- 同一个 detail payload 返回 `resultContracts`：
  - 每个 action 一个 stable `contractId`
  - contract shape 兼容前端 `ResultContractView`
  - action row 可深链到对应 ResultContract
- 所有查询显式 tenant-scoped；跨租户 message/task/contract 不可见。

### Non-Goals

- 不新增 `ab_conversation_turn` 写模型。
- 不修改 `ConversationTurnService.runTurn/resumeTurn` 执行语义。
- 不把 SSE replay、fork-from-step、time travel 加进本轮。
- 不用 generic fallback 或 legacy adapter 补数据。

## 文件结构

- Modify: `platform/src/main/java/com/auraboot/framework/agent/controller/AgentRunController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/dto/replay/AgentRunDetail.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/dto/replay/AgentActionItem.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/dto/replay/AgentConversationMessageItem.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/dto/replay/AgentConversationTurnReplay.java`
- Create: `platform/src/main/java/com/auraboot/framework/agent/dto/replay/AgentResultContractItem.java`
- Modify: `platform/src/test/java/com/auraboot/framework/integration/agent/AgentRunControllerIntegrationTest.java`
- Modify: `web-admin/app/plugins/core-aurabot/services/agentRunsApi.ts`
- Modify: `web-admin/app/plugins/core-aurabot/components-internal/AgentRunDetailDrawer.tsx`
- Modify: `web-admin/app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx`
- Modify: `web-admin/tests/e2e/aurabot/admin-agent-runs.spec.ts`

## Task List

### Task 1: Backend DTO Contract

- [x] Create `AgentConversationMessageItem`.
- [x] Create `AgentConversationTurnReplay`.
- [x] Create `AgentResultContractItem`.
- [x] Extend `AgentRunDetail` with `conversationTurn` and `resultContracts`.
- [x] Extend `AgentActionItem` with `resultContractId`.

### Task 2: Backend Projection

- [x] In `AgentRunController.detail`, load conversation turn projection from `ab_agent_run.task_id -> ab_agent_task.input_data`.
- [x] Load turn messages from `ab_im_message` by exact `inboundMessageId` and `client_msg_id = out-${turnId}`.
- [x] Derive one `ResultContract` projection per `AgentActionItem`.
- [x] Ensure every SQL statement includes `tenant_id = ?`.

### Task 3: Backend Integration Tests

- [x] Seed `ab_agent_task.input_data` with `turnId/conversationId/inboundMessageId/triageBucket/userMessage`.
- [x] Seed inbound and outbound `ab_im_message` rows.
- [x] Assert detail returns `conversationTurn` with exact messages and turn metadata.
- [x] Assert action rows expose `resultContractId` and matching `resultContracts`.
- [x] Assert cross-tenant messages are not linked.

### Task 4: Frontend Contract + Drawer UI

- [x] Extend `agentRunsApi.ts` types.
- [x] Add drawer tabs: `Overview`, `Conversation`, `Results`, existing `Live Stream`.
- [x] Render turn metadata and message tape in `Conversation` tab.
- [x] Render `ResultContractView` list in `Results` tab.
- [x] Add action-row `Open Result` control that selects the matching ResultContract.

### Task 5: Frontend Unit Tests

- [x] Update drawer detail fixture with new fields.
- [x] Test `Conversation` tab renders turn id, user message, inbound/outbound messages.
- [x] Test `Open Result` jumps from action row to `Results` tab and renders `ResultContractView`.

### Task 6: Targeted E2E

- [x] Seed conversation task/message rows in `admin-agent-runs.spec.ts`.
- [x] Assert detail API returns `conversationTurn` and `resultContracts`.
- [x] Assert UI Conversation tab and Results tab render concrete seeded values.
- [x] Preserve E2E truth constraints: no write API fallback for the read-only replay path.

### Task 7: Verification + Truth Review

- [x] Run backend integration target.
- [x] Run frontend unit target and `pnpm typecheck`.
- [x] Run targeted Playwright spec if local stack is valid.
- [x] Run `/e2e-truth` audit before any coverage claim.
- [x] Run `git diff --check`.

## Verification Log

- Backend integration: `./gradlew :test --tests com.auraboot.framework.integration.agent.AgentRunControllerIntegrationTest -x jacocoTestReport` — 14 tests passed.
- Frontend unit: `pnpm --dir web-admin exec vitest run app/plugins/core-aurabot/__tests__/AgentRunDetailDrawerLiveStream.test.tsx app/plugins/core-aurabot/__tests__/AgentRunsPage.test.tsx` — 2 files, 11 tests passed.
- Frontend typecheck: `pnpm --dir web-admin typecheck` — passed.
- Targeted E2E: `npx playwright test tests/e2e/aurabot/admin-agent-runs.spec.ts --project=chromium --reporter=line --no-deps` against current worktree backend on `:16443` and temporary Vite/BFF on `:15174/:13501` — 5 tests passed.
- E2E truth audit: no executable `test.skip/test.fixme/test.only`, `waitForTimeout`, write API fallback, or retry override hits in `admin-agent-runs.spec.ts`.
- Diff hygiene: `git diff --check` — passed.

## Acceptance Criteria

- A replay detail payload can reconstruct a turn from task/input-message/out-message without manual DB inspection.
- Every action in the timeline has a stable result contract anchor.
- Frontend can move from action row to ResultContract rendering in one click.
- Cross-tenant task/message rows never appear in another tenant's run detail.
- Tests cover backend projection, frontend rendering, and at least one real E2E path.
