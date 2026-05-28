# B.0 named-agent SPI migration — execution plan v1

**日期**：2026-04-27
**分支**：`feat/named-agent-spi-migration`（off `2a97f3c8` Phase A 验收报告 head）
**目标**：让 `turnService.runTurn` 真的成为唯一入口；named-agent 与 aurabot 都走同一 chokepoint。
**关联**：
- 设计 + 路线 [`2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md`](./2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md) §2
- Phase A 验收 [`2026-04-27-conv-turn-svc-phase-a-acceptance-report.md`](./2026-04-27-conv-turn-svc-phase-a-acceptance-report.md) §5

## 0. 范围 / 不范围

**做**：
- `AgentChatPort` SPI 演进（`streamAgentChat(emitter)` → `runAgentTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome`）
- `AgentChatPortImpl` 内部 12 个 send/emitter 站点改 sink.on*
- `ConversationTurnServiceImpl.runTurn` 内 dispatch by agentCode
- `AuraBotController.streamChat` 单路径
- `AuraBotChatService.streamChat` 公共入口删除
- `AuraBotAgentRoutingTest` 5 个 case 迁到 `ConversationTurnServiceImplDispatchTest`

**不做**（明确推迟）：
- `/execute` continuation —— Phase B B.6 才做（continue using legacy `resumeAfterConfirmation`）
- `runAgentTurn` 的 `PendingConfirmation` 路径 —— 当前 `AgentChatPortImpl` 没有写工具/确认场景；返 `Success` / `Failed` 即可
- 真 Persistence —— 仍 NOOP
- 群聊 / WebSocket / ImAiService 入口 —— Phase B+ 单独 group-chat-adapter sub-design

## 1. 决策点

| Q | 决策 | 理由 |
|---|------|------|
| Q-B0.1 SPI 演进方式 | **clean break**，不留 deprecation bridge | dev 阶段允许破坏（feedback_dev_stage_breaking_ok）；保留双 API 只会让评审复杂化、调用点遗漏 |
| Q-B0.2 `streamAgentChat` 是否保留 default method | **删除**，唯一签名 `runAgentTurn` | 同上，`AgentChatPortImpl` 是唯一实现 |
| Q-B0.3 `runAgentTurn` 是否需要 `PendingConfirmation` 出口 | **不需要**（当前 impl 不发 confirm 事件） | A.3 调研：`AgentChatPortImpl` 只有 `sendError` ×7 + `sendChunk` ×1 + `sendDone` ×1 = 9 业务终结点 + 3 helper 私有方法（共 12 site）。无 tool confirmation 场景 |
| Q-B0.4 dispatch 放哪 | **`ConversationTurnServiceImpl.runTurn`** 内分支，不放 controller | controller 单一职责（transport），dispatch 是 business 决策 |
| Q-B0.5 `agentExists` 检查时机 | **dispatch 之前**，agent 不存在 → `sink.onError` + `TurnOutcome.Failed` + finalize 走 audit | 与 chokepoint claim 一致：所有失败都经 finalize，不绕过 |
| Q-B0.6 是否提交配套测试 | **必须**：迁 `AuraBotAgentRoutingTest` 5 case 到 `ConversationTurnServiceImplDispatchTest` | AGENTS.md "没有测试的代码 = 没有完成" 红线 |

## 2. 实施顺序（建议合一个 PR，可拆 2 commit）

### Commit 1: SPI evolution + impl refactor

1. `AgentChatPort.java`：删 `streamAgentChat`，加 `runAgentTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome`
2. `AgentChatPortImpl.java`：
   - `streamAgentChat` 整段重写为 `runAgentTurn`
   - 12 send/emitter 站点 → sink.on*
   - 每个终结点返 `TurnOutcome.Success` / `Failed`
   - 删 `sendChunk` / `sendDone` / `sendError` 3 个 private helper

### Commit 2: dispatch + controller + cleanup + test

3. `ConversationTurnServiceImpl.java`：runTurn 内根据 `agentCode` 分派
   - `aurabot` / `null` / `blank` → `chatService.executeAuraBotTurn(ctx, legacyRequest, sink)`
   - 其他：`agentChatPort.agentExists` 校验；不存在 → `Failed`；存在 → `agentChatPort.runAgentTurn(ctx, legacyRequest, sink)`
4. `AuraBotController.java`：删 named-agent 分支，单一路径调 `turnService.runTurn`
5. `AuraBotChatService.java`：删 `public void streamChat(...)` 公共入口（约 -55 LOC）；保留 `resumeAfterConfirmation`、`executeAuraBotTurn`、内部 helpers
6. `AuraBotAgentRoutingTest.java`：→ `ConversationTurnServiceImplDispatchTest.java`，5 case 改为 `turnService.runTurn` 调用 + verify `agentChatPort.runAgentTurn` mock 行为

## 3. Test 计划

`ConversationTurnServiceImplDispatchTest`（基于现有 5 case 迁移 + 补 1 case）：

| # | scenario | 验证 |
|---|----------|------|
| 1 | agentCode=aurabot | dispatch → `chatService.executeAuraBotTurn`；`agentChatPort` 不被调 |
| 2 | agentCode=null/blank | 同上（fallthrough 到 aurabot） |
| 3 | agentCode=nonexistent，agentExists=false | sink.onError + Failed；`runAgentTurn` 不被调 |
| 4 | agentCode=foo，agentExists=true | dispatch → `agentChatPort.runAgentTurn`；`executeAuraBotTurn` 不被调 |
| 5 | agentChatPort=null（runtime 没装） + agentCode=foo | sink.onError("AgentChatPort not available")，行为对齐 A.5 fallback |
| 6 | runAgentTurn 抛异常 | runTurn try/catch 捕获 → Failed；finalize 仍调 |

## 4. 验收

| 检查 | 必过 |
|------|------|
| `./gradlew :compileJava --rerun-tasks` 0 errors | ✅ |
| `./gradlew :compileTestJava` 0 errors | ✅ |
| Grep gate：`grep -rn 'streamAgentChat\|chatService.streamChat\(' platform/src/main` 0 hits | ✅ |
| `ConversationTurnServiceImplDispatchTest` 6/6 pass | ✅ |
| `AuraBotChatServiceTracePayloadTest` / `ChatToolResolverIsReadOnlyTest` / `AuraBotAgentResolverIntegrationTest` 仍 pass | ✅ |
| 后端 republish + restart 后 SSE byte parity（aurabot 路径 4 scenario） | ✅ |
| `aurabot_turn_*` metrics 仍 +1 per /chat/stream | ✅ |
| named-agent path（如有 fixture）也 +1 | ✅ if testable |

## 5. 风险

| 风险 | 缓解 |
|------|------|
| `AgentChatPortImpl.runAgentTurn` 内部 LLM 调用栈漏 send 替换 | grep gate + compile + test |
| `runTurn` dispatch 引入 NPE（`legacyRequest()` 在 named-agent path 上 nullable） | dispatch 前判 `request.legacyRequest() != null`；现有 controller 必传 legacyRequest |
| named-agent path 没现成 fixture 可跑 SSE smoke | 至少跑 mock 单测覆盖；live 验证延后到 ACP runtime fixture 准备好 |
| `ChatSseContext.setEmitter` 旧路径内部还有调用 | grep `ChatSseContext.set` 确认；`AgentChatPortImpl` 内不应调（aurabot-specific） |

## 6. 总成本估算

- 代码：~150-180 LOC（含测试）
- 时间：1-2 hour（参考 A.3 替换 38 send 站点用了类似时间）
- PR review：medium（核心是 SPI 演进；逻辑变化清晰）

## CHANGELOG

- 2026-04-27 v1 初始化
