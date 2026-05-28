# ConversationTurnService Phase B 验收报告

**日期**：2026-04-28
**状态**：B.0 + B.0+tests + B.1 + B.2 + B.3 + B.6 全部落地，Phase B 收口
**关联**：
- 设计稿 [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md) v3.3
- B.0 路线 [`2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md`](./2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md)
- B.1 plan [`2026-04-27-b1-persistence-and-frontend-contract-plan.md`](./2026-04-27-b1-persistence-and-frontend-contract-plan.md)
- Phase A 验收 [`2026-04-27-conv-turn-svc-phase-a-acceptance-report.md`](./2026-04-27-conv-turn-svc-phase-a-acceptance-report.md)

## 0. 摘要

Phase B 把 `TurnSideEffects` chokepoint 从 NOOP 全部接成 real impl，并把 `/chat/stream` + `/execute` 两个端点都收敛进 `turnService.runTurn` / `resumeTurn`。**chokepoint claim 完整落地**：每一条 chat 流量（aurabot 主路径 + named-agent + resume）都经过同一个生命周期，每一个横切关注点（持久化 / 事件 / 审计 / 度量）只接一次。

| 指标 | 状态 |
|------|------|
| `TurnSideEffects.Persistence` real | ✅ B.1 `AuraBotTurnPersistence` 写 `ab_im_message` |
| `TurnSideEffects.EventEmitter` real | ✅ B.2 `SpringEventEmitter` 包 `ApplicationEventPublisher` |
| `TurnSideEffects.AuditWriter` real | ✅ B.3 `LoggingAuditWriter` 结构化 WARN 日志 |
| `TurnSideEffects.MetricsRecorder` real | ✅ A.6 Micrometer counters |
| named-agent 也走 chokepoint | ✅ B.0 `AgentChatPort.runAgentTurn` |
| `/execute` resume 也走 chokepoint | ✅ B.6 `turnService.resumeTurn` |
| `appendUserMessage` / `appendAssistantMessage` 删除 | ✅ B.1（前端 + 后端 + DB schema） |
| `ChatSessionStore` key 迁 turnId | ✅ B.6 |
| `confirm_required` SSE event 携 `pendingTurnId` | ✅ B.6 |

## 1. PR 链 + commit 时间线

| 阶段 | Commit | 分支 |
|------|--------|------|
| **B.0** named-agent SPI migration | `40e87208` | `feat/named-agent-spi-migration` |
| **B.0+** finalize + SSE 单测 | `12a982e2` | 同上 |
| **B.1 plan** | `fe98bfe8` | 同上 |
| **B.1** real Persistence + 前端契约切换 | `d31215cb` | `feat/conv-turn-svc-b1-persistence` |
| **B.2 + B.3** SpringEventEmitter + LoggingAuditWriter | `58303219` | `feat/conv-turn-svc-b2-events-audit` |
| **B.6** /execute pendingTurnId + ChatSessionStore turnId | `d2e91ebf` | `feat/conv-turn-svc-b6-resume` |

合计 6 个 commit / ~2200 LOC（含测试 + 文档 + 前端）。

## 2. 单测覆盖

`./gradlew :test --tests "com.auraboot.framework.conversation.*"` → **43 PASSED / 0 FAILED**

| Test class | cases | 覆盖范围 |
|------------|-------|---------|
| `ConversationTurnServiceImplDispatchTest` | 6 | runTurn agentCode dispatch（B.0） |
| `ConversationTurnServiceImplFinalizeTest` | 7 | finalizeTurn 5 outcome 分支 + null defense + side-effect-throw（A.4 + B.0+） |
| `ConversationTurnServiceImplResumeTest` | 6 | resumeTurn APPROVED/DENIED/CANCELLED/pending-missing/identity-mismatch/null-pendingTurnId（B.6） |
| `AuraBotTurnPersistenceTest` | 6 | persistInbound/persistOutbound 各 outcome + dedup + defensive NOOP（B.1） |
| `SpringEventEmitterTest` | 3 | 真 ApplicationEventPublisher 投递 Completed/Suspended（B.2） |
| `LoggingAuditWriterTest` | 4 | 结构化 WARN log 字段 + null 防御（B.3） |
| `SseResponseSinkTest` | 11 | 字节级别对齐 baseline（A.2 / B.6 含 pendingTurnId 字段）|

## 3. 端到端验收信号

| 验证 | 结果 |
|------|------|
| `aurabot_turn_begin/end` metrics | 每次 `/chat/stream` +1，每次 `/execute` +1（B.6 后） |
| `ab_im_message` row 增长 | 每次 chat +2（inbound human + outbound agent，sender_id=resolved agentId） |
| `client_msg_id` 幂等 | 重发同 ID 仍 1 行 |
| SSE byte stream 对照基线 | 4/4 scenario events 序列一致；4 种 event JSON top-keys（chunk/done/tool_start/tool_result）一致；新增 confirm_required.pendingTurnId 字段（baseline 之外） |
| 前端 aurabot E2E | 78/79（A.7.5 baseline，B.x 后未跑全套；建议 Phase C 入场前再跑一次） |

## 4. chokepoint 完整调用链（Phase B 收口形态）

```
controller (/chat/stream OR /execute)
    │  snapshot identity (MetaContext) before async hop
    │  build SseResponseSink(emitter)
    │  asyncTaskExecutor.execute(() -> {
    │
    ▼
turnService.runTurn(TurnRequest, sink)         /chat/stream
turnService.resumeTurn(pendingTurnId, ...)     /execute
    │
    │  beginTurn -> Persistence.persistInbound (ab_im_message human row)
    │            metricsRecorder.recordTurnBegin
    │  dispatch:
    │    aurabot   -> chatService.executeAuraBotTurn(ctx, request, sink)
    │    named     -> agentChatPort.runAgentTurn(ctx, request, sink)   (B.0)
    │    resume    -> chatService.resumeApprovedTurnFromPending(ctx, pending, sink)  (B.6)
    │
    ▼
TurnOutcome (Success / Interrupted / Failed / PendingConfirmation)
    │
    │  finalizeTurn(ctx, outcome):
    │    Success / Interrupted -> Persistence.persistOutbound (ab_im_message agent row)
    │                           EventEmitter.emit(TurnCompletedEvent)              (B.2)
    │    Failed                -> AuditWriter.writeFailure (structured WARN log)   (B.3)
    │                           EventEmitter.emit(TurnCompletedEvent)
    │    PendingConfirmation   -> Persistence.persistOutbound iff partial nonblank (B.1 P1.4)
    │                           EventEmitter.emit(TurnSuspendedEvent)              (B.2)
    │    always                -> MetricsRecorder.recordTurnEnd                    (A.6)
    ▼
controller closes SSE (sink.onDone / onError already terminated emitter)
```

## 5. 已知遗留 + Phase C 候选

| 项 | 性质 | 优先级 |
|----|------|--------|
| AGENTS.md `### 长期演进视角` 还没把 chokepoint 完整形态写进硬约束 | 文档 | 中 — 建议补 |
| `TurnContext.traceId` 仍 null（A.4 留空，B.x 未补） | 可观测性 gap | 中 — 让 outbound row card_payload 带 traceId 需要 trace 链路 |
| `TurnSuspendedEvent` 没有消费方 | 待用 hook | 低 — Phase C 接 memory L1 时订阅 |
| 群聊 `@mention` (#7) / WebSocket `ImAiService` (#8) 入口未归一 | Phase B+ | 低 — group-chat-adapter sub-design 已规划，无紧迫驱动 |
| 公开 `ConversationTurnService.resumeTurn` SPI 不带 toolId | API 形态 | 低 — 当前一 turn 一 pending；多 pending 时再加 toolId 字段 |
| `ConfirmDecision` 没接 CANCELLED 路径的前端 trigger | 前端 UX | 低 — 当前 cancelTool 走 DENIED |

## 6. 下一阶段建议

**Phase C：插件化插入 triage / ACP / memory（design §4 Phase C）**。具体可拆：

1. **C.1 PreGroundingTriage 接入**：`runTurn` 内在 dispatch 之前调 PreGroundingTriage（OSS 已有 GAP-273 SPI），结果写 `TurnContext.triageBucket`。
2. **C.2 Memory L1 writeback**：订阅 `TurnCompletedEvent` 的 listener，把 turn 结果写进 active memory。
3. **C.3 ACP Phase 3-4 集成**：把 aurabot tool loop 替换为 ACP Stage Lifecycle（design §1.3 列的"3 个月内"需求）。

Phase C 各步独立，可分别立项。

## 7. 决策矩阵回顾

design v3.3 §6 "patch vs refactor 6 个月成本"预测的 5 项质量收益（架构一致性 / 风险可控 / 未来变更可预测 / 维护性 / 测试可观测）现在已经全部兑现：

| 收益 | 兑现证据 |
|------|---------|
| 架构一致性 | 1 个 chokepoint vs 8 条物理路径（design §1.1 prod 数据 sender_type 三分裂消除 → B.1 全部统一 agent + agentId） |
| 风险可控 | A.0 → A.7 → B.0 → B.1 → B.2/B.3 → B.6 每一步 PR 独立可回滚；commit 全程 byte-stream 对照 sse-baseline-2026-04-26.sha256 |
| 未来变更可预测 | C.1/C.2/C.3 都有清晰单点接入面（dispatch 前调 triage / 订阅 TurnCompletedEvent / 替换 chat impl） |
| 维护性 | AuraBotChatService 1565 LOC → 1411 LOC（B.6 后），删除 streamChat / resumeAfterConfirmation 两个 public wrapper + 9 个 send* helper + 1 个 dead method |
| 测试可观测 | 43 单测覆盖 dispatch / finalize / resume / sink / persistence / event / audit 全套，Phase A 验收前是 0 |

## CHANGELOG

- 2026-04-28 v1 创建：B.6 落地后 Phase B 收口
