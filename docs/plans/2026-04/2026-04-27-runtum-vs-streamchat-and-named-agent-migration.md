# `turnService.runTurn` vs `chatService.streamChat` — 改进对比 + named-agent 长期迁移方案

**日期**：2026-04-27
**状态**：A.3 + A.4 + A.5 + A.6 已落地后的 architectural FAQ + Phase B+ 路线建议
**关联**：
- design 主稿 [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md) v3.3
- 执行 plan [`2026-04-26-conv-turn-svc-phase-a-execution-plan.md`](./2026-04-26-conv-turn-svc-phase-a-execution-plan.md) v4.1

## 0. 这份文档是什么

**不是新设计**。是把"为什么要从 `streamChat` 切到 `turnService.runTurn`"这件事用 12 维对照 + 文档索引固定下来，方便：

1. 后续 session 快速理解 chokepoint 价值，不必重新推导
2. owner 给同事 / reviewer 解释这次改动时有现成材料
3. 评估 named-agent 路径是否要在 Phase B 前迁移（见 §2）

不替代 design v3.3 的细节论证，只做"FAQ + 索引"。

---

## 1. 12 维度改进对照（runTurn 相对 streamChat）

### 一、生命周期 / 契约

| # | 维度 | `streamChat`（旧） | `runTurn`（新） |
|---|------|---------|----------|
| 1 | 入参/返回签名 | `(tenantId, userId, …, ChatRequest, SseEmitter): void` —— 调用方拿不到任何状态 | `(TurnRequest, ResponseSink): TurnOutcome` —— 真实结果传播 |
| 2 | Outcome 传播 | 没有概念，emitter 写完就完 | sealed `TurnOutcome` = `Success / Interrupted / Failed / PendingConfirmation`；finalize / audit / persistence 全部基于真实 outcome 决策 |
| 3 | 生命周期阶段 | 隐式（lambda 一气写到底） | 显式 `beginTurn → executeTurn → finalizeTurn`，每一步可独立扩展 |
| 4 | async 边界 | 散布在 service 内部（lambda + ThreadLocal），lifecycle 与 transport 耦合 | **Q-A.4=A'**：async 仅在 controller/adapter；business lifecycle 内部 sync。outcome 真传到 finalize（v3 → v4 根因，详见 plan v4 §0） |
| 5 | 状态机 / suspend-resume | 通过 ad-hoc `resumeAfterConfirmation(emitter)`，pending 状态散落 | TurnPhase = `ACTIVE → SUSPENDED → ACTIVE → COMPLETED`；`PendingConfirmation` 是 first-class outcome；`TurnSuspendedEvent` ≠ `TurnCompletedEvent` |

### 二、抽象 / 多态

| # | 维度 | `streamChat`（旧） | `runTurn`（新） |
|---|------|---------|----------|
| 6 | 传输抽象 | hardcoded `SseEmitter`；要 WebSocket / sync JSON 必须新写一套 | `ResponseSink` SPI（SSE/WS/sync 三种 adapter 同形态），business core 一份 |
| 7 | 多 chat impl | aurabot 专用 | 设计支持三种 impl 切换：`AuraBotChatService` + 未来 `ACPRuntime` + `group-chat-adapter`（design §3.1 架构图） |
| 8 | InboundMode | 只能"从 request body 创建" | `NEW_FROM_REQUEST` / `EXISTING_MESSAGE_ID`，后者支持群聊事件入口（inbound 已被 ImMessageService 持久化） |

### 三、横切关注点 / chokepoint 收敛

| # | 维度 | `streamChat`（旧） | `runTurn`（新） |
|---|------|---------|----------|
| 9 | side effects 模型 | 持久化/事件/审计内联在多个 SSE 终止点 | `TurnSideEffects` = `{ Persistence, EventEmitter, AuditWriter, MetricsRecorder }` 可注入；Phase A `observeOnly`，Phase B 切 `Production` 不动 business 代码 |
| 10 | 持久化 chokepoint | 8 条物理路径各自决定 sender_type（design §1.1 表）；prod 数据 `sender_type=human:6, system:1, agent:0` 印证分裂 | 1 个 chokepoint，所有 entry 一致；可单点决策 sender_type（Q8 选项 A=统一 `agent`+agentId） |
| 11 | 可观测性 / TurnContext | 没有 turnId、没有 channelSessionId | `TurnContext = { turnId, tenantId, userId, humanMemberId, agentId, channelSessionId, conversationId, inboundMessageId, triageBucket, traceId, beginAt }` —— 一个统一 context 贯穿 metrics/persistence/audit |
| 12 | 未来需求接入面 | 8 个未来需求都得再钻 1565 行的 `doStreamChatInner`（design §1.3 表）：GAP-270 持久化、GAP-273 PreGroundingTriage、ACP Phase 3-4、Memory L1 writeback、跨设备同步、Approval Gate `PER_SESSION`、Multi-channel continuity | 每个 cross-cutting 需求改一处接口面；TurnContext 自然贯穿；订阅 `TurnCompletedEvent` 即可 |

### 一句话总结

`streamChat` 是把 L5 transport / L4 grounding / L3 skill / L1 tool 全部塞进 1565 行的反模式入口；`runTurn` 是把 L5 业务层从中拆出来的真正 chokepoint，让"持久化 / triage / audit / memory / 跨设备同步 / approval gate" 6 个未来需求都收敛到一个接口面而不是 8 条物理路径里各管各。

---

## 2. 长期方案：B.0 named-agent SPI migration（建议 Phase B persistence 之前做）

### 现状（A.5 cutover 后）

`AuraBotController.streamChat` 内根据 agentCode 分派：

- **aurabot 主路径**（agentCode 空 / aurabot）：进 `turnService.runTurn` —— 真 chokepoint，metrics + outcome propagation 都生效
- **named-agent 路径**（agentCode != aurabot）：仍委托 legacy `chatService.streamChat` —— 内部 `asyncTaskExecutor.execute` + `agentChatPort.streamAgentChat(emitter)`，**完全绕过 chokepoint**

也就是说，目前 chokepoint claim 对 named-agent 是装饰性的。Phase A 临时 dual 路径是合理 scaffold；永久 dual 路径是反模式（AGENTS.md `### 长期演进视角` 已硬约束禁止）。

### 目标（B.0 完成后）

所有 agent 流量都走 `turnService.runTurn`，controller 单一路径无分支。

### 改造点

1. **`AgentChatPort` SPI 演进**
   ```diff
   - void streamAgentChat(Long tenantId, String agentCode, ChatRequest request, SseEmitter emitter);
   + TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink);
   ```
2. **`AgentChatPortImpl` 改造**：把内部 `emitter.send(...)` 改为 `sink.on*(...)`；每个终结点返 `TurnOutcome.Success / Failed / PendingConfirmation`（与 `executeAuraBotTurn` 同形态，A.3 已有现成模式可参考）。
3. **`ConversationTurnServiceImpl.runTurn` 内 dispatch**
   ```java
   String agentCode = request.legacyRequest().getAgentCode();
   TurnOutcome outcome = ("aurabot".equals(agentCode) || agentCode == null || agentCode.isBlank())
       ? chatService.executeAuraBotTurn(ctx, request.legacyRequest(), sink)
       : agentChatPort.runAgentTurn(ctx, request.legacyRequest(), sink);
   ```
4. **删除 `AuraBotChatService.streamChat` legacy public wrapper**：不再有外部调用方需要它（controller 切到 turnService 后 named-agent 也会进 turnService）。
5. **`AuraBotController.streamChat` 简化为单路径**：去掉 named-agent 分支，所有 agentCode 都 `turnService.runTurn`。

### 关键判断

- `AgentChatPort` 是我们自己的代码（`platform/src/main/java/com/auraboot/framework/agent/port/AgentChatPort.java` + `agent/service/AgentChatPortImpl.java`），不影响外部消费方。
- 估算：约 80–150 LOC，**独立 PR**，可 review。
- 与 A.3 的 `executeAuraBotTurn` 同形态，模式已经验证。

### 长期演进视角（6 个月后悔检查）

- ✅ **必须做**：Phase B 接 persistence/event/audit 时，如果还留 dual path，每个 chokepoint 特性都要 N×2 接线（aurabot 一次 + named-agent 一次），复杂度爆炸。接抽象一次，所有特性免费覆盖。
- ⚠️ **steel-man 反方**："named-agent 流量极小，先上 Phase B 再说"
  —— 反驳：风险倒挂。迁移成本随后续 wiring 数量线性增长，越拖越贵；且 Phase B 的 persistence 一旦混入 dual 路径，未来再纠正 = 改两套持久化 + 数据 backfill。
- 🚫 **chokepoint 装饰化反模式**：AGENTS.md `### 长期演进视角` 已明确禁止 "接口契约 / chokepoint claim 装饰化（后续阶段才真用）"。

### 建议时序

```
Phase A.7 收尾验收
   ↓
B.0 named-agent SPI migration（独立 PR，~80-150 LOC）
   ↓
B.1 真 Persistence（替换 NOOP）
B.2 EventEmitter / AuditWriter 接入
B.3 /execute pendingTurnId 端到端契约（design §3.10）
...
```

把 B.0 当 Phase B 的入场券。

---

## 3. 文档索引：哪些章节覆盖哪些问题

### `2026-04-26-conversation-turn-service-design.md` v3.3（主设计稿）

| 想了解 | 看哪一节 |
|--------|---------|
| 当前 chat 持久化为什么是分裂的（8 条物理路径，sender_type 三分裂） | §1.1 |
| AuraBotChatService 1565 行为什么是反模式（4 层错配） | §1.2 |
| 8 个未来需求都会再次进来动这个文件 | §1.3 |
| 前端代写持久化 + 服务端 SSE 不写的真实调用链 | §1.4 |
| 为什么 4 种 patch 方案都治标不治本 | §2 |
| patch 路径的 6 个月成本累加 | §2.2 |
| 目标架构 ASCII 图（含三 impl + Phase 范围） | §3.1 |
| 引入 ConversationTurnService 后 8 维度对比 | §3.2 |
| 与 ACP canonical 5 层下沉的契合 | §3.3 |
| 接口形态 + TurnPhase 状态机 + finalizeTurn 伪码 | §3.4 |
| 8 条入口分别在 chokepoint 下的归一方式 | §3.5 |
| sender_type 决策（Q8 选项 A/B/C） | §3.6 |
| channelSessionId 来源 | §3.7 |
| IM/AuraBot Sender Identity + humanMemberId 服务端注入 | §3.8 / §3.8.1 |
| Phase A 真实零行为边界 | §3.9 |
| `/execute` continuation 端到端契约（pendingTurnId / ChatSessionStore key 迁移） | §3.10 |
| 三阶段路径（Phase A / B / C） | §4 |
| 风险评估与缓解 | §5 |
| patch vs refactor 决策矩阵 + 6 月总成本 | §6 |
| 推荐结论 + 反对意见 steel-man | §7 |
| 决策点清单（owner 已拍板 9 项 + 4 项偏好默认） | §8 |
| Phase A/B 完成后的可观测行为断言 | §11 |

### `2026-04-26-conv-turn-svc-phase-a-execution-plan.md` v4.1（执行 plan）

| 想了解 | 看哪一节 |
|--------|---------|
| v1→v4 演进总结 | §0 |
| v3 → v4 根因（async 边界从未明确导致 finalize 抢跑 / SPI bypass / chokepoint 装饰化 / SSE 假通过） | §0 |
| owner 决策（Q-A.4 sync core / Q-A.5 直接 cut over / Q-A.6 legacyRequest / Q-A.7 真实现 SPI） | §1 |
| Phase A v4 总览（A.1 / A.2 / A.2b / A.3 / A.4 / A.5 / A.6 / A.7） | §2 |
| Pre-condition checks | §3 |
| A.2 SPI v4 微调 + SseResponseSink + TurnRequest.legacyRequest | §4 |
| A.2b SSE pre-refactor baseline 录制 + sha256 锁定 | §5 |
| A.3 chatService split 同步 core（核心高风险 PR） | §6 |
| A.4 ConversationTurnServiceImpl 真 runTurn | §7 |
| A.5 controller cutover（无 shadow） | §8 |
| A.6 Spring config + Micrometer | §9 |
| A.7 验收（pre-refactor baseline diff + worktree 前端 E2E + 后端身份校验） | §10 |
| PR 划分（5 PR） | §11 |
| 风险 + 缓解 | §12 |
| Next Session Checklist（worktree 验证 / baseline 恢复 / A.3 真实 scope inventory） | §14 |

### 上位架构参考

| 想了解 | 在哪 |
|--------|------|
| 5 层下沉 canonical（L0-L5） | `ACP-Architecture.md §2.1` |
| Turn Lifecycle 10-Stage Loop | `ACP-Architecture §4.6` / `runtime-core.md` |
| Channel Gateway（多入口 transport adapter） | `ACP-Architecture §6.1` |

---

## 4. 已落地 commits（截至 2026-04-27）

| Commit | 内容 | 范围 |
|--------|------|------|
| `36715e55` | A.1 SPI + DTOs（ConversationTurnService 接口 + TurnContext / TurnRequest / TurnOutcome / ResponseSink / InboundMode / TurnSideEffects） | feat branch |
| `7234fce2` | A.2 SseResponseSink + ResponseSink SPI v4 update + TurnRequest.legacyRequest | feat branch |
| `d0766d79` | A.2b SSE pre-refactor baseline sha256 lock | OSS main |
| **`9cf276e4`** | **A.3 chatService split — sync core executeAuraBotTurn**（38 个 send* call site → sink.on* + 9 helpers 删除 + streamTextContent dead code 清理） | feat branch |
| **`383cd23b`** | **A.4 + A.5 + A.6 — runTurn impl + controller cutover + Spring config**（ConversationTurnServiceImpl + ConversationTurnConfig + Micrometer counters + AuraBotController 单一异步入口） | feat branch |

剩余：A.7 完整验收（plan §10）—— 4 scenario SSE diff（已部分跑过 pass）+ sender_type 分布 + worktree 前端 E2E + 后端集成测试。

---

## 5. 验收信号（A.4-A.6 已跑通的端到端证据）

| 信号 | 数值 | 说明 |
|------|------|------|
| `aurabot_turn_begin_total{phase=A}` | 0 → 1 → 3（每次 /chat/stream +1） | turnService.runTurn 被 controller 调到 |
| `aurabot_turn_end_total{phase=A}` | 0 → 1 → 3 | finalizeTurn 在所有 outcome 路径都 fire |
| SSE 字节流（4 scenario） | `chunk` + `done` 与 baseline 一致；`tool_start` `tool_result` shape `(input/toolId/toolName)` `(result/success/toolId)` 完全相等 | A.2b sha256 baseline 验证通过 |
| 调用链 | controller → SseResponseSink → turnService.runTurn → executeAuraBotTurn → finalizeTurn → metrics | sync core + async-only-at-boundary 模型实测生效 |

---

## CHANGELOG

- 2026-04-27 创建：A.3 + A.4-A.6 落地后写下来防止下次 session 重新推导
