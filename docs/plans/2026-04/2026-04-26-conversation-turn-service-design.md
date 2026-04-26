# ConversationTurnService 抽取设计稿（L5 chokepoint）

**状态**：design proposal v3.2 —— 第五轮 review。本轮重点是 v3.1 暴露的**生命周期错误**：`PendingConfirmation` 不能既是 completed outcome 又是可 resume 状态；引入 TurnPhase + suspendTurn/TurnSuspendedEvent 模型。同时清掉残留架构图、Phase B 群聊验收、/chat 同步路径、PendingTool schema 丢字段、humanMemberId 前端契约错位
**日期**：2026-04-26（v1）/ 2026-04-26（v2 同日二轮 review 后）

> **v2 收口**：v1 review 暴露 5 P1 + 3 P2 + 5 missing 共 13 点结构性问题。已就地修正，重点：
> 1. **真实 API 入口表**修正（`/api/ai/aurabot/*` 而非 `/api/aurabot/*`；补 `AuraBotConversationController.messages/user|assistant` + `ImMessageController` + `/execute` continuation）
> 2. **接口拆 runTurn 单 orchestrator**（解决 v1 的 begin/execute/end 三段责任边界漏洞）
> 3. **两种 beginTurn 模式**：`beginNewInboundTurn`（创建+持久化）vs `beginFromExistingMessage`（群聊已持久化场景，避免双写）
> 4. **前端契约变更显式化**：`AuraBotProvider.appendUserMessage / appendAssistantMessage` 必须删除，`chatStream` 接收 `conversationId + clientMsgId`，否则与服务端 begin/endTurn 双写
> 5. **sender_type 决策点新增**（Q8）：当前 `appendAssistantMessage` 走 `system + sender_id=0`，`GroupChatMessageAdapter.saveAgentMessage` 走 `agent + agentId`；统一前的产品语义 + 历史 UI 兼容必须 owner 拍板
> 6. **`ab_agent_channel_session` 表是 phantom**：OSS schema.sql 仅有 `_state` 后缀表，无主表 CREATE；TurnContext.channelSessionId 注入前必须先解决 schema 来源
> 7. **Phase A 契约与 0 行为变更冲突修正**：Phase A 接口明确 `Persistence.NOOP` 模式，持久化契约严格延后 Phase B
> 8. **Triage 阶段位置与 Tier 0 contract drift 修正**：ConversationTurnService 仅准备 ChannelSession + ConversationHistoryDigest 输入，触发 PreGroundingTriage 实际执行仍按 contract 在 Stage 2.5
> 9. **验收判据重写**：移除 `git log --grep` 类非行为判据，改为可观测行为断言（前端不再调 append*、4 类入口各自 sender_type/seq/client_msg_id 验证、`/execute` 不创建新 human message）
> 10. **feature flag 决策**（Q9）：dev stage 偏好直切；如保留 flag 必须明示关闭路径与删除时间点
**关联文档**：
- 触发：`auraboot/docs/plans/2026-04/2026-04-24-acp-v4-patches-and-rationale.md` GAP-270 调研发现 AuraBotChatService 持久化缺失，且暴露更深的分层问题
- 上下文：`auraboot-enterprise/docs/agent/ACP-Architecture.md §2.1`（5 层语义下沉 L5→L0）
- 上下文：`auraboot-enterprise/docs/agent/ACP-Ideal-Agent-Design.md §6.1`（Channel Gateway / Session Router / Interrupt Protocol）
- 上下文：`auraboot-enterprise/docs/agent/contracts/runtime-core.md`（ExecutionContext + Turn Lifecycle 10-Stage）
- 后续依赖：本设计落地后 GAP-270/272/273 + ACP Phase 3-4 的实施路径会显著简化

---

## 0. 这份文档是什么

是对 GAP-270（AuraBotChatService.doStreamChat 消息持久化）实施路径的**长期视角架构判断**。

### 0.1 不是什么

- **不是**实施计划：不含具体里程碑日期、Sprint 分配、资源指派
- **不是**接口最终定稿：所有 Java 签名是 reference shape，定稿在 owner 评审后单独 PR 进 `contracts/` Tier 0
- **不是**对 ACP canonical 体系的改动：本设计补的是已有 5 层下沉里 L5 业务层的缺口，不引入新概念

### 0.2 是什么

- **现状判断**：当前 chat 持久化不一致，AuraBotChatService 1565 行存在分层错配
- **路径选择**：在"快速 patch GAP-270 单点"和"抽取 ConversationTurnService chokepoint"之间，给出长期成本对比与推荐
- **三阶段拆解**：refactor 路径下的 Phase A/B/C 各自的范围、可验证 deliverable、回滚边界
- **决策点清单**：owner 评审时需要拍板的 7 件具体事

---

## 1. 现状的真实问题：不止于"漏了 INSERT"

把 GAP-270 当成"加 2 个 INSERT"是看错了问题。真正的故障是更深的架构错配。

### 1.1 当前 chat 持久化的不一致（v2 修正）

> **[v2]** v1 列的 API 路径错误。下面是从源码 grep 的真实入口表。

AuraBot 同一个逻辑动作"用户向 AuraBot 发了一条消息"，在生产代码里**有 8 条物理路径**（v3 加入 #7 异步标记 + #8 ImAiService）：

| # | 入口 | 控制器 | 服务层 | 持久化 inbound | 持久化 outbound |
|---|------|--------|--------|----------------|-----------------|
| 1 | `POST /api/ai/aurabot/chat/stream` (SSE) | `AuraBotController:29` | `AuraBotChatService.doStreamChatInner` (1565 行) | ❌ | ❌ |
| 2 | `POST /api/ai/aurabot/chat` (非 SSE) | `AuraBotController:44` | 同上（reviewer 指出当前只返回提示，不实走 doStreamChat） | ❌ | ❌ |
| 3 | `POST /api/ai/aurabot/execute` (SSE continuation) | `AuraBotController:53` | `resumePendingTool(...)` | ❌（已有 inbound）| ❌ |
| 4 | `POST /api/ai/aurabot/conversations/{id}/messages/user` | `AuraBotConversationController:53` | `AuraBotConversationService.appendUserMessage` | ✅ via `imMessageService.sendMessage`（sender_type=human）| n/a |
| 5 | `POST /api/ai/aurabot/conversations/{id}/messages/assistant` | `AuraBotConversationController:66` | `AuraBotConversationService.appendAssistantMessage` | n/a | ✅ via `imMessageService.sendSystemMessage`（**sender_type=system, sender_id=0**） |
| 6 | `POST /api/im/conversations/{id}/messages` | `ImMessageController:32` | `imMessageService.sendMessage` | ✅（sender_type=human）| n/a |
| 7 | 群聊 @mention agent（事件触发） | `ImMessageSentEvent → GroupChatAgentRouter`（**v3.2 修 P2.7：当前源码未找到 `publishEvent(new ImMessageSentEvent(...))` 实际调用点；监听器 + event class 已就位但未接线，标 latent**）| **`AgentReplyTask`（异步 fire-and-forget）** → `GroupChatMessagePort.saveAgentMessage` (`GroupChatMessageAdapter.java:164`) | n/a（设计上已持久化，但 latent 状态下未实际触发）| ✅ 设计 sender_type=agent + agentId（实际生产 sender_type=agent 行 prod 数据为 0）|
| 8 | **WebSocket @AI 触发**（v3 新增 P1.7）| `ImWebSocketHandler:151-152` → `ImAiService.hasMention/generateResponse` | `ImAiService.generateResponse` 直接调 LLM 后写 `imMessageService.sendSystemMessage` | n/a（已持久化）| ✅（**sender_type=system, sender_id=0**） |

**关键不一致**：
- 入口 1-3（SSE 路径）**完全不写库**
- 入口 4-8 各自落库，但 outbound `sender_type` 三分裂：5 = `system`，7 = `agent`，**8 = `system` (ImAiService)**
- 入口 4-5 由前端在 chatStream 前后**显式调用**做"前端代写"持久化（详见 §1.4）
- 入口 7 是**异步**（AgentReplyTask fire-and-forget），与同步 SSE 路径 lifecycle 模型不一致
- 入口 8 是 WebSocket-driven，独立 LLM 调用 + 直接写 system 消息，未经 ChatToolResolver / D1 Grounding

production 数据 `sender_type` 分布印证：`human=6, system=1, agent=0`。

这是 architectural smell（分层缺失 + 持久化责任在前端 vs 后端模糊），不是 missing feature。

### 1.4 真实调用链：前端代写持久化 + 服务端 SSE 不写（v2 新增）

> **[v2]** v1 把"AuraBotChatService 不持久化"作为问题的全部，错过了关键事实：**当前持久化是前端调用 #4 + #5 两个 endpoint 完成的**，不是后端漏写。

源码（`web-admin/app/plugins/core-aurabot/components-shell/AuraBotProvider.tsx:633` + `:614`）：

```typescript
// 1. 前端先 POST /messages/user 落 inbound 消息（吞异常）
await auraBotApi.appendUserMessage(conversationId, content, userMsgId);
//    → 入口 #4 → AuraBotConversationService.appendUserMessage
//    → imMessageService.sendMessage(sender_type=human)

// 2. 前端 POST /chat/stream 拿 SSE 流（doStreamChat 不写库）
await auraBotApi.chatStream(...);
//    → 入口 #1 → AuraBotChatService.doStreamChatInner（不持久化）

// 3. 前端 SSE done/error 后 POST /messages/assistant 落 outbound 消息（吞异常）
await auraBotApi.appendAssistantMessage(conversationId, content, traceId, error);
//    → 入口 #5 → AuraBotConversationService.appendAssistantMessage
//    → imMessageService.sendSystemMessage(sender_type=system, sender_id=0)
```

**两端都 `try { ... } catch {}` 吞异常**——持久化失败不影响 chat UX，但也无错误反馈。

**这意味着两件事**：

1. **服务端要落库不只是"加 INSERT"**：要同时**删除前端的 appendUserMessage / appendAssistantMessage 调用**，否则双写。
2. **前端契约必须变**：`chatStream` 必须额外接收 `conversationId` + `clientMsgId`（用于服务端幂等持久化 + dedup）。当前 chatStream 不传这两个字段。

这是 v2 设计必须正面处理的，不能被掩盖在"在 chokepoint 加持久化"的简短描述里。

### 1.2 AuraBotChatService 1565 行的分层错配

按 `ACP-Architecture.md §2.1` canonical 5 层语义下沉：

```
L5  Natural Language / Event       ← 应该是清晰的"入口归一化"层
L4  Business Intent (BIF)          ← Grounding 产出
L3  Skill                           ← 可治理的能力编排
L2  Action                          ← 写副作用 + 审计/回滚
L1  Tool                            ← 原子调用
L0  DB / API / System
```

`AuraBotChatService.doStreamChatInner` 1565 行里**塞着 4 层**：

| 行段 | 实际职责 | 应在哪一层 |
|------|----------|-----------|
| 285-330 | HTTP/SSE 入口、agent code routing、provider 解析 | **L5** |
| 341-378 | D1 Grounding 调用、BIF context 注入 | **L4** |
| 380-440+ | Tool resolver + tool loop（5 轮） | **L3** |
| 1173+ | 直接调 LLM provider HTTP、SSE event 发送 | **L1** + 横切 transport |

**这个文件没有 L5/L4/L3/L1 边界**。当前所有 chat 相关需求被迫往这个文件塞，所以 1565 行还在涨。

### 1.3 已识别的"会再次进来动这个文件"的需求清单

| 需求 | 类型 | 何时进来 | 触及 doStreamChat 的方式 |
|------|------|---------|--------------------------|
| GAP-270（消息持久化） | 当前 | 现在 | 入口 + 多个 SSE 终止点 |
| GAP-273（Pre-Grounding Triage） | 已规划 | 1-2 月 | 入口前插决策 + 多分支 dispatch |
| ACP Phase 3 集成（StepRuntime + PlanningService） | 已规划 | 数周内 | 把 tool loop 替换为 Step Lifecycle |
| ACP Phase 4（D1 Grounding 全量替换 TF-IDF） | 已规划 | Phase 3 后 | 撕掉 TF-IDF fallback；改 BIF 决策路径 |
| Memory L1 writeback 真实接入 | 已落地待续 | 持续 | 加完成时回调 |
| 跨设备会话同步（移动端继续 web 对话） | 已知需求 | 移动端规划 | 加 cross-device session 解析 |
| Approval Gate `PER_SESSION` lifetime（v4 patches GAP-272） | 新引入 | Phase 3 同期 | scope key 4 元组贯穿 |
| Multi-channel continuity（同 user 在 Web + Slack） | 已知 | Channel Gateway 规划 | 跨入口共享 conversation |

**8 个独立需求都要在 1565 行的 `doStreamChatInner` 里塞代码**。继续打 patch 就是给反模式加燃料。

---

## 2. 为什么 4 种 patch 方案都治标不治本

GAP-270 调研列了 4 种方案：

| 方案 | 治标在哪 | 不治本在哪 |
|------|---------|------------|
| (a) try-finally + 响应累加器 | 修复一个入口的持久化 | 其他 entry point（非 SSE 直连）仍各干各的 |
| (b) 每个 sendDone call site 加一行 | 修复 SSE 路径 | 13+ 个 call site，漏一个就丢消息 |
| (c) Spring AOP / Observation 切面 | 隐式劫持 | 隐式逻辑后人难调，且与 ACP Stage 7 EXECUTE 冲突 |
| (d) 重构成 PendingChatTurn 对象 | 接近正解 | 但只覆盖 doStreamChat 一条路径，其他入口仍不参与 |

### 2.1 共同盲点

4 种方案都假设"GAP-270 是 AuraBotChatService 的内部问题"。但真正的问题是**所有 chat 入口需要一个共同的 chokepoint**，否则每个 cross-cutting 需求（持久化、triage、memory、audit、跨设备同步）都要在 N 个入口重复实现。

### 2.2 patch 路径的 6 个月成本

假设走方案 (a) 单点 patch：

```
现在 (0.5 day):       AuraBotChatService 加 try-finally + 累加器
↓
1 个月 (1-2 day):     GAP-273 triage 实施 → 又钻 1565 行加 hook
↓
3 个月 (1 week):      ACP Phase 3 集成 → 撕掉 doStreamChat 重做
↓
6 个月 (1 week):      移动端跨设备同步 → 再做一次 cross-device persistence
```

**总成本 ~3 weeks，且 1565 行还在涨**（每次叠新功能都加上百行）。

更隐蔽的成本：每次 reopen 都需要回归测试整个 chat 路径，团队对这个文件的恐惧逐次累积。

---

## 3. 长期正确做法：抽出 ConversationTurnService 作为 L5 chokepoint

### 3.1 目标架构（v3.2 重画，对齐正文修正）

```
┌─────────────────────────────────────────────────────────────────────┐
│ ENTRY POINTS (L5 Adapter Layer)                                     │
│                                                                     │
│ Phase A 范围:                                                       │
│   POST /api/ai/aurabot/chat/stream   ─→ runTurn(NEW_FROM_REQUEST)   │
│                                                                     │
│ Phase B 范围（除 A 之外加）:                                         │
│   POST /api/ai/aurabot/execute       ─→ resumeTurn(pendingTurnId)   │
│                                                                     │
│ 不归一（保留现状,见 §3.5）:                                          │
│   POST /api/ai/aurabot/chat (sync)   /api/im/conversations/{id}/... │
│                                                                     │
│ Phase B+ 范围（独立 group-chat-adapter sub-design,Q13 决策后启动）:  │
│   GroupChatAgentRouter (@mention,event-driven; latent 状态)         │
│   ImWebSocketHandler → ImAiService                                  │
│   Webhook adapter / BPM userTask escalation (未来扩展)              │
│                                       │                             │
│                                       ▼                             │
│   ┌────────────────────────────────────────────────────────────┐   │
│   │ ConversationTurnService (L5 业务层 chokepoint)             │   │
│   │                                                             │   │
│   │  runTurn(TurnRequest, ResponseSink) → TurnOutcome           │   │
│   │  resumeTurn(pendingTurnId, decision, sink) → TurnOutcome    │   │
│   │                                                             │   │
│   │  内部 try/catch/finally → finalizeTurn(ctx, outcome):       │   │
│   │    Success / Interrupted / Failed:                          │   │
│   │       persistOutbound? → phase=COMPLETED                    │   │
│   │       → emit TurnCompletedEvent (一次)                      │   │
│   │    PendingConfirmation:                                     │   │
│   │       persistOutboundPartial? + savePending(turnId, payload)│   │
│   │       → phase=SUSPENDED                                     │   │
│   │       → emit TurnSuspendedEvent (不是 TurnCompletedEvent)   │   │
│   │                                                             │   │
│   │  注: triage 不在本服务执行                                   │   │
│   │     ConversationTurnService 仅准备 ChannelSession +         │   │
│   │     ConversationHistoryDigest; PreGroundingTriage 由 Stage  │   │
│   │     2.5 executor 调用并回写 TurnContext.triageBucket        │   │
│   │     (按 Tier 0 contracts/pre-grounding-triage.md)           │   │
│   │                                                             │   │
│   │  side effects 由 Q12 决策的 TurnSideEffects profile 注入:   │   │
│   │     Phase A: TRULY_DISABLED (Q12=M) 或 observeOnly (Q12=N) │   │
│   │     Phase B: PRODUCTION (含 Persistence + Event + Audit +   │   │
│   │              Metrics)                                       │   │
│   └────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴────────────────┐
              ▼                                ▼
       AuraBotChatService              ACPRuntime
       (SSE streaming impl;            (Stage 1-10 lifecycle;
        Phase A: 改签名收 ResponseSink;  走 D1 → BIF → Skill →
        Phase B: 不再做持久化;           Action → Tool;
        持久化职责在 finalizeTurn)       Phase C 接入)

     注: GroupChatRouter / ImAiService 不在此架构图主流;
         Phase B+ 单独 sub-design 处理异步 lifecycle 与 SSE 同步 lifecycle
         的差异(详见 §3.5 + Q13)
```

### 3.2 这一步为什么是对的

| 维度 | 现状 | 引入 ConversationTurnService 后 |
|------|------|--------------------------------|
| 持久化 | 3 套 service 各自决定 | 1 个 chokepoint，全 entry point 一致 |
| Triage（GAP-273） | 需要再钻 1565 行文件 | ConversationTurnService 准备 input；PreGroundingTriage 在 Stage 2.5 执行（按 Tier 0 contract）|
| ACP Phase 3-4 集成 | 撕掉 doStreamChat 内部重做 | `executeTurn` 加一种 impl 委派 |
| Memory L1 writeback | 各 service 自己挂回调 | 订阅 `TurnCompletedEvent` |
| 移动端 / 跨设备同步 | 每个端各自实现 | 共享同一个 conversation 模型 |
| Audit 链路 | 散布 | 单点写 trace + audit |
| Approval Gate `PER_SESSION` lifetime | 各 service 自己管 scope key | TurnContext 携带 channelSessionId，自然贯穿 |
| 1565 行 chat 文件 | 持续上涨 | 收缩到只剩 streaming impl，其他职责外移 |

### 3.3 与 ACP canonical 体系的契合

`ACP-Architecture.md §2.1` 5 层下沉里 L5 = "Natural Language / Event"。当前 ACP doc 有 §6.1 Channel Gateway（多入口适配器），但**缺少 L5 业务层 chokepoint**——Channel Gateway 是 transport adapter，不是 turn lifecycle 控制器。

ConversationTurnService 就是这一缺位的业务层。它在 `Turn Lifecycle 10-Stage Loop`（`ACP-Architecture §4.6` / `runtime-core.md`）之外，作为 Stage 1（LOAD_OR_RESUME）的**前置归一化层**。

也就是说：**这一步补的是已有架构里隐藏的缺口，不是发明新概念**。

```
Channel Gateway (L5 transport)
    ↓
ConversationTurnService (L5 业务) ← 本设计补的
    ↓
Turn Lifecycle Stage 1-10 (现有 §4.6)
    ↓
... L4 Grounding → L3 Skill → L2 Action → L1 Tool → L0
```

### 3.4 接口形态（v2 重写，reference shape，非定稿）

> **[v2]** v1 的 begin/execute/end 三段拆分有责任边界漏洞（reviewer P1.5）：executeTurn 是 void，谁保证 endTurn 在 async SSE / provider stream / client disconnect / send error / confirm_required 后都被调到？现有 sendChunk/sendDone 还吞异常。
>
> v2 改用 **runTurn 单 orchestrator + 内部 try/finally** 模式。execute impl 返回 TurnOutcome，ResponseSink 只负责 transport。orchestration 层保证 endTurn 一定被调。

```java
package com.auraboot.framework.conversation;

public interface ConversationTurnService {

    /**
     * 单 orchestrator 入口。覆盖 begin → execute → end 全生命周期；
     * 内部 try/catch/finally 保证 endTurn 一定被调到，下游 TurnCompletedEvent 不漏发。
     *
     * 调用方仅负责提供 TurnRequest + ResponseSink（SSE / WS / sync 适配器），
     * 不再分别 begin / execute / end —— 那三个分段是内部实现细节。
     */
    TurnOutcome runTurn(TurnRequest request, ResponseSink sink);

    /**
     * Continuation 入口。用于 confirm-required 暂停后恢复（对应 /api/ai/aurabot/execute）。
     * 不创建新 inbound message；用 pendingTurnId 找回已持久化的 TurnContext。
     */
    TurnOutcome resumeTurn(String pendingTurnId, ConfirmDecision decision, ResponseSink sink);
}

/**
 * 内部 TurnLifecycle（不对外暴露，由 ConversationTurnService 实现持有）：
 *   - beginNewInboundTurn(TurnRequest)         → 创建 + 持久化新 inbound
 *   - beginFromExistingMessage(messageId)      → 群聊场景：inbound 已持久化，仅装配 context
 *   - executeTurn(TurnContext, ResponseSink)   → 委派到 chat impl，返回 TurnOutcome
 *   - endTurn(TurnContext, TurnOutcome)        → 持久化 outbound + emit event
 */
interface TurnLifecycle {
    TurnContext beginNewInboundTurn(TurnRequest request);
    TurnContext beginFromExistingMessage(long inboundMessageId, long tenantId);
    TurnOutcome executeTurn(TurnContext ctx, ResponseSink sink);
    void endTurn(TurnContext ctx, TurnOutcome outcome);
}

// ★ [v3.1 修正 P1.2]
// TurnRequest 与 TurnContext 的 canonical 定义见 §3.8.1（含 humanMemberId / agentId 等 identity 字段）。
// 本节不重复定义，避免双源漂移。下方 ResponseSink / TurnOutcome / InboundMode / 接口签名是 §3.4 唯一持有的。

public enum InboundMode {
    /** 默认：从 userMessage 创建并持久化新 inbound message。SSE / 直连 /chat/stream 用此模式 */
    NEW_FROM_REQUEST,
    /** 群聊事件：inbound 已被 ImMessageService 持久化；TurnRequest.options 必须含 inboundMessageId
     *  注：v3 决策 Q13 已把群聊路径推迟到 Phase B+ 单独 adapter，此模式 Phase A/B 不启用 */
    EXISTING_MESSAGE_ID
}

public interface ResponseSink {
    // ★ v2: 仅负责 transport,不参与 lifecycle 决策
    void onTextChunk(String text);
    void onToolStart(String toolId, String name, Map<String, Object> args);
    void onToolResult(String toolId, Map<String, Object> result, boolean success);
    void onConfirmRequired(String toolId, String name, Map<String, Object> args);
    void onError(String message);
    void onDone(String finalResponse);
    // ★ v2: 监测 client 断开（SSE / WS adapter 实现）；orchestrator 据此触发 INTERRUPTED outcome
    boolean isClientConnected();
}

public sealed interface TurnOutcome permits TurnOutcome.Success,
                                              TurnOutcome.Interrupted,
                                              TurnOutcome.PendingConfirmation,
                                              TurnOutcome.Failed {
    record Success(String finalResponse, Map<String, Object> meta) implements TurnOutcome {}
    record Interrupted(String partialResponse, String reason) implements TurnOutcome {}
    record PendingConfirmation(String pendingTurnId, String partialResponse, String pendingToolId) implements TurnOutcome {}
    record Failed(String errorMessage, Throwable cause) implements TurnOutcome {}
}
```

> **不变式（v3.2 重写，修 P1.1 双完成）**：

#### TurnPhase 状态机（v3.2 新增）

```
ACTIVE ──(Success / Interrupted / Failed)──→ COMPLETED  → emit TurnCompletedEvent (一次)
   │                                              │
   └──(PendingConfirmation)──→ SUSPENDED          │
                                  │               │
                                  └──(resumeTurn)─→ ACTIVE → COMPLETED
```

- 一个 turn 可以经过 `ACTIVE → SUSPENDED → ACTIVE → COMPLETED`，但 **`COMPLETED` 与 `TurnCompletedEvent` 全生命周期只触发一次**
- `SUSPENDED` 触发独立的 `TurnSuspendedEvent`（含 pendingTurnId），下游订阅者明确区分 suspended 与 completed

#### 接口语义

- **`runTurn` 内部 try/catch/finally**：finally 调 `finalizeTurn(ctx, outcome)`，由 finalizeTurn 根据 outcome 类型分发：
  - `Success / Interrupted / Failed` → 走 `endTurn` 路径 → phase=COMPLETED → emit `TurnCompletedEvent`
  - `PendingConfirmation` → 走 `suspendTurn` 路径 → phase=SUSPENDED → emit `TurnSuspendedEvent`
- **`beginNewInboundTurn`**：持久化失败 = 整个 runTurn 早 fail，返回 `TurnOutcome.Failed`
- **`beginFromExistingMessage`**：仅做 context 装配，不写库（inbound 已存在）
- **`endTurn(Success)`**：持久化 agent 消息；持久化失败**写 audit 但不回滚**已发出的 SSE 响应
- **`endTurn(Interrupted)`**：持久化 partialResponse（非空时）+ 加 metadata 标记为 partial
- **`endTurn(Failed)`**：不持久化 agent 消息（无可用响应），仅写 audit
- **`suspendTurn(PendingConfirmation)`**：持久化 partialResponse（非空时）+ 标记 partial；**不** emit TurnCompletedEvent；persist PendingTool payload 到 ChatSessionStore
- **`resumeTurn(pendingTurnId, decision)`**：从 ChatSessionStore 找回 PendingTool；按 decision 分支：
  - `APPROVED` → 重新进入 `runTurn` 内部 ACTIVE phase 继续工具执行；最终 phase=COMPLETED → 单一 TurnCompletedEvent
  - `DENIED / CANCELLED` → 直接 `endTurn(Interrupted)` → phase=COMPLETED
- **整 turn 生命周期内 TurnCompletedEvent 至多发布一次**；TurnSuspendedEvent 可发布多次（理论上可多轮 suspend，但 maxSpawnDepth 限制）

#### 修正后的 finalizeTurn 伪码

```java
void finalizeTurn(TurnContext ctx, TurnOutcome outcome) {
    switch (outcome) {
        case Success s -> {
            persistOutbound(ctx, s);
            ctx.transitTo(COMPLETED);
            eventEmitter.emit(new TurnCompletedEvent(ctx, s));
        }
        case Interrupted i -> {
            if (i.partialResponse() != null && !i.partialResponse().isBlank()) {
                persistOutboundPartial(ctx, i);
            }
            ctx.transitTo(COMPLETED);
            eventEmitter.emit(new TurnCompletedEvent(ctx, i));
        }
        case Failed f -> {
            auditWriter.writeFailure(ctx, f);
            ctx.transitTo(COMPLETED);
            eventEmitter.emit(new TurnCompletedEvent(ctx, f));
        }
        case PendingConfirmation pc -> {
            if (pc.partialResponse() != null && !pc.partialResponse().isBlank()) {
                persistOutboundPartial(ctx, pc);
            }
            chatSessionStore.savePending(ctx.turnId(), buildPendingTool(ctx, pc));
            ctx.transitTo(SUSPENDED);
            eventEmitter.emit(new TurnSuspendedEvent(ctx, pc));   // ★ 不是 TurnCompletedEvent
        }
    }
}
```

### 3.5 入口适配映射（v2 新增）

按 §1.1 真实入口表，每条入口在 v2 ConversationTurnService 下的归一方式：

| # | 入口 | Phase | runTurn / resumeTurn | InboundMode | 备注 |
|---|------|-------|---------------------|-------------|------|
| 1 | `POST /api/ai/aurabot/chat/stream` (SSE) | **A0+B** | `runTurn` | NEW_FROM_REQUEST | 主入口；前端 chatStream 仅新增 `conversationId` + `clientMsgId` 两个字段。**`humanMemberId` 由 `AuraBotController.currentHumanMemberId()` 服务端注入，不暴露给前端**（v3.2 修 P1.3：避免伪造成员身份的攻击面）|
| 2 | `POST /api/ai/aurabot/chat` (非 SSE) | **不归一**（v3.2 修 P1.5）| n/a | n/a | 当前 endpoint 只返回提示，不实走 doStreamChat。同步 ResponseSink 协议（如何返回 confirm_required / pendingTurnId / partial / interrupted）未定义；要么 Phase B+ 单独设计 sync JSON response schema，要么直接删除该 endpoint。**v3.2 倾向：保留现状不归一**，待后续真有 sync 需求再单设 |
| 3 | `POST /api/ai/aurabot/execute` (continuation) | **B**（详见 §3.10）| `resumeTurn` | n/a | 不创建新 inbound；用 pendingTurnId 找回；§3.10 5 步必须同期改齐 |
| 4 | `POST /messages/user` | B 删除 | n/a | n/a | 持久化职责移到服务端 runTurn |
| 5 | `POST /messages/assistant` | B 删除 | n/a | n/a | 同上 |
| 6 | `POST /api/im/conversations/{id}/messages` | 不变 | 不归 turnService | n/a | IM 模块自己的入口；Phase B 验收为"不受 turnService 影响"，不要求 4 outcome |
| 7 | 群聊 @mention（事件触发）| **延后到 Phase B+** | 见 Q13 | n/a | reviewer P1.4：AgentReplyTask 异步路径与 runTurn 同步 lifecycle 模型不匹配，不能直接归一；Phase A 不动；Phase B 单独设计 group chat adapter（决策 Q13） |
| 8 | WebSocket @AI（`ImAiService`）| **延后到 Phase B+** | 见 Q13 | n/a | reviewer P1.7：Phase A 不动；Phase B+ 与 GroupChatAgentRouter 一起单独设计 |

### 3.6 sender_type 决策点（v2 新增，对应 reviewer P2.6）

**当前不一致（v3.1 补 ImAiService）**：

| 路径 | inbound sender_type | outbound sender_type |
|------|--------------------|--------------------|
| 前端 appendUserMessage → #4 | `human` | n/a |
| 前端 appendAssistantMessage → #5 | n/a | **`system` + sender_id=0** |
| 群聊 GroupChatMessageAdapter (#7) | `human`（已落） | **`agent` + agentId** |
| **ImAiService (#8)** | `human`（由 IM 已落）| **`system` + sender_id=0**（`sendSystemMessage` 路径）|

**v2 必须决策（决策 Q8）**：

- 选项 A：**统一用 `agent` + agentId**。AuraBot 默认 agentId 由新引入 `AuraBotAgentResolver` 解析（OSS 内置一个 default AuraBot agent registration）。Pro：与群聊路径一致。Con：历史所有 sender_type=system 的 assistant 消息要 backfill 或保留双语义；前端 UI 需识别两种值。
- 选项 B：**保留 `system` + sender_id=0**。Pro：兼容历史数据。Con：与群聊路径分裂，未来跨 channel 同步混乱。
- 选项 C：**新增 sender_type=`aurabot`** 区分。Pro：语义明确。Con：又多一个枚举值，DB CHECK 要扩展。

**我的倾向**：选项 A + backfill SQL。理由：
1. AuraBot 与群聊里的 agent 概念上是同一物（一个 AI 角色），分两个 sender_type 是分裂
2. backfill 是一次性写入，可在 dev stage 直接做（per `feedback_dev_stage_breaking_ok`）
3. 前端 UI 有现成的 agent 渲染逻辑（群聊侧），不需要为 system 维护双套渲染

**v3.1 ImAiService 数据迁移补充**（修 P2.8）：

入口 #8 ImAiService 当前写 `sender_type=system, sender_id=0` 的 outbound。Q8=A 实施时必须**同时**处理这部分历史数据，否则 sender_type 分布在 Phase B 完成后仍分叉。

| 时机 | 动作 |
|------|------|
| **Phase B 完成前** | ImAiService 仍写 system；不归 turnService（在 Phase B+ scope）|
| **Phase B+ 启动时**（决策 Q13 完成后） | ImAiService 改为调 turnService.runTurn（与群聊 #7 一同设计 group-chat-adapter）；新数据写 sender_type=agent + agentId |
| **历史数据 backfill** | 一次性 SQL：`UPDATE ab_im_message SET sender_type='agent', sender_id=<aurabot_agent_id> WHERE sender_type='system' AND sender_id=0 AND <来源标记 = ImAiService 或 AuraBotConversationService>`。**风险**：现有 ImAiService 写入没有显式来源标记字段；backfill 难以区分"AuraBot 的 system 消息" vs "其他系统通知 system 消息"
| **Backfill 区分手段** | 1. 走 `card_payload.source = 'aurabot' \| 'imai'` 字段（需 Phase B+ 同期补元数据）<br>2. 或按 conversation `metadata.chat_kind = 'aurabot_panel'` 反查 |

**Q8 选项 A 的实施风险**：sender_type=system 的历史数据可能不是全部来自 AuraBot/ImAiService。Backfill 前必须先 audit 现有 system 行的来源分布；不能盲目全部改成 agent。这是 Phase B+ 的 sub-design 必须明确的。

### 3.7 channelSessionId 来源（v2 新增，对应 reviewer P2.8）

**当前 schema 状态**：

| 表 | 是否有 CREATE | 在哪 |
|----|---------------|------|
| `ab_agent_channel_session` | ❌ 无 | enterprise/docs/agent/schemas/tables.sql 仅 ALTER 引用，未 CREATE |
| `ab_agent_channel_session_state` | ✅ | OSS schema.sql:6968 |

**也就是说 `TurnContext.channelSessionId` 注入的源表是 phantom table**——存在于设计文档但没有真实 DDL。

**v2 必须决策（决策 Q10）**：

- 选项 X：**先建 `ab_agent_channel_session` 主表**作为 Phase A 前置，由 `ChannelSessionResolver` 服务负责 lookup/create
- 选项 Y：**复用 `ab_agent_channel_session_state` 作为 session 主表**（拆掉 _state 后缀，把 lease 列与 session 列合并）
- 选项 Z：**TurnContext.channelSessionId 接受 null**，PER_SESSION lifetime grant 在 null 时降级为 PER_TURN（已写入 runtime-authorization.md）

**我的倾向**：选项 X + 显式 ChannelSessionResolver。理由：
1. `ab_agent_channel_session_state` 名字里 `_state` 暗示 lease/heartbeat 是 session 的状态视图，不是 session 本身
2. ACP-Ideal §6.1 设计本身就分 session vs session_state 两个概念
3. 选项 Z 是退化路径，PER_SESSION 永远生效不了等于砍掉 EffectLifetime 一整档

### 3.8 IM/AuraBot Sender Identity 模型（v3 新增 P1.5+P1.6 优先项）

**v2 漏掉的事实**：

- `imMessageService.sendMessage(SendMessageRequest, senderId, tenantId)` 的 `senderId` **不是 `user_id`，是 `ab_im_conversation_member.member_id`**（人类成员的 IM 会员主键）。`AuraBotConversationController:80` 用 `currentHumanMemberId()` 解析，5 个端点都依赖此方法。如果 TurnRequest 只带 `userId` 直接调，会被 IM membership 校验拒绝（`Not a member of this conversation`）。
- `groupChatMessagePort.saveAgentMessage(conversationId, tenantId, agentId, content, cardPayload)` 的 `agentId` 是 `ab_agent_definition.id`。AuraBot 默认 agent 当前**没有显式 agent_definition row**——`AuraBotConversationService.resolveAgentDefinition` 在 `agentCode="aurabot"` 时返回 null。Phase B 要写 `sender_type=agent + agentId` 必须先解决"AuraBot 默认 agent 是哪个 agentId"的来源问题。

**v3 必须决策（决策 Q11）**：

- 选项 P：**OSS 内置 1 个 default AuraBot agent_definition row**（agent_code="aurabot"，由 bootstrap 自动 seed），所有 tenant 共享一行 → 简单但跨租户共用 ID 有审计问题
- 选项 Q：**每 tenant bootstrap 时 seed 一个 tenant-scoped AuraBot row** → tenant 隔离干净，audit 友好，但 bootstrap 流程要改
- 选项 R：**保留 sender_id=0 的 system 语义专门给 AuraBot**（即放弃统一到 agent，撤回 Q8 选项 A）→ 与 §3.6 Q8 决策冲突，需重新评估

**我的倾向**：选项 Q（per-tenant seed）+ 加 `AuraBotAgentResolver` SPI。

### 3.8.1 TurnRequest / TurnContext 加 identity 字段（v3 修正）

```java
public record TurnRequest(
    long tenantId,
    long userId,
    Long humanMemberId,              // ★ v3 新增 P1.5: ab_im_conversation_member.member_id（HUMAN 类型）
                                     //   Phase A 允许 null（NOOP 模式）
                                     //   Phase B 必填（AuraBotController 由 currentHumanMemberId() 注入）
    String channel,
    String agentCode,                // 默认 "aurabot"，传给 AuraBotAgentResolver 解析 agentId
    Long conversationId,             // Phase A 允许 null（NOOP 模式）；Phase B 必填
    String clientMsgId,              // Phase A 允许 null（NOOP 模式）；Phase B 必填
    String userMessage,
    Map<String, Object> pageContext,
    Map<String, Object> options,
    InboundMode inboundMode
) {}

public record TurnContext(
    String turnId,
    long tenantId,
    long userId,
    Long humanMemberId,              // ★ v3 新增：从 TurnRequest 注入
    Long agentId,                    // ★ v3 新增 P1.6: 从 AuraBotAgentResolver.resolve(tenantId, agentCode) 解析
    String channelSessionId,
    Long conversationId,
    Long inboundMessageId,
    TriageBucket triageBucket,
    String traceId,
    Instant beginAt
) {}

public interface AuraBotAgentResolver {
    /** 解析 tenant 内 agentCode 对应的 ab_agent_definition.id；缺失则按 Q11 决策处理 */
    Long resolve(long tenantId, String agentCode);
}
```

### 3.9 Phase A 真实零行为边界（v3 新增 P1.3 优先项）

**v2 错误**：v2 用 `Persistence.NOOP` 解决持久化的 0 行为变更，但**未涵盖其他 side effects**。即使不写 `ab_im_message`，下面这些都会改变运行行为：

| Side effect 源 | 默认会触发？ | Phase A 必须 |
|----------------|------------|------------|
| `MessageInboundEvent`（runTurn 内 emit） | 是 | **NOOP**（不发布） |
| `TurnCompletedEvent`（endTurn 内 emit） | 是 | **NOOP**（不发布） |
| `AuditService.append` 调用 | 是 | **NOOP**（不写 audit）|
| Memory L1 hooks（订阅 TurnCompletedEvent）| 间接（依赖 event） | 自动 NOOP（event 不发布）|
| Metrics（counter / histogram） | 是 | **新增 metric 加 `phase_a=true` 标签**或完全 NOOP |
| Trace span 生成 | 是 | **保留**（trace 是观测，不算行为变更）|

**v3 修正 + v3.1 拆分两个 profile**（修 P2.5）：

```java
public interface TurnSideEffects {
    Persistence persistence();
    EventEmitter eventEmitter();
    AuditWriter auditWriter();
    MetricsRecorder metricsRecorder();
    // 注：TraceSpan 不在本 SPI 内（trace 是观测层，由 AiTraceService 独立管理）

    /** TRULY_DISABLED：全部 NOOP（含 metrics）。仅在 Q12=M 时使用 */
    TurnSideEffects TRULY_DISABLED = new TurnSideEffects() {
        public Persistence persistence() { return Persistence.NOOP; }
        public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
        public AuditWriter auditWriter() { return AuditWriter.NOOP; }
        public MetricsRecorder metricsRecorder() { return MetricsRecorder.NOOP; }
    };

    /** OBSERVE_ONLY：业务 side effect NOOP，但 metrics 保留（带 phase=A 标签）。Q12=N 默认 */
    static TurnSideEffects observeOnly(MetricsRecorder realMetrics) {
        return new TurnSideEffects() {
            public Persistence persistence() { return Persistence.NOOP; }
            public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
            public AuditWriter auditWriter() { return AuditWriter.NOOP; }
            public MetricsRecorder metricsRecorder() { return realMetrics; }
        };
    }
}
```

> **v3.1 修正**：v3 的 `DISABLED` 单一 profile 与 Q12=N 倾向冲突（DISABLED 含 MetricsRecorder.NOOP）。
> 现拆为两个 profile：`TRULY_DISABLED`（Q12=M 时用）+ `observeOnly()`（Q12=N 时用，默认）。
> Phase A.6 注入的具体 profile 由 Q12 决策值定。

**v3 决策（Q12）**：Phase A 是否允许 trace span 与 metrics（observation only）发挥作用？

- 选项 M：**TRULY_DISABLED**（最严格 0 行为变更）—— 但失去观测，不知道 turn 数量、耗时
- 选项 N：**observeOnly**——持久化/事件/audit NOOP，trace + metrics 保留（带 phase=A 标签） —— **倾向**
- 选项 O：**全部正常发布**——metrics 加标签区分 NOOP profile —— A/B 切换更平滑但不是 0 行为变更

我倾向选项 N。理由：trace 和 metrics 不改变业务流，但能让我们在 Phase A 期间观测 runTurn 真的被调到、observed coverage 与现有 chat 的对应关系。

### 3.10 `/execute` Continuation 端到端契约（v3 新增 P1.2 优先项）

**v2 缺失**：v2 只说 `resumeTurn(pendingTurnId)`，但未规定：
- 后端 confirm_required SSE event 如何返回 `pendingTurnId`
- 前端如何接收并保存 `pendingTurnId`
- `/execute` 请求体 schema 如何变更
- `ChatSessionStore` 当前用 `sessionId` 作 key，要改 `pendingTurnId` 作 key？还是新增字段？

**v3 端到端契约**：

#### 1. confirm_required SSE event 携带 pendingTurnId

`ResponseSink.onConfirmRequired` 实现侧（SseResponseSink）发出的 SSE event payload 必须包含 `pendingTurnId`：

```json
event: confirm_required
data: {
  "toolId": "tool_abc",
  "toolName": "cmd_crm_lead_update",
  "args": {...},
  "pendingTurnId": "01HW3K8XJZ..."   // ★ v3 新增
}
```

#### 2. 前端保存 pendingTurnId

`AuraBotProvider.tsx` 现有 confirm_required handler 必须从 SSE payload 读取 `pendingTurnId` 并存到 React state（与现有的 `pendingToolId` 同生命周期）。

#### 3. `/execute` 请求体加 pendingTurnId

```typescript
// 现状（auraBotApi.ts）
executeStream({ sessionId, toolId, confirmed }: ExecuteRequest)
// v3
executeStream({ pendingTurnId, toolId, confirmed }: ExecuteRequest)
```

后端 `ChatRequest` (or new `ExecuteRequest`) DTO 加 `pendingTurnId` 字段。

#### 4. ChatSessionStore key 迁移

当前 `ChatSessionStore.savePending(sessionId, ...)` 用 `sessionId` 作 key。Phase B 改为用 `turnId` 作 key（一个 session 可能有多个并发 pending tools，但每个属于不同 turn）：

```java
// 当前
chatSessionStore.savePending(sessionId, pendingTool);
chatSessionStore.findPending(sessionId);

// v3 Phase B
chatSessionStore.savePending(turnId, pendingTool);
chatSessionStore.findPending(turnId);

// 兼容期：同时 keyed by sessionId 和 turnId（Phase B 完成后清理）
```

#### 5. `resumeTurn(pendingTurnId)` 内部流程

```
1. ChatSessionStore.findPending(pendingTurnId) → PendingTool + 原 TurnContext
2. 验证 user / tenant 与原 turn 一致
3. 按 ConfirmDecision 分支：
   - APPROVED → 继续执行 pending tool；走原 turn 的 endTurn 路径
   - DENIED → endTurn(Interrupted, reason="user_denied")
   - CANCELLED → endTurn(Interrupted, reason="user_cancelled")
4. emit TurnCompletedEvent
5. SSE close
```

#### 6. PendingTool 持久化 payload schema（v3.1 新增 P2.6）

**问题**：当前 `PendingTool` 只存 toolId/args/sessionId 等执行细节，**不含 identity 上下文**。如果 `resumeTurn` 需要恢复 TurnContext 来调 endTurn → persistOutbound，必须知道原 turn 的 tenantId / userId / humanMemberId / agentId / conversationId。仅按 turnId 改 key 不足以支持可靠恢复。

**v3.2 PendingTool payload 升级**（修 P1.2：保留现有所有 resume 必需字段，**仅新增** identity 字段）：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public static class PendingTool {

    // ========== 现有字段（保持原状，resume 必需）==========
    // 来源：ChatSessionStore.PendingTool (auraboot/.../ChatSessionStore.java:125-148)
    private String toolId;                        // tool call id
    private String toolName;                      // 工具名（如 cmd_crm_lead_update）
    private Map<String, Object> input;            // 工具入参
    private String description;                   // 给用户看的描述
    private String modelCode;                     // 当前 page model code
    private String toolSpanId;                    // trace span id

    // 现有 resume 必需的 LLM 上下文（绝对不能删）
    private List<Map<String, Object>> messages;   // 完整对话历史（含 tool_calls / tool_results）
    private String providerCode;
    private String apiKey;                        // ★ resume 时重建 LLM client 需要
    private String baseUrl;
    private String model;
    private String systemPrompt;
    private Integer maxTokens;
    private int currentLoop;                      // tool loop 当前轮次

    @Builder.Default
    private long createdAt = Instant.now().toEpochMilli();

    // ========== v3.2 新增 identity 字段（runTurn 模型恢复 TurnContext 必需）==========
    private String turnId;                        // ★ 新 key (与 toolId 兼容期并存)
    private long tenantId;
    private long userId;
    private Long humanMemberId;                   // ab_im_conversation_member.member_id
    private Long agentId;                         // ab_agent_definition.id (AuraBot 默认 by Q11)
    private Long conversationId;                  // ab_im_conversation.id
    private String channelSessionId;              // ab_agent_channel_session.pid (Q10 解决后)
    private String agentCode;                     // 默认 "aurabot"
    private TriageBucket triageBucket;            // light_chat | contextual_answer | acp_run

    // partial response（已发出但未持久化的 agent 文本）
    private String partialAgentResponse;
}
```

**关键不变式**：
- 现有字段一个不删——`resumeAfterConfirmation` 当前直接读这些字段重建 LLM 调用
- 新增字段在 `suspendTurn` 路径填入；旧 caller（resumeAfterConfirmation 直接路径）允许新字段为 null（**Phase B 兼容期**），Phase B 完成后清理

**ChatSessionStore migration（B.6 子步骤）**：

| 步骤 | 内容 |
|------|------|
| B.6.1 | `PendingTool` DTO 加 12 个新字段；旧 caller 仍可填部分字段（backward compat）|
| B.6.2 | Redis key 兼容：写入时同时 keyed by `pending:session:{sessionId}` 和 `pending:turn:{turnId}`；读取优先 turnId。**原子消费**（v3.2 修 P2.6）：用 Lua script 保证同一 PendingTool 至多被消费一次——`EVAL "if redis.call('GET', KEYS[1]) then redis.call('DEL', KEYS[1], KEYS[2]) ... end" 2 turn_key session_key`；或写 `consumed:{turnId}` marker 配合 SETNX。绝对不允许"删 turn key 但保留 session alias"的中间态 |
| B.6.3 | `resumeTurn` 校验 `payload.tenantId == ctx.tenantId && payload.userId == ctx.userId`；不一致 reject |
| B.6.4 | Phase B 完成 + 30 天后由 owner 决定移除 sessionId 索引（独立 cleanup PR）|

**v3 决策**：以上 6 点均**必须在 Phase B 同期改齐**，单独改任一项都会破坏 confirm flow。Phase A 不动 `/execute` 路径（保留旧 ChatSessionStore + sessionId 行为）。

---

## 4. 三阶段路径

> **estimate 仅作 scope 参考**，不是承诺日程。

### Phase A：纯 refactor，零行为变更 + Persistence.NOOP（estimate ~1 week）

> **[v2 修正]** v1 在这里的"零行为变更"和 §3.4 接口契约（"已持久化用户消息后才返回"）冲突。v2 修法：
> Phase A 的 `ConversationTurnService.runTurn` 内部**显式注入 `Persistence.NOOP`**，
> 即调用 begin/end 但跳过实际持久化操作。`Persistence` SPI 在 Phase B 才注入真实实现。
> 这样 Phase A 接口形态稳定 + 0 行为变更可同时成立。

**目标**：把现有 6 条入口归一到 ConversationTurnService.runTurn，**不改持久化行为**（Persistence.NOOP）。

```java
public interface Persistence {
    Long persistInbound(TurnContext ctx, String userMessage, String clientMsgId);  // 返回 message_id
    Long persistOutbound(TurnContext ctx, TurnOutcome outcome);                    // 返回 message_id
    Persistence NOOP = new Persistence() {
        public Long persistInbound(TurnContext ctx, String m, String c) { return null; }
        public Long persistOutbound(TurnContext ctx, TurnOutcome o) { return null; }
    };
}
```

| 步骤 | 内容 | 文件 |
|------|------|------|
| A.1 | 新建 `ConversationTurnService` 接口 + `TurnRequest` / `TurnContext` / `TurnOutcome` / `ResponseSink` / `Persistence` SPI | 新增 ~250 行 Java |
| A.2 | **仅 `AuraBotController.POST /chat/stream` 改为调 `turnService.runTurn(...)`**（v3.1 修正 P1.1：`/chat` 与 `/execute` 仍走原路径，§3.5 表已标 Phase B/B+）；新建 `SseResponseSink` 适配器把 ResponseSink 桥到 SseEmitter | `AuraBotController.java` 仅改 `/chat/stream` 端点, `SseResponseSink.java` |
| A.3 | **`AuraBotConversationController` 的 `/messages/user` + `/messages/assistant` 端点保留**（Phase A 不动）；前端继续调用旧 endpoint。Phase B 才删 | — |
| A.4 | `AuraBotChatService.doStreamChat` 改签名接收 `TurnContext` + `ResponseSink`；内部 `sendChunk` / `sendDone` / `sendError` 全部改写到 sink；返回 `TurnOutcome` 不直接 close emitter | `AuraBotChatService.java` 大规模签名重构（1565 行核心） |
| A.5 | **不动 `GroupChatAgentRouter` / `ImAiService` / `AuraBotConversationController`**（reviewer P1.4+P1.7：异步 + WebSocket 路径与 runTurn 同步 lifecycle 不匹配，强行归一会破坏现有 AgentReplyTask 流；推迟到 Phase B+ 单独设计 group chat adapter，详见 Q13）| — |
| A.6 | **按 Q12 决策注入 `TurnSideEffects` profile**：Q12=N（默认）→ 注入 `TurnSideEffects.observeOnly(realMetrics)`；Q12=M → 注入 `TurnSideEffects.TRULY_DISABLED`（详见 §3.9）到 ConversationTurnServiceImpl bean 配置 | Spring `@Configuration` |
| A.7 | 跑全套现有 chat 集成测试 + E2E，确保 0 regression | `AuraBotChatServiceIntegrationTest`, E2E 套件 |

**deliverable**：纯 refactor；前端无感；现有持久化（前端调 #4 + #5）继续跑；为 B 阶段铺路。

**验证判据（v2 重写为可观测行为断言）**：
- 测试前后跑全套 E2E，所有 chat 场景 0 regression
- `psql -c "SELECT sender_type, count(*) FROM ab_im_message GROUP BY sender_type"` Phase A 前后分布相同
- 所有 SSE event 类型（chunk / tool_start / tool_result / confirm_required / error / done）的发送顺序与现状一致
- `AuraBotChatService.doStreamChat` 签名变了但行为字节级不变（用 SSE 录制对比）
- `runTurn` 内部 try/finally 验证：故意抛出异常路径时 `endTurn` 仍被调（单测）

**回滚边界**：每步独立 PR；任一步骤回归测试不过则 revert 该 PR，不影响下一步。`Persistence.NOOP` 是 Phase A 的安全网，无论 runTurn 内部决策如何，都不会真写库。

### Phase B：在 chokepoint 落 α 持久化 + 前端契约迁移（estimate ~1.5 weeks）

> **[v2 修正]** v1 严重低估 Phase B 范围。reviewer P1.3 指出现有前端 `appendUserMessage` / `appendAssistantMessage` 是当前持久化的实际承担者，服务端加持久化必须**同时**改前端契约，否则双写。
>
> v2 把前端契约迁移作为 B.0 显式列出。Phase B 实际是后端 + 前端 + 群聊三方协调。

**目标**：AuraBot 直连入口（#1 + #3）走统一持久化语义；前端不再代写持久化。**群聊 #7 + WebSocket #8 不在 Phase B 范围**（v3.2 修 P1.4）。

| 步骤 | 内容 |
|------|------|
| **B.0** | **前端契约变更**：(a) `auraBotApi.chatStream(...)` 签名加 `conversationId` + `clientMsgId` 字段；(b) `AuraBotProvider.tsx` 删除 `appendUserMessage` / `persistAssistantMessage` 调用；(c) `AiPageGenerateDialog` / `AiPagePanel` 等其他 chatStream 消费者同步更新 |
| B.1 | 新建 `AuraBotPersistence` 实现 `Persistence` SPI：`persistInbound` 用 `imMessageService.sendMessage(SendMessageRequest, senderId, tenantId)`（独立短事务，sender_type=human，带 clientMsgId 幂等去重） |
| B.2 | `persistOutbound(Success)` 用 `groupChatMessagePort.saveAgentMessage`（独立短事务，sender_type 按 §3.6 决策结果） |
| B.3 | `persistOutbound(Interrupted \| PendingConfirmation)`：persist partialResponse（非空时），加 `card_payload.partial=true` metadata |
| B.4 | `persistOutbound(Failed)`：返回 null（不写 agent 消息）；orchestrator 只写 audit + emit event |
| B.5 | **删除** `AuraBotConversationController` 的 `/messages/user` + `/messages/assistant` 端点（B.0 前端已不调）；保留 `GET /messages` 用于历史回放 |
| B.6 | **`/execute` continuation 端到端契约落地**（§3.10 五点）：confirm_required SSE event 加 pendingTurnId / 前端 React state 持有 / executeStream 请求体改 / ChatSessionStore key 迁移 / resumeTurn 内部流程；同步实现 PendingTool 的 identity payload schema（见 §3.10.6）|
| B.7 | 切换 Spring bean：按 Phase A 注入的 profile（Q12=N→`observeOnly`，Q12=M→`TRULY_DISABLED`）→ `TurnSideEffects.PRODUCTION`（含真实 Persistence + EventEmitter + AuditWriter + MetricsRecorder；trace 不变）；feature flag 决策见 Q9。**v3.2 修 P2.10**：profile 名按 v3.1 拆分后的 observeOnly/TRULY_DISABLED，不再用已删的 DISABLED |
| B.8 | 集成测试 + E2E：详见验证判据 |
| ~~B.6 (旧)~~ | **[v3.1 修正 P1.3] 移除**：群聊 #7 + WebSocket #8 已在 §3.5 标注延后到 Phase B+，B.6 不应在此重新引入 GroupChatAgentRouter 接入。详见 §4 Phase B+ |

**deliverable**：3 条 AuraBot 直连入口（#1 `/chat/stream` + #3 `/execute`，#2 `/chat` 见 P1.5 决策）持久化一致；前端不再代写；群聊 #7 + WebSocket #8 **不在 Phase B 范围**（已由 Q13 推迟到 Phase B+）。

**验证判据（v2 重写为可观测行为断言）**：
- **入口 1（`/chat/stream`）**：单 chat → DB 出现 1 条 `sender_type=human` 行（带 clientMsgId）+ 1 条 `sender_type=agent`（按 Q8 决策值）行，seq 严格递增
- **入口 3（`/execute` continuation）**：confirm 后 → DB **不**新增 human 行；新增 1 条 agent 行（pendingTurnId 关联）
- **入口 7（群聊 @mention）**：human 行只 1 条（由 ImMessageService 写入），agent 行 1 条；不双写
- **前端契约**：`grep -r "appendUserMessage\|appendAssistantMessage" web-admin/` 应返回 0 行（B.0 已删除）
- **幂等性**：相同 clientMsgId 两次请求 → DB 仅 1 条 human 行（依赖现有 `idx_ab_im_message_dedup` 唯一索引）
- **错误路径**：故意失败的 chat → DB 仅 1 条 human 行，无 agent 行；audit 表有 failure record
- **α 性能**：单 turn 新增 INSERT 耗时 ≤10ms（监控 connection-hold-time ≤50ms）

**回滚边界**：详见 Q9 决策。如保留 feature flag，则关闭路径 = Persistence.NOOP（退回 Phase A 行为，但前端已删除 append* 调用 → 无任何持久化）；这意味着 flag 关闭 ≠ Phase A 真实状态。**所以 B.0 与 flag 不能同时存在**；要么 dev stage 直切（Q9=直切），要么保留 flag 但延迟 B.0（Q9=保留）。

### Phase C：插件化插入 triage / ACP / memory（与 ACP Phase 3-4 同期）

| 步骤 | 内容 | 关联 |
|------|------|------|
| C.1 | **ConversationTurnService 仅准备 ChannelSession + ConversationHistoryDigest 输入**；`PreGroundingTriage.triage(...)` 在 Stage 2.5 executor 实际执行（按 Tier 0 `contracts/pre-grounding-triage.md`）；triage 结果回写 `TurnContext.triageBucket` + `ab_im_message.triage_bucket` 列。**v3.2 修 P2.9**：不在 beginTurn 加 hook | GAP-273 |
| C.2 | `executeTurn` 按 triage bucket 分发：`light_chat` → 直 LLM；`contextual_answer` → readonly tool 限制；`acp_run` → 委派 `ACPRuntime` (Stage 1-10) | GAP-273 + ACP Phase 3 |
| C.3 | `TurnCompletedEvent` 订阅者：`MemoryWriter`（L1 写回）/ `AuditService` / Trace persister | Memory L1/L2 已落 |
| C.4 | `ExecutionContext.channelSessionId` 从 TurnContext 注入，`PER_SESSION` effect lifetime 工作 | GAP-272 + v4 patches |

**deliverable**：v4 patches 全部接入，且**不需要再回改** `doStreamChat`。

**验证判据**：
- triage_bucket 在 prod 数据可见且分布合理（light_chat:contextual_answer:acp_run 比例符合预期）
- ACP Stage 1-10 lifecycle 在 acp_run 路径走通至少 1 个 E2E 场景
- Memory L1 写回经由 TurnCompletedEvent 订阅，与现有 SessionEndedEvent 协同（不重复写）

---

## 5. 风险评估与缓解（v2 修正）

| 风险 | 严重度 | 缓解 |
|------|--------|------|
| 重构 1565 行核心 chat 代码，回归 | 高 | Phase A 注入 `Persistence.NOOP` 保 0 行为变更；现有 E2E 守门；A 拆 7 步独立 PR |
| 跨 3 个 service + 前端协调，PR review 工作量大 | 中 | A 步骤化每步独立可 review；B.0 前端契约变更与后端 PR 配对（不能 standalone merge） |
| **B.0 前端契约破坏第三方 chatStream 消费者**（v2 新增） | **高** | 必须先 grep 全部 `chatStream(` 调用点：当前已知 `AuraBotProvider` / `AiPageGenerateDialog` / `AiPagePanel` 三处；B.0 必须三处全改齐 |
| **服务端 + 前端双写 race**（v2 新增） | **高** | B.0 与 B.1-B.7 必须**同一 PR 上线**，不允许中间态；client_msg_id + 现有 `idx_ab_im_message_dedup` 唯一索引兜底 |
| **Phase A 内部接口契约与 0 行为变更冲突**（v2 新增） | 中 | 显式注入 `Persistence.NOOP` SPI；接口形态稳定 + 行为不动同时成立 |
| **群聊路径 EXISTING_MESSAGE_ID 模式漏配置导致重复 INSERT**（v2 新增） | 中 | B.6 单测 + 集成测试覆盖；GroupChatAgentRouter 调用点必须传 inboundMessageId 且 InboundMode=EXISTING_MESSAGE_ID |
| **`ab_agent_channel_session` 主表缺失阻塞 PER_SESSION**（v2 新增 P2.8）| 中 | Q10 决策；选项 X 需 Phase A 前补 schema；选项 Z 接受降级 |
| Phase B 持久化失败的 error handling 改产品语义 | 中 | 不变式：beginNewInboundTurn 持久化失败 = 早 fail；persistOutbound 失败 = 写 audit 但不回滚响应 |
| 与 ACP Phase 3 实施期冲突 | 低-中 | Phase A 反而加速 Phase 3，作为前置任务 |
| 移动端 BFF 已有自己的 chat 桥接 | 中 | Q7：A.2 前调研；若 BFF 绕过 AuraBotController，A 阶段必须把 BFF 端也归一 |
| feature flag 与 B.0 互斥 | 中 | Q9 决策：dev stage 直切 / 保留 flag 但延迟 B.0；不允许"flag 开着 + 前端已删 append*"组合 |

---

## 6. 决策矩阵：patch vs refactor 6 个月成本

| 路径 | 现在 | 1 个月（GAP-273） | 3 个月（ACP Phase 3-4） | 6 个月（mobile sync） | 总成本 |
|------|------|-----|-----|-----|--------|
| **Patch GAP-270 单点** | 0.5 day | 1-2 day（再钻 doStreamChat） | 1 week（撕掉重做） | 1 week（再做一次） | **~3 weeks + 1565 行还在涨** |
| **Refactor 抽 ConversationTurnService** | 1 week (Phase A) + 1.5 week (Phase B) + Phase B+（待估）| 0.5 day（只加 triage hook） | 与 Phase 3 合并，节省 ~3 day | 0 day（已是统一入口） | **~3 weeks 且 1565 行收缩**（含 Phase B+ 不确定项）|

> **[v3.1 修正 P2.7]** v3 修正后两路径总工时几乎相等，原 v2 "净节省 30%" 论证已失效。
> Refactor 路径的真实优势**不再是工时**，而是：
>
> 1. **架构一致性**：1 个 chokepoint vs 4-8 处分散逻辑；持久化/triage/audit/memory 决策单点收敛
> 2. **风险分布**：Phase A 0 行为变更 + Phase B 与前端契约配对上线，每步可单独回滚；patch 路径每次 reopen 1565 行核心代码风险叠加
> 3. **未来变更成本递减**：refactor 后所有 chat 相关需求（GAP-273 / ACP Phase 3-4 / mobile sync / audit 合规）改动都在统一接口面；patch 后每个需求都要从 1565 行起重新理解
> 4. **代码可维护性**：1565 行 → 收缩为 streaming impl + 清晰 L5 chokepoint；patch 路径 1565 行 → 涨到 3000 行
> 5. **测试可观测性**：runTurn 单 orchestrator 可单测覆盖各 outcome；patch 路径每个 sendDone/sendError call site 独立测试
>
> **结论修正**：refactor 不是"省 30% 工时"，是"用相同工时换取一致性 + 风险可控 + 未来变更可预测"。owner 评估应以这 5 项质量收益为基础，而非工时账。

---

## 7. 推荐结论

### 7.1 推荐：走 refactor 路径（Phase A → B → C），不走 patch

理由按重要性排序：

1. **AuraBoot 在开发阶段**（per `feedback_dev_stage_breaking_ok` memory）：允许破坏性变更，正是做架构纠正的窗口期。GA 后做这种重构成本翻倍。

2. **L5 chokepoint 是 ACP canonical 体系本来就有的位置**：`ACP-Architecture §2.1` 的 5 层下沉显式定义了 L5。补 L5 = 补已有架构的缺口，不是发明新抽象。

3. **当前 5 个未来需求（GAP-270/273/272 + Phase 3/4 + mobile sync）都通过同一个 chokepoint 解决**：refactor 一次，5 个需求落地都简化。

4. **Patch 路径的"快"是假象**：6 个月看实际成本反而更高 ~30%，且留下 1565 行越涨越大的反模式文件。

5. **Phase A 是纯 refactor**：风险可控，0 行为变更，回归测试守得住。即使 Phase B/C 推迟，Phase A 本身就是净改进。

### 7.2 不推荐 patch 的硬反对意见（公平起见）

- **"现在是 Phase 3 实施期，不该开新战场"**：但 Phase A 反而**加速** Phase 3，因为给了它干净的接入点；Phase A 应作为 Phase 3 的**前置任务**。
- **"1 周 vs 0.5 天差太多"**：但 0.5 天的 patch 6 个月内会被 4 次需求 reopen，总成本反而高。
- **"重构有回归风险"**：现实地接受；但 Phase A 0 行为变更 + 完整测试守门 = 风险可控；继续 patch 才是把 1565 行养到 3000 行的不可控路径。

### 7.3 退路：如果有强 demo 压力必须 1-2 周内见到 triage 数据

走方案 (a) try-finally + 累加器，**仅持久化用户消息**（agent 消息不在这一轮做）：

- 用户消息持久化是 triage 字段的载体；agent 消息持久化可以延后到 refactor
- 拆 GAP-270 → GAP-270a（user persist only，0.5-1 day）+ GAP-270b（agent persist + 完整改造，并入 refactor）
- 接受 ~30% 总工时损失，换 1 周提前 demo
- **但要明确**：GAP-270a 是 short-term hack，6 个月内必须由 refactor 替换

---

## 8. 决策点清单（owner 评审时拍板，v3 增至 13 项）

| # | 决策 | 选项 | 我的倾向 |
|---|------|------|---------|
| Q1 | 走 refactor 还是 patch？ | refactor / patch / 退路（GAP-270a + 延后） | **refactor**（理由见 §7） |
| Q2 | Phase A 是否作为 ACP Phase 3 的前置任务？ | 是（前置） / 并行 / Phase 3 后 | **前置**（避免冲突 + 加速 Phase 3） |
| Q3 | `ConversationTurnService` 命名是否合适？ | 是 / `ChatTurnService` / 其他 | 略偏 ConversationTurn（"chat" 太窄，未来含 webhook/BPM 触发） |
| Q4 | runTurn 单 orchestrator vs begin/execute/end 三段（v2 已采纳 reviewer P1.5 建议）| runTurn 单段 / 三段 / 折中 | **runTurn 单段**（已写入 §3.4） |
| Q5 | `endTurn(Interrupted)` 的 partialResponse 是否持久化？ | 持久化 / 不持久化 / metadata 标记 | **持久化 + `card_payload.partial=true` 标记** |
| Q6 | `endTurn(Failed)` 是否完全不写 ab_im_message？ | 完全不写 / 写 agent + content=error | **完全不写**（错误走 audit + trace） |
| Q7 | 移动端 BFF 是否在 A.2 范围内？ | 是 / 否 / 先调研 | **必须先 grep 调研**，A.2 前给出结论 |
| **Q8** | **outbound sender_type 统一选项**（v2 新增 P2.6）| A=`agent`+agentId / B=保留`system` / C=新增`aurabot` | **A**（与群聊一致 + 历史 backfill）|
| **Q9** | **feature flag 是否保留**（v2 新增） | 保留（B.0 延迟）/ dev stage 直切（不要 flag）| **直切**（per `feedback_dev_stage_breaking_ok`，避免与 B.0 互斥）|
| **Q10** | **`ab_agent_channel_session` 主表来源**（v2 新增 P2.8）| X=Phase A 前补 CREATE / Y=合并到 _state 表 / Z=接受 channelSessionId=null 降级 | **X**（保 PER_SESSION 完整语义）|
| **Q11** | **AuraBot 默认 agentId 来源**（v3 新增 P1.6）| P=OSS 内置全局 agent_definition / Q=per-tenant bootstrap seed / R=保留 sender_id=0 system 撤回 Q8=A | **Q**（per-tenant seed + AuraBotAgentResolver SPI）|
| **Q12** | **Phase A 真实 0 行为变更范围**（v3 新增 P1.3）| M=完全 NOOP（含 trace+metrics）/ N=持久化+事件+audit NOOP，trace+metrics 保留 / O=全部正常发布 + metrics 标签区分 | **N**（保观测，不动业务）|
| **Q13** | **群聊 #7 + WebSocket #8 路径如何归一**（v3 新增 P1.4+P1.7）| α=Phase B+ 单独设计 group chat adapter（异步 lifecycle）/ β=保留现状 / γ=强行同步化 | **α**（Phase B+ 单独 sub-design，非本设计范围）|

---

## 9. 与现有 ACP 资产的对接锚点

| 现有资产 | 对接方式 |
|---------|----------|
| `contracts/runtime-core.md` `ExecutionContext` | TurnContext.channelSessionId 是 `ExecutionContext.channelSessionId` 的注入源 |
| `contracts/runtime-core.md` Turn Lifecycle 10-Stage | TurnContext 是 Stage 1 的输入；ConversationTurnService 在 Stage 1 之前 |
| `contracts/pre-grounding-triage.md` `PreGroundingTriage` | **ConversationTurnService 仅准备 ChannelSession + ConversationHistoryDigest 输入**；PreGroundingTriage 实际执行仍按 Tier 0 contract 在 Stage 2.5（不在 beginTurn 内）；triage 结果在 Stage 2.5 后回写 TurnContext.triageBucket |
| `contracts/runtime-authorization.md` `RuntimeAuthorizationService` | `executeTurn` 委派 ACPRuntime 时，TurnContext 提供 channelSessionId 给 PER_SESSION lifetime |
| `contracts/effect-taxonomy.md` `EffectClass` | `endTurn` 写 audit 时记录 actual_effects |
| `ACP-Ideal §6.1.5` Interrupt Protocol | `endTurn(Interrupted)` 与 §6.1.5 `INTERRUPTED` run status 对齐；TurnContext.turnId 与 `ab_agent_run.pid` 关联 |
| `ACP-Ideal §6.5` Memory Type × Lifecycle | `TurnCompletedEvent` 订阅者写 L1；与现有 `SessionEndedEvent` 协同（avoid double-write） |
| `GroupChatMessagePort` (`GroupChatMessageAdapter:164`) | Phase B 持久化直接复用，不重新发明 |
| `ImMessageService.sendMessage` | Phase B 用户消息持久化的 service 入口 |
| `AuraBotConversationService` | A.4 内部归一到 turnService；B.5 移除独立持久化 |

---

## 10. 不在范围内（out of scope）

- **重新实现 SSE / streaming transport**：Phase A 仅签名重构，streaming 实现保留在 `AuraBotChatService`
- **改动 LLM provider 抽象**：`LlmProvider` / `LlmProviderFactory` 不动
- **改动 D1 Grounding / Skill / Action 任何实现**：那些是 L4-L2 层职责，本设计只补 L5
- **改动 ACP Stage 1-10 lifecycle 内部**：本设计在 Stage 1 之前，不入侵 lifecycle
- **多租户 / 跨租户协作**：现状假设单租户，跨租户在 OSS 不支持
- **chat 历史压缩 / 召回优化**：那些归 Memory 子系统
- **mobile BFF 重构**：如果 BFF 绕过 AuraBotController，需单独 spec
- **流式响应的可观测性增强**：trace span 改进归 AiTraceService

---

## 11. 评审通过的判据（v2 重写为可观测行为断言）

本设计稿评审通过的硬条件：

1. **§8 决策点 13 项中阻塞实施的至少 9 项必须 owner 给出明确决策**：
   - **Q1-Q4**（refactor 路径 / Phase A 与 ACP Phase 3 关系 / 命名 / 单 orchestrator）
   - **Q8-Q10**（sender_type 选项 / feature flag vs B.0 互斥 / channel_session 主表来源）
   - **Q11**（AuraBot 默认 agentId 来源；不决定 → Phase B 的 persistOutbound 写不出 agent 消息）
   - **Q12**（Phase A 真实 0 行为变更范围；不决定 → A.6 不知道注入 TRULY_DISABLED 还是 observeOnly）
   - **Q13**（群聊 + WebSocket 路径分期；不决定 → Phase B+ 范围悬空，可能再次踩双写）
   - Q5-Q7 可在 Phase A 实施期间补决（不阻塞门禁）
2. **若 Q1=refactor**：Phase A 拆 7 步 + Phase B 拆 9 步 + Phase C 拆 4 步的范围都被认可
3. **§5 风险表 11 项缓解措施 owner 都接受**（特别是 v2 新增的 4 项：B.0 第三方影响 / 双写 race / Persistence.NOOP / channelSessionId）
4. **§9 资产对接锚点与 owner 对 ACP canonical 体系的理解一致**
5. **若 Q7 调研显示 mobile BFF 绕过 AuraBotController**：补一份 mobile-bff 子设计后再开干
6. **若 Q10=X**：补一份 `ab_agent_channel_session` 主表 schema 提案（CREATE TABLE + ChannelSessionResolver 接口签名），由 enterprise/docs/agent/contracts/ 接收
7. **若 Q8=A**：补一份 `sender_type=system → agent` 的历史数据 backfill SQL + 前端 UI 兼容确认

### Phase A 完成后的可观测行为断言（不再用 git log --grep 类非行为指标）

- `psql -c "SELECT sender_type, count(*) FROM ab_im_message GROUP BY sender_type"` Phase A 前后分布相同
- 全套 chat E2E（含 `/chat/stream` / `/execute` continuation / 群聊 @mention）回归 0 失败
- SSE event 顺序录制对比：Phase A 前后字节级一致
- 单测：`runTurn` 内部 try/finally 在故意抛 RuntimeException 时 `endTurn` 仍被调（验证 orchestration 闭合）

### Phase B 完成后的可观测行为断言（v3 修正 P2.9）

- **入口 #1（`/chat/stream`）+ #3（`/execute`）**：各自集成测试覆盖 4 种 TurnOutcome（Success/Interrupted/PendingConfirmation/Failed）；其中 PendingConfirmation 必须验证 phase=SUSPENDED 时只发 `TurnSuspendedEvent`，resume 后才发单一 `TurnCompletedEvent`（v3.2 修 P1.1）
- **入口 #2（`/chat` 非 SSE）**：保留现状不归一（v3.2 修 P1.5）；不在 Phase B 验收范围
- **入口 #6（IM 直接 `/messages`）**：仅验证"**不受 turnService 影响**"——sender_type=human 行为与 Phase A 完全一致；不要求 TurnOutcome 4 种覆盖（IM 直接 send 没有 turn lifecycle）
- **入口 #7 + #8（群聊 + WebSocket @AI）**：**不在 Phase B 范围**；保留现状直到 Q13 决策 + 单独 Phase B+ adapter
- prod sender_type 分布按 Q8 决策值生效（如 Q8=A：入口 #1-#5 outbound 全部 sender_type=agent；入口 #7 已经是 agent；入口 #8 仍 system 直到 Phase B+）
- `grep -r "appendUserMessage\|appendAssistantMessage" web-admin/` 返回 0 行
- `/execute` 端到端契约（§3.10 五点）：
  - SSE confirm_required event payload 含 `pendingTurnId`
  - 前端 React state 持有 `pendingTurnId` 并在 executeStream 请求中带回
  - `ChatSessionStore` 按 `turnId` 索引（兼容期同时 keyed by sessionId 和 turnId）
- 同 clientMsgId 重复请求 → DB 仅 1 条 human 行（依赖 `idx_ab_im_message_dedup` 唯一索引）
- 失败路径 → DB 无 agent 行；audit 表有 failure record
- α 性能：单 turn 新增 INSERT 耗时 ≤10ms；connection-hold-time ≤50ms

满足全部条件 → 进入 Phase A 实施（worktree `feat/conversation-turn-service-phase-a`，从 main 拉新分支，A 7 步分 7 个小 PR）。

---

## 12. 立即可做的下一步（按推荐路径）

| 步骤 | 动作 | 触发 |
|------|------|------|
| 1 | owner review 本设计稿（重点 §7 推荐 + §8 决策点） | **现在** |
| 2 | owner 给出 Q1-Q4 决策；若 Q1=refactor 则给 Q5-Q7 | review 后 |
| 3 | grep 调研移动端 BFF chat 路径（Q7 unblock） | Q1=refactor 后 |
| 4 | 删除 GAP-270 单点条目，新增 GAP-275/276/277（Phase A/B/C） | 设计通过后 |
| 5 | 重命名 worktree `feat/gap-270-aurabot-doStreamChat-persistence` → `feat/conversation-turn-service-phase-a` | 实施开始时 |
| 6 | 启动 Phase A.1（ConversationTurnService 接口 + DTO 新增） | 实施期 |

---

## CHANGELOG

- 2026-04-26 v1 初始化（GAP-270 调研发现 AuraBotChatService 分层错配，触发本设计；推荐 refactor 路径）
- 2026-04-26 v2 二轮 review 13 项修正合入：
  1. **真实 API 入口表修正**（§1.1）：6 条物理路径 + 真实 controller 类名 + 真实 URL 路径（`/api/ai/aurabot/*` 而非 `/api/aurabot/*`）
  2. **新增 §1.4 真实调用链**：前端 `appendUserMessage` + `chatStream` + `appendAssistantMessage` 三段调用，两端吞异常；持久化职责当前在前端而非后端漏写
  3. **接口拆 runTurn 单 orchestrator**（§3.4）：解决 v1 begin/execute/end 三段责任边界漏洞；`ResponseSink.isClientConnected()` 加 transport 状态查询
  4. **新增 InboundMode 枚举 + 双 begin 模式**：`NEW_FROM_REQUEST`（创建+持久化）vs `EXISTING_MESSAGE_ID`（群聊已持久化场景），避免双写
  5. **TurnRequest 新增 conversationId + clientMsgId 字段**：前端 chatStream 契约必须配合修改
  6. **新增 §3.5 入口适配映射表**：7 条入口在 v2 下的归一方式；明示 `/messages/user` `/messages/assistant` 端点 Phase B 删除
  7. **新增 §3.6 sender_type 决策点**（Q8）：当前 system vs agent 分裂；倾向选项 A 统一 agent + backfill
  8. **新增 §3.7 channelSessionId schema 缺失**（Q10）：`ab_agent_channel_session` 是 phantom table，必须先解决主表来源
  9. **Phase A 加 Persistence.NOOP**：解决接口契约与 0 行为变更的冲突；显式 SPI 注入
  10. **Phase B 加 B.0 前端契约迁移**：3 处 chatStream 消费者必须同步改齐；与后端 PR 配对上线避免 race
  11. **Phase B 加 B.6 群聊 EXISTING_MESSAGE_ID 验证**：避免 GroupChatAgentRouter 触发 beginTurn 时双 INSERT
  12. **风险表加 4 项**（§5）：B.0 第三方影响 / 双写 race / Persistence.NOOP 契约 / channelSessionId 阻塞
  13. **决策点扩到 10 项**（§8）：新增 Q8 sender_type / Q9 feature flag 与 B.0 互斥 / Q10 主表来源
  14. **验收判据重写**（§11）：移除 `git log --grep` 类非行为指标；改为 SQL 分布断言、SSE 字节对比、grep web-admin 残留、性能阈值断言
- 2026-04-26 v3 三轮 review 10 项修正合入：
  - **优先三件齐**：
    1. **§3.8 IM/AuraBot Sender Identity 模型**（P1.5+P1.6）：humanMemberId（ab_im_conversation_member.member_id，非 user_id）+ agentId（ab_agent_definition.id）显式加入 TurnRequest/TurnContext；新增 `AuraBotAgentResolver` SPI；新增决策 Q11（per-tenant seed AuraBot agent_definition）
    2. **§3.9 Phase A 真实零行为边界**（P1.3）：Persistence.NOOP 不够，新引入 `TurnSideEffects.DISABLED` 套装（Persistence + EventEmitter + AuditWriter + MetricsRecorder 全 NOOP）；新增决策 Q12（trace+metrics 是否保留）
    3. **§3.10 `/execute` Continuation 端到端契约**（P1.2）：5 点契约（confirm_required event 加 pendingTurnId / 前端 React state 持有 / executeStream 请求体改 / ChatSessionStore key 迁移 / resumeTurn 内部流程），全部必须 Phase B 同期改齐
  - **剩余 7 项修正**：
    4. **§1.1 入口 #8 ImAiService**（P1.7）：补 WebSocket @AI 路径（`ImWebSocketHandler:151-152` → `ImAiService.generateResponse` → sender_type=system）；至此入口表 8 条
    5. **§1.1 入口 #7 异步标记**：群聊 @mention 是 `AgentReplyTask` fire-and-forget 异步；与 SSE 同步 lifecycle 不匹配
    6. **§3.5 入口适配映射表 v3 重排**：明示哪些入口在 Phase A0 / B / B+；群聊 #7 + WebSocket #8 推迟到 Phase B+ 单独 sub-design
    7. **Phase A.5 不动 GroupChatAgentRouter / ImAiService**（P1.4）：避免破坏 AgentReplyTask 异步流；Phase A 只做 doStreamChat 一条入口归一
    8. **TurnRequest conversationId/clientMsgId/humanMemberId 在 Phase A nullable**（P1.1）：解决"接口 required vs Phase A 不改前端"冲突；Phase B 改前端时变 required
    9. **§9 triage 残留修正**（P2.8）：ConversationTurnService 仅准备 ChannelSession + ConversationHistoryDigest 输入，PreGroundingTriage 实际执行仍按 contract 在 Stage 2.5
    10. **§11 IM 入口 #6 验收语义修正**（P2.9）：改为"不受 turnService 影响"，不要求 4 outcome 覆盖
    11. **§3.1 架构图 + §6 cost matrix 数字一致性**（P3.10）：清理 `/api/aurabot/*` 残留为 `/api/ai/aurabot/*`；Phase B 估算 1w → 1.5w 全文一致；总成本 2.5w → 3w
  - **新增决策 Q11/Q12/Q13**：扩到 13 项 owner 决策
  - **scope 收敛**：Phase A 从"3 套 service 全归一"收敛到"仅 doStreamChat 一条入口"；群聊 + WebSocket 推迟到 Phase B+ 由独立 group-chat-adapter sub-design 处理
- 2026-04-26 v3.1 第四轮 review 9 项收口（v3 新增内容与旧段落不一致）：
  1. **§3.4 删除旧 TurnRequest/TurnContext record**（P1.2）：canonical shape 单源在 §3.8.1，避免双源漂移
  2. **A.2 收口到只 `/chat/stream`**（P1.1）：`/chat` 与 `/execute` 在 Phase A 不动，与 §3.5 / §3.10 表述一致
  3. **B.6 移除群聊接入**（P1.3）：原 B.6 群聊 EXISTING_MESSAGE_ID 验证已与 v3 决策 Q13（推迟 Phase B+）冲突；新 B.6 改为 `/execute` 端到端契约落地
  4. **§11 审批门禁加 Q11-Q13 必决**（P1.4）：从原"前 6 项"改为"9 项必决 + Q5-Q7 可补决"
  5. **TurnSideEffects 拆 TRULY_DISABLED + observeOnly**（P2.5）：解决 v3 单一 DISABLED 与 Q12=N 倾向的冲突；A.6 按 Q12 决策值注入对应 profile
  6. **§3.10 加 PendingTool identity payload schema**（P2.6）：12 个新字段 + Redis key 兼容期 migration（B.6.1-B.6.4）
  7. **§6 成本矩阵结论重写**（P2.7）：去掉失效的"30% 节省"，改为 5 项质量收益（架构一致性 / 风险分布 / 未来变更成本 / 可维护性 / 可观测性）
  8. **§3.6 Q8 加 ImAiService 数据迁移**（P2.8）：明示 ImAiService 的 system 行 backfill 风险（无显式来源标记字段）+ Phase B+ sub-design 必须解决
  9. **数字残留清理**（P3.9）：§1.1 入口数 6→8；§8 标题 v2 10→v3 13
- 2026-04-26 v3.2 第五轮 review 10 项收口：
  1. **PendingConfirmation 生命周期重设计**（P1.1，最深的修正）：引入 TurnPhase enum (ACTIVE / SUSPENDED / COMPLETED)；PendingConfirmation 走 `suspendTurn` 路径发 `TurnSuspendedEvent`，**不**走 endTurn；resumeTurn → COMPLETED 时再发单一 TurnCompletedEvent。整 turn 生命周期 TurnCompletedEvent 至多一次。新增 `finalizeTurn` 内部分发函数 + 伪码示例
  2. **PendingTool schema 复用现有完整字段**（P1.2）：v3.1 简化掉的 apiKey/baseUrl/model/systemPrompt/maxTokens/currentLoop/messages 等 resume 必需字段全部保留；新增 identity 字段叠加，旧 caller 兼容期允许新字段为 null
  3. **删 humanMemberId 前端契约要求**（P1.3）：明示由 `AuraBotController.currentHumanMemberId()` 服务端注入；前端 chatStream 仅新增 conversationId + clientMsgId
  4. **Phase B deliverable + 验收完全清掉群聊**（P1.4）：deliverable 改为"3 条 AuraBot 直连"；§11 验收删除"入口 #7 验证"；明示 #7+#8 全归 Phase B+
  5. **/chat 同步路径决策：保留现状不归一**（P1.5）：当前 endpoint 只返回提示，不实走 doStreamChat；同步 ResponseSink 协议未定义；不强行归一，待后续真有 sync 需求再单设
  6. **ChatSessionStore 双 key 原子消费**（P2.6）：B.6.2 加 Lua script 或 SETNX consumed marker，禁止"删 turn key 但保留 session alias"中间态
  7. **#7 群聊事件标 latent**（P2.7）：源码未找到 `publishEvent(new ImMessageSentEvent(...))` 实际调用点；监听器 + event class 已就位但未接线
  8. **§3.1 主架构图重画**（P2.8）：runTurn + suspendTurn + Stage 2.5 triage + Phase A/B/B+ 范围标注 + 不归一入口标注；删除旧 beginTurn 内执行 PreGroundingTriage 的描述
  9. **Phase C C.1 + §3.2 表 triage 描述对齐**（P2.9）：明示 ConversationTurnService 仅准备 input；PreGroundingTriage 实际执行在 Stage 2.5
  10. **B.7 profile 名修正**（P2.10）：从已删的 DISABLED 改为按 Q12 决策值的 observeOnly/TRULY_DISABLED → PRODUCTION
