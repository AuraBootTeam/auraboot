# Phase D — Multi-Channel Adapters: IM Events / WebSocket / Mobile

> **Status**: v2 (2026-04-30) — owner locked all 6 decisions to α; §8 implementation sequence filled in.
> **Predecessor**: design v3.3 §3.5 entry-adapter mapping (Phase A/B/C completed; D was deferred via Q13=α).
> **Reads alongside**: [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md), [`2026-04-29-c3-acp-phase-3-4-integration-design.md`](./2026-04-29-c3-acp-phase-3-4-integration-design.md).

## 0. 摘要

Phase A/B/C 把 `POST /api/ai/aurabot/chat/stream` 单一入口归一到 `ConversationTurnService.runTurn` 的 sync chokepoint，C.3 系列再把 ACP_RUN/CONTEXTUAL_ANSWER 路由到 ACP runtime — chokepoint claim 在 **web SSE 通道** 已完整兑现。

Phase D 把同一个 chokepoint 推到 **事件驱动 / WebSocket / 跨平台** 通道：

| 入口 (v3.3 §3.5 编号) | 当前实现 | 当前归属 |
|---|---|---|
| #7 群聊 @mention | `GroupChatAgentRouter.onMessageSent` (@Async) → `AgentReplyTask.executeReply` (@Async, 327 LOC, handoff chain) | 完全旁路 chokepoint |
| #8 WebSocket @AI（IM panel） | `ImAiService.generateResponse` (@Async eventTaskExecutor) → `ImMessageBroadcaster.publish` | 完全旁路 chokepoint |
| #1 mobile chat（如新增） | 复用 `/chat/stream`（v3.3 Q7 已确认 OSS 无独立 mobile BFF）| chokepoint 已覆盖 |

D 完成后：
- 所有 chat-类流量（无论 SSE / WS / IM-event）经过 `runTurn`，metrics + memory L1 writeback + audit + Triage 一致
- `sender_type` 在 v3.3 Q8 决策上彻底落地（ImAiService 不再写 `system`+0）
- `AgentReplyTask` 的 handoff chain 与 ACP `ab_agent_task` 父子关系合并，跨 channel 的 mission 视图可贯通

非范围：mobile 原生 BFF（v3.3 Q7=否，OSS 不引入）。

---

## 1. 现状对照

### 1.1 入口 #7 群聊 @mention

```
ImMessageSentEvent (Spring event)
  └─> GroupChatAgentRouter.onMessageSent (@Async, 138 LOC)
        ├─ 解析 @mention → agentId
        └─> AgentReplyTask.executeReply (@Async, 327 LOC)
              ├─ load AgentDefinition
              ├─ SSE typing indicator 通过 SseEmitterManager.sendToUsers(humanMemberIds, ...)
              ├─ build context (replyContext)
              ├─ resolve handoff tools
              ├─ provider.chat(...)  ← 直接调 LLM，不走 GroundingService / Skill
              ├─ 解析响应 → 写 ab_im_message (sender_type='agent', sender_id=agentId)
              ├─ 若 handoff tool 命中 → 递归 executeReplyWithDepth(depth+1)
              └─ MAX_HANDOFF_DEPTH=5
```

**与 chokepoint 的差距**：
- 完全没有 `ab_agent_task` / `ab_agent_run` 写入 — ACP 看不到这些 turn
- Triage 不走（每次都直接调 LLM，浪费 LIGHT_CHAT 流量）
- L1 memory writeback (C.2) 不 fire — 群聊里 agent 学到的东西不进 memory pool
- Approval gate 不走 — 群聊里 agent 调 write tool 没有审批拦截
- handoff chain 与 ACP `ab_agent_task.parent_id` 体系平行，互不可见

### 1.2 入口 #8 WebSocket @AI（IM panel）

```
ImMessage (用户在 IM panel 写消息触发)
  └─> ImAiService.generateResponse (@Async eventTaskExecutor, 169 LOC)
        ├─ load chat context (recent messages)
        ├─ resolve agent (默认 aurabot agentId)
        ├─ provider.chat(...)  ← 直接调 LLM
        ├─ 写 ab_im_message (sender_type='system', sender_id=0) ← v3.3 Q8 决议要改
        └─ ImMessageBroadcaster.publish(memberIds, frame) ← WebSocket 推送
```

**与 chokepoint 的差距**：同上 #7 + 还有 v3.3 Q8 sender_type 分裂（'system' vs 'agent'）。

### 1.3 ResponseSink 当前面 vs Phase D 需要的

| 通道 | 现有 sink | 状态 |
|---|---|---|
| HTTP SSE | `SseResponseSink` | ✓ Phase A.3 已实现 |
| WebSocket | — | 需要 `WsResponseSink` |
| IM 广播 (sender 多端同步) | — | 需要 `BroadcastResponseSink`（封 `ImMessageBroadcaster`） |
| Sync JSON | — | v3.3 §3.5 #2 标记不归一；Phase D 不做 |
| Mobile push | — | OSS 无独立 mobile BFF；不做 |

---

## 2. 决策点（owner 拍板）

### Q-D.1 异步入口的 chokepoint 归一方式

@Async 事件监听器（GroupChatAgentRouter / ImAiService）是 fire-and-forget 模型；chokepoint `runTurn` 是 sync return TurnOutcome。

| 选项 | 描述 | Pro | Con |
|------|------|-----|-----|
| **α 提取 sync core + 包 @Async wrapper**（建议）| GroupChatAgentRouter / ImAiService 在 @Async 方法内调 `turnService.runTurn(...)` 同步执行；外层 @Async 提供 fire-and-forget 语义 | 与 Q-A.4=A' / Q-C3.2=β 哲学完全一致；chokepoint 行为不变 | @Async 线程池 占用时长 = 完整 turn 时长（含 LLM）；需要确认 `eventTaskExecutor` pool size 够 |
| β fire-and-forget runTurn 变体 | 新增 `runTurnFireAndForget(TurnRequest)` 内部 dispatch async；chokepoint 多一个 entry | 不占调用方线程 | 多一个 surface；observability 异步弱化 |
| γ 不归一 | 群聊事件保持当前实现，只把它包成"通过 chokepoint 写 metric"形式 | 改动最小 | C.3 chokepoint claim 立刻装饰化（与 §3.5 #7 #8 的"延后"一致） |

**倾向 α**。@Async + sync runTurn 是最简洁的 bridge，无新 API 面。

### Q-D.2 非 SSE 通道的 ResponseSink 实现

群聊事件路径不需要"响应流回客户端"——它需要把 LLM 输出**写入 IM 消息表 + 广播给在线成员**。语义不同。

| 选项 | 描述 |
|------|------|
| α 新 `BroadcastResponseSink` | 实现 `onTextChunk` / `onDone` / `onError` 等方法时，缓冲文本 + 在 onDone 一次性写 `ab_im_message` 一行 + `ImMessageBroadcaster.publish` 一次。`onResultContract` / `onToolStart` 转成 IM card 类型消息 |
| β `NoOpResponseSink` + 在 chokepoint 外做 IM 广播 | sink 不做事，TurnRequest 持有 messagePort 引用，turn 结束后 finalizeTurn 写 IM | 简单，但 chokepoint 不再是"唯一 transport surface" |
| γ 复用 `SseResponseSink` 推 WebSocket 帧 | 把 `SseEmitter` 抽象成 `EventChannel`，WS 复用 |

**倾向 α**。BroadcastResponseSink 让 IM 路径与 SSE 路径在 ResponseSink 抽象上对齐；`onTextChunk` 缓冲到 `onDone` 一次写出（IM 不分段流式渲染，只看终态）符合 IM UX。

### Q-D.3 sender_type 统一（v3.3 Q8 收尾）

v3.3 已倾向选项 A（统一 `sender_type='agent'` + `sender_id=agentId`）但**未在 Phase B 落地** —— Phase B+ 单独做。Phase D 是兑现窗口。

| 选项 | 描述 |
|------|------|
| α 全部统一为 `agent` | ImAiService 改写新数据；历史数据 SQL backfill |
| β 保留 `system` for ImAiService | 与群聊路径分裂；前端 UI 维护双套渲染 |
| γ 新增 `aurabot` 枚举 | 多一个语义分裂源 |

**倾向 α**。dev-stage 允许 backfill；OSS 还在演进；不应推迟到 prod 数据落地后再改（典型"推迟该做的重构"红线）。

### Q-D.4 handoff chain 与 ACP `ab_agent_task.parent_id` 关系

`AgentReplyTask` 的 handoff（agent A → agent B 接力）目前是单进程递归 `executeReplyWithDepth(depth+1)`。归一后该如何建模？

| 选项 | 描述 |
|------|------|
| α 每个 handoff = 新 turn = 新 `ab_agent_task` row（with `parent_id`= 上游 task pid） | turn lifecycle 清晰；ACP 的 mission 视图可见 handoff 链 |
| β 一个 turn 包多个 agent 调用 | TurnContext 没办法表示 multi-agent；TurnOutcome 也只一个 finalResponse |
| γ handoff 暂保留递归实现，不进 task 模型 | 改动最小，但 chokepoint claim 在 handoff 链上仍装饰化 |

**倾向 α**。任务建模一致才能让 mission progress / cost / approval 在跨 agent 接力链上贯通（与 Q-C3.1=A 同源理由）。

### Q-D.5 IM 事件的 TurnRequest 字段映射

| TurnRequest 字段 | IM 事件来源 |
|---|---|
| tenantId | `ImMessage.tenant_id` |
| userId | `ImMessage.sender_user_id`（群聊里发起 @ 的人）|
| humanMemberId | 同上 lookup |
| channel | `"im_group"` / `"im_panel"` |
| agentCode | 解析 `@mention` 得到的 agent ID → 反查 agentCode；ImAiService 默认 `"aurabot"` |
| conversationId | `ImMessage.conversation_id` |
| clientMsgId | `ImMessage.client_msg_id`（IM 已有）|
| userMessage | `ImMessage.content` |
| pageContext | null（IM 不带页面上下文） |
| precomputedBucket | null（让 triage SPI 决定） |
| inboundMode | **`PERSISTED_FROM_IM`**（新增 enum 值，区别 NEW_FROM_REQUEST）|

**新增决策**：`InboundMode.PERSISTED_FROM_IM` —— `persistInbound` 不再写新 `ab_im_message` 行（IM 模块已写）；只更新 metadata（triage_bucket / confidence / reason_codes）。

### Q-D.6 mobile 入口

v3.3 Q7 已确认：OSS 无独立 mobile BFF；mobile 复用 web `/chat/stream`。Phase D 是否引入 mobile-specific WebSocket？

| 选项 | 描述 |
|------|------|
| α 不做 | mobile 继续用 SSE；未来如有需求再 sub-design |
| β 借 BroadcastResponseSink + WebSocket 帧 | 复用 IM 通道结构；mobile 客户端订阅 `ab_im_message` 通道 |

**倾向 α**。mobile push 是独立产品决策，不耦合到 chokepoint refactor。

---

## 3. 候选 PR 切片（决策定后细化）

按倾向 α + α + α + α + 字段映射 + α 假设：

| PR | 内容 | 估 LOC | 依赖 |
|----|------|------|------|
| **D.1** `BroadcastResponseSink` 实现 + 单测；`InboundMode.PERSISTED_FROM_IM` enum 值 + `Persistence.persistInbound` 分支处理 | ~200 | — |
| **D.2** `ImAiService.generateResponse` 改造：保留 @Async wrapper；内部走 `turnService.runTurn(TurnRequest, BroadcastResponseSink)`；删原 LLM 直调路径；写 `sender_type='agent' + agentId` 而非 `system` | ~250 (-300) | D.1 |
| **D.3** `GroupChatAgentRouter.onMessageSent` / `AgentReplyTask` 改造：handoff 链改为 task 父子关系（每跳建 `ab_agent_task`，`parent_id` 串起来）；@Async wrapper 调 `turnService.runTurn` | ~400 (-300) | D.1 + Q-C3.1=A 已有的 task 模型 |
| **D.4** `WsResponseSink` 实现（如有 IM 实时打字指示需求）；TYPING SSE event 改成 WS 帧 | ~150 | D.1 |
| **D.5** `sender_type` backfill SQL：一次性 UPDATE 历史 `system+0` 行（按 conversation `metadata.chat_kind` 反查） | ~50 | D.2 |

合计估算：~1050 LOC 新增 / ~600 LOC 删除（净 +450）。每 PR 独立 review。

---

## 4. 单测覆盖策略

每个 D PR 必须配套：

- D.1: BroadcastResponseSink 单测 — 验证 onTextChunk 缓冲到 onDone 一次性写出；onResultContract 转 card；onError 写错误消息
- D.2: ImAiService integration test — 真发 IM 消息 → 验证 `runTurn` 被调；`ab_agent_task` 落库；`ab_im_message` 用 `sender_type='agent'`；ImMessageBroadcaster 收到 frame
- D.3: GroupChatAgentRouter 集成测试 — 群聊 @mention 走 chokepoint；handoff 链每跳建 `ab_agent_task` 父子；mission_id 贯通
- D.4: WsResponseSink 字节级测试 — 验证 TYPING / chunk / done 帧格式
- D.5: backfill SQL dry-run 验证（在 reset-db 后跑）

预计每 PR 5-12 个新单测。

---

## 5. 风险 + 缓解

| 风险 | 缓解 |
|------|------|
| @Async + runTurn sync 占用 eventTaskExecutor pool 太久（一个 LLM call ≈ 5-15s）| 评估 pool size；考虑独立 chokepoint executor；监控 task queue 长度 |
| handoff chain 改成 task 父子后，递归 5 层会建 5 个 task row，DB 压力 | 与 ACP 现有 dispatchChildTasks 一致；handoff depth 已限制 5 |
| backfill SQL 误改非 AuraBot system 消息（v3.3 §3.6 已警告）| 必须先 audit `system + sender_id=0` 行的 metadata 分布；有怀疑就分批跑、加 `WHERE conversation.metadata->>'chat_kind' IN ('aurabot_panel','imai')` 过滤 |
| BroadcastResponseSink 缓冲 `onTextChunk` 到 `onDone` 期间崩溃 → 整段消息丢失 | onError 也写一行 IM 消息（status='failed'），不让 crash 静默 |
| Q-D.6 不做 mobile WS 现在，未来要做时 sink 抽象不够 | ResponseSink default no-op 兜底（C.3b 已建立的模式）；mobile sink 加进来不破坏既有 |

---

## 6. 不在范围（明确推迟）

- mobile 独立 BFF（v3.3 Q7=否；不开新坑）
- IM 跨 channel sync（v3.3 §3.4 提到的多端 read-receipt 同步）— 与 chokepoint 正交
- ACP Phase 5-10（v3.3 §1.4 列的高阶能力）— 与 D 正交
- 把 `/api/ai/aurabot/chat`（非 SSE，§3.5 #2）归一 — 设计仍未 unblock；v3.2 决议保留现状

---

## 7. owner 决策汇总（v2 已 lock 2026-04-30）

| Q | 内容 | **决策** | 关键判断 |
|---|------|---------|---|
| Q-D.1 | 异步 → 同步 bridge | **α** @Async wrapper 调 sync runTurn | 与 Q-A.4=A'/Q-C3.2=β 同源；β fire-and-forget 让 turn lifecycle 异步化破坏 chokepoint observability claim 的真实性；γ 不归一让 chokepoint 在 IM 通道装饰化（C.3 刚修过的反模式） |
| Q-D.2 | 非 SSE sink | **α** BroadcastResponseSink | β 让 chokepoint 不再是"唯一 transport surface"——TurnSideEffects 里的 finalizeTurn 已 emit event/persist，再加一个旁路写消息会让数据流分叉；α 把"缓冲文本到 onDone 一次性写 IM"作为 sink 实现细节，调用方对此无感 |
| Q-D.3 | sender_type 统一 | **α** 全部 agent + sender_id=agentId + backfill | β/γ 都让 sender_type 永远分裂；前端要为 system+0 维护双套渲染；dev-stage（AGENTS.md 红线）允许 backfill，推迟到 prod 后改是"推迟该做的重构"反模式 |
| Q-D.4 | handoff 与 task 父子 | **α** 每跳建 ab_agent_task (parent_id 串接) | β 一个 turn 多 agent 让 TurnContext / TurnOutcome 表达不下；γ 保留递归则跨 channel mission 视图永远破碎；ACP `dispatchChildTasks` 已是同模型，handoff depth=5 受限，写入压力可控 |
| Q-D.5 | IM TurnRequest 字段映射 | **§2.5 表 + 新 InboundMode.PERSISTED_FROM_IM** | persistInbound 不能写新 ab_im_message 行（IM 模块已写）；新 enum 值清晰区分 turn lifecycle 起点 |
| Q-D.6 | mobile WS | **α** 不做 | OSS 无独立 mobile BFF（v3.3 Q7 已确认）；mobile push 是产品决策，与 chokepoint refactor 正交；ResponseSink default no-op 兜底（C.3b 模式）保留未来扩展空间 |

### 6 个月后悔检查

| 决策 | "6 个月后会后悔吗?" | 答 |
|------|------------------|---|
| Q-D.1 = α | "应该用 fire-and-forget"? | ✗ 不会 — sync core 让 chokepoint claim 在 IM 通道也成立，与 web SSE 通道哲学一致 |
| Q-D.2 = α | "应该让 IM 旁路 sink"? | ✗ 不会 — sink 抽象统一是跨 channel 演进的根 |
| Q-D.3 = α | "backfill 当时不必要"? | ✗ 反过来后悔：推迟改后 prod 数据写满 system+0 行，再改要么重做 backfill 要么忍受永久双套 UI |
| Q-D.4 = α | "handoff 链不该进 task 模型"? | ✗ 反过来后悔：跨 agent mission progress / cost / approval 没法贯通是 ACP 设计的根本目标失败 |
| Q-D.5 = ↑ | "InboundMode 不该多 1 个 enum 值"? | ✗ — 区分 NEW_FROM_REQUEST vs PERSISTED_FROM_IM 是数据来源根本差异 |
| Q-D.6 = α | "应该把 mobile WS 提前考虑"? | ✗ — YAGNI；OSS 不引入独立 mobile BFF 是 Q7 既定决策 |

### Steel-man 反方汇总（公平起见，但每条都被驳）

- **Q-D.1**: "α 让 @Async pool 长期占用 → 应该 β fire-and-forget"
  → β 让 observability 弱化（异步分离 turn lifecycle），违反 chokepoint claim 真实性。pool size 是运维参数；chokepoint claim 是架构属性。
- **Q-D.2**: "BroadcastResponseSink 写法太繁琐 → 应该 β NoOp + 外部广播"
  → β 让 finalizeTurn 与 IM 写入两路并行，turn 失败时数据状态不一致风险加倍。
- **Q-D.3**: "backfill SQL 风险大 → 不如 β 保留 system+0"
  → 风险来自 `system+0` 历史行的来源混杂；用 `WHERE conversation.metadata->>'chat_kind' IN ('aurabot_panel','imai')` 过滤可控。dev-stage 是 backfill 的最佳窗口。
- **Q-D.4**: "每跳建 task 写表压力大 → 不如 β 单 turn 多 agent"
  → ACP `dispatchChildTasks` 已是同模型；handoff depth ≤ 5 受限；DB 压力可控。β 的真实成本是 TurnContext/TurnOutcome 表达不下 multi-agent。

## 8. C.3 实施时序（owner 决策已锁，可进 plan v1）

按 §3 PR 切片 + α 全部，5 PR 紧密推进：

| PR | 决策来源 | 验收红线 |
|----|---------|---------|
| **D.1** `BroadcastResponseSink` + `InboundMode.PERSISTED_FROM_IM` + `Persistence.persistInbound` 分支处理 | Q-D.2=α + Q-D.5=↑ | sink 单测 5 cases (chunk 缓冲 → done 一次性写；error 写 failed 行；result_contract 转 card 类型；pendingApproval/confirm 仍流过)；既有 SseResponseSink 12/12 不破 |
| **D.2** `ImAiService.generateResponse` 改造：保留 @Async wrapper；内部走 `turnService.runTurn(TurnRequest, BroadcastResponseSink)`；删原 LLM 直调路径；写 `sender_type='agent' + agentId` | Q-D.1=α + Q-D.3=α | 集成测试：IM 消息 → `runTurn` 被调；`ab_agent_task` 落库；`ab_im_message` 用 `sender_type='agent'`；ImMessageBroadcaster 收到 frame；C.2 memory L1 writeback fire |
| **D.3** `GroupChatAgentRouter.onMessageSent` / `AgentReplyTask` 改造：handoff 链改为 task 父子关系（每跳建 `ab_agent_task`，`parent_id` 串起来）；@Async wrapper 调 `turnService.runTurn` | Q-D.1=α + Q-D.4=α | 集成测试：群聊 @mention 走 chokepoint；handoff 链每跳建 `ab_agent_task` 父子；mission_id 贯通；MAX_HANDOFF_DEPTH=5 仍生效 |
| **D.4** `WsResponseSink` 实现（IM TYPING / streaming chunk 帧）；TYPING SSE event 改成 WS 帧 | Q-D.6=α 内部细节 | 字节级测试：TYPING 帧 / chunk 帧 / done 帧；SseEmitterManager 改造同步 |
| **D.5** `sender_type` backfill SQL：一次性 UPDATE 历史 `system+0` 行（按 conversation `metadata.chat_kind` 反查） | Q-D.3=α 后置 | dry-run 在 reset-db 后跑；audit query 列出受影响行数；OSS schema.sql 加注释说明 |

每 PR 独立 commit + push + main fast-forward 模式（同 C.3.x 节奏）。每 PR 独立可 review + 独立可回滚。

预估总时长：D.1 (1天) + D.2 (1.5天) + D.3 (2天) + D.4 (1天) + D.5 (0.5天) ≈ 5-6 天工作量。

**长期演进视角检查**（参考 AGENTS.md 红线）：

- 6 个月后回头看，会后悔的选择？
  - β/γ 选项让 chokepoint 在 IM 通道装饰化（已是 C.3 系列要修的同类问题）
  - 推迟 sender_type backfill 到 prod 数据落地后做 → 数据集成本爆炸
  - 不归一 handoff chain → 跨 channel mission 视图永远破碎
- α 倾向都符合"长期演进价值优先"判断信号

**反方 steel-man**：
- "α 让 @Async pool 长期占用 → 应该 β fire-and-forget"：但 β 让 observability 弱化（异步分离 turn lifecycle），违反 chokepoint claim 真实性。pool size 是运维参数；chokepoint claim 是架构属性。
- "Q-D.4 α 每跳建 task 写表压力大"：但 ACP `dispatchChildTasks` 已是这个模型，handoff depth=5 受限，写入压力可控。
- "sender_type backfill 风险大不如 β"：dev-stage 是允许 breaking changes 的窗口；推迟到 prod 后改风险才大。

---

## CHANGELOG

- 2026-04-30 v2 owner 决策锁定 6 项 α；§7 改写决策表 + 6 个月后悔检查 + steel-man；§8 决策已锁的实施时序（D.1-D.5）
- 2026-04-30 v1 初稿；6 个决策点 surfacing；候选 PR 切片 D.1-D.5
