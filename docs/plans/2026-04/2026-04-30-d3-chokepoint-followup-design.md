# D.3-chokepoint Follow-up — HandoffToolProvider × AgentChatPort SPI Integration

> **Status**: v1 draft (2026-04-30) — decisions surfaced, not yet owner-locked.
> **Predecessor**: [`2026-04-30-conv-turn-svc-phase-d-multi-channel-design.md`](./2026-04-30-conv-turn-svc-phase-d-multi-channel-design.md) v3 §8 row D.3 deferral note.

## 0. 摘要

Phase D.3 (`afbc283e`) landed the handoff chain → `ab_agent_task` parent_id linkage (Q-D.4=α complete) but explicitly deferred the second half of the original spec — `@Async wrapper 调 turnService.runTurn` (Q-D.1=α partial). The deferral happened because the existing chokepoint dispatch for named agents (`ConversationTurnService.runTurn` → `AgentChatPort.runAgentTurn` → `AgentChatPortImpl`) does NOT support handoff, and shoving group-chat traffic through it would silently drop the `transfer_to_agent` capability.

This follow-up surfaces the SPI integration question: **how should HandoffToolProvider integrate with the named-agent chokepoint path so group-chat agents can route their LLM calls through `runTurn` while preserving handoff semantics?**

Outcome targets:
- `GroupChatAgentRouter` / `AgentReplyTask` LLM call goes through `turnService.runTurn(req, sink)` (Q-D.1=α full)
- Handoff (`transfer_to_agent`) still works — child task created, handoff message broadcast, recursion bounded by `MAX_HANDOFF_DEPTH=5`
- Existing `ResponseSink` / `BroadcastResponseSink` contract preserved
- No regression to other named-agent callers (e.g. enterprise group chats that don't use handoff)

Non-targets:
- Removing `HandoffToolProvider` (it stays; only its registration path changes)
- ACP plan-loop integration for group-chat agents (separate concern; design v3.3 §3.6)

---

## 1. 现状对照

```
D.3 实施后:

GroupChatAgentRouter.onMessageSent (@Async)
  └─> AgentReplyTask.executeReply (@Async, opens ab_agent_task root)
        └─> executeReplyWithDepth(taskPid)
              ├─ load AgentDefinition / build context / build tools
              │    └─ HandoffToolProvider.getToolDefinition(otherAgents) ← ❶ injected here
              ├─ provider.chat(...) ← ❷ direct LLM call (NOT chokepoint)
              ├─ if stopReason=tool_use & toolName=transfer_to_agent:
              │    └─ HandoffToolProvider.execute(...)
              │       ├─ close current task (handoff_to:targetCode)
              │       ├─ open child task (parent_id=current)
              │       └─ executeReplyWithDepth(childTaskPid, depth+1)
              └─ else: save reply + close task

D.3-chokepoint target:

GroupChatAgentRouter.onMessageSent (@Async)
  └─> AgentReplyTask.executeReply (@Async, opens ab_agent_task root)
        └─> turnService.runTurn(TurnRequest{agentCode, EXISTING_MESSAGE_ID}, BroadcastResponseSink)
              └─ ConversationTurnService dispatches by agentCode → AgentChatPort.runAgentTurn
                    └─ AgentChatPortImpl runs LLM tool loop with discovered tools
                       (must include transfer_to_agent ← ❸)
                    └─ on transfer_to_agent: surface handoff signal somehow ← ❹
        └─ on handoff signal: close current task + recurse with child task
```

The two open questions: ❸ how does `AgentChatPortImpl` get the handoff tool registered for group-chat dispatches; ❹ how does `AgentChatPortImpl` signal "handoff requested" up to the caller.

### 1.1 Where the handoff tool currently lives

`HandoffToolProvider` (`platform/src/main/java/com/auraboot/framework/agentchat/handoff/HandoffToolProvider.java`):
- Single class, `@Component`, knows nothing about ToolProviderRegistry
- `getToolDefinition(List<AgentMemberDto> availableAgents)` returns `LlmChatRequest.Tool`
- `execute(Map<String, Object> input, Map<String, AgentMemberDto> agentByCode)` returns `HandoffResult`
- Tool name: `transfer_to_agent`
- Inputs depend on the conversation's other members — list materialized at call time

### 1.2 ToolProviderRegistry's discovery model

`ToolProviderRegistry`:
- `discoverAll(ToolDiscoveryContext ctx)` — returns `List<ToolDefinition>`
- Tool catalog is per-tenant + per-context (modelHint / intentHint)
- Provider-based plugin model (DSL command, named-query, MCP, etc.)
- Tools surface as `ToolDefinition` (a different DTO from `LlmChatRequest.Tool`)

The handoff tool fundamentally differs from registry-discovered tools: its parameters are **conversation-scoped** (the list of valid `targetAgentCode` enum values is the OTHER members of THIS conversation), whereas registry tools are **tenant-scoped** (the same DSL command is callable wherever the LLM has the tool).

---

## 2. 决策点（owner 拍板）

### Q-DC.1 HandoffToolProvider 与 ToolProviderRegistry 的关系

| 选项 | 描述 | Pro | Con |
|------|------|-----|-----|
| α 注册为一个 ToolProvider 实现 | 实现 `ToolProvider` 接口；`discoverFor(ctx)` 在 ctx 包含 `conversationId` 时返回 transfer_to_agent；ctx 缺失则空。`AgentChatPortImpl` 把 conversationId 灌进 ctx | 单一统一抽象；`AgentChatPortImpl` 不需 special-case handoff | ToolProvider 接口需要扩展或 ctx 需要承载 conversationId（当前 `ToolDiscoveryContext` 没有此字段） |
| **β 旁路注入**（建议）| `AgentChatPort.runAgentTurn` 签名加 optional `extraTools: List<ToolDefinition>`；`AgentReplyTask` 调用前从 HandoffToolProvider 拿 tool definition 灌进去；其他 named-agent 调用方（aurabot main path）传空 | 改动最小；handoff 的"会话 scope"语义本来就独立于 tenant tool catalog；不污染 ToolProviderRegistry | SPI 加参数；调用方记得传（容易漏） |
| γ AgentChatPortImpl 内部识别 group-chat ctx | TurnContext 加 `channel` 字段；AgentChatPortImpl 看 channel == "im_group" 时主动从 HandoffToolProvider 拿 tool；其他 channel 不拿 | 调用方零改动；channel-driven dispatch 自然 | AgentChatPortImpl 直接耦合 HandoffToolProvider（agentchat 模块依赖）— 跨模块依赖反向 |

**倾向 β**。handoff 的会话 scope 语义本就是"AgentChatPort 调用方掌握会话上下文 → 由调用方决定灌什么额外工具"。SPI 加 optional 参数比把 conversationId 推到 registry / 反向跨模块依赖都直接。

### Q-DC.2 transfer_to_agent 工具命中后的信号传递

`AgentChatPortImpl` 当前在 LLM tool loop 中：read-only 工具自动执行；confirmation-required 工具 → `TurnOutcome.PendingConfirmation`；其他工具 → 走完循环。handoff 是哪种？

| 选项 | 描述 |
|------|------|
| α 视为终止信号 → `TurnOutcome.Success` 带 meta 字段 | LLM 调用 transfer_to_agent → AgentChatPortImpl 不执行该工具，直接返回 `Success(text=handoffMsg, meta={handoffTo:targetCode, handoffContext:...})`。AgentReplyTask 看 meta 里有 handoffTo → 触发递归 |
| β 新加 TurnOutcome.HandoffRequested 变体 | 与 PendingConfirmation 平行；caller dispatch 到 handoff 处理 |
| γ AgentChatPortImpl 直接执行 handoff（递归调 runTurn） | AgentChatPortImpl 知道 conversationId → 直接建子 task + 递归 |

**倾向 α**。新加 outcome 变体污染所有现有 caller（chokepoint dispatch / persistOutbound / metrics）；γ 让 SPI 实现承担业务流程协调（不是它的职责）。α 用现有 Success.meta 通道传 handoff 信号，AgentChatPortImpl 改动 ~30 行，AgentReplyTask 适配 ~50 行。

### Q-DC.3 AgentReplyTask 的 task 链如何与 chokepoint task 整合

D.3 已让 AgentReplyTask 写 `ab_agent_task` row。D.3c 的 chokepoint dispatch 路径（C.3c 实现）也写 `ab_agent_task` row（每 turn 一 task）。两个 task 写入会重复。

| 选项 | 描述 |
|------|------|
| α AgentReplyTask 不再开 task，让 chokepoint 接管 | runTurn 内部已建 task；AgentReplyTask 只在外层做 handoff 链协调，handoff 时 close 上一个 chokepoint task + 让下一次 runTurn 在 child task 上下文里跑 |
| β 双 task 模型 | AgentReplyTask 开 outer task（handoff 全链路）；chokepoint 内部开 inner task（单 LLM 回合）；inner task.parent_id = outer task.pid |
| γ 保持 D.3 现状 | AgentReplyTask 开 task，chokepoint 路径关闭 task 创建分支（when channel=im_group） |

**倾向 α**。一个语义统一的 task 模型最干净；β 让 mission view 看到嵌套 task 链增加复杂度；γ 让 chokepoint claim 在 group-chat 通道装饰化（task 创建路径分叉）。

落地需要：runTurn 接受 `parentTaskPid` 参数（TurnRequest 加字段），AgentReplyTask 在 handoff 时把 parentTaskPid 传下去。

### Q-DC.4 BroadcastResponseSink 与 SseEmitterManager 双 transport 在群聊路径

D.3 现状 AgentReplyTask 用 `SseEmitterManager.sendToUsers(SseEventType.STREAM_CHUNK / STREAM_END)` 推 chunk + end；D.4 决定 SseEmitterManager 保留为 enterprise transport。chokepoint 接管后用 BroadcastResponseSink (WS) — group-chat 路径要不要继续推 SSE？

| 选项 | 描述 |
|------|------|
| α 双推 — sink 推 WS + AgentReplyTask 同时推 SSE | 兼容 enterprise；显式重复发送 |
| β 仅推 WS — SSE event 由 enterprise 自行从 WS 转发 | 单一 transport；要求 enterprise 改 imSseClient.ts |
| γ 仅推 SSE — BroadcastResponseSink 在 group-chat 路径降级 | 兼容 enterprise；放弃 chokepoint sink claim 的统一性 |

**倾向 α**。D.4 已记录 dual-transport 决策；强行迁移会再次破坏 enterprise。α 是 D.4 决策的延续 — D.3-chokepoint 不引入 transport 迁移耦合。

可后续作为独立的 D.4-frontend-migration 一起处理。

### Q-DC.5 实施分阶段

| 选项 | 描述 |
|------|------|
| α 单 PR | runTurn 加 extraTools 参数 + AgentChatPortImpl handoff 识别 + AgentReplyTask 重构一次到位 |
| **β 三 PR**（建议）| (1) AgentChatPort SPI 加 extraTools；(2) AgentChatPortImpl 加 handoff 识别 + Success.meta 信号；(3) AgentReplyTask 改走 chokepoint |
| γ 实验性开关 | 加 `aurabot.handoff-via-chokepoint=true/false` feature flag；rollback 容易 |

**倾向 β**。每 PR 独立可 review + 回滚；β 的中间 PR (1)+(2) 即使 (3) 暂未上线，也已让 SPI 具备 handoff-aware 能力（后续 enterprise 路径也能受益）。γ feature flag 在 dev-stage 红线下不必要。

---

## 3. 候选 PR 切片（决策定后细化）

按倾向 β + α 全部假设：

| PR | 内容 | 估 LOC | 依赖 |
|----|------|------|------|
| **DC.1** `AgentChatPort.runAgentTurn(ctx, request, sink, extraTools)` 签名扩展 + AgentChatPortImpl 拼合 extraTools 到 ToolProviderRegistry 发现的列表 | ~80 | — |
| **DC.2** AgentChatPortImpl 识别 transfer_to_agent 工具命中 → `TurnOutcome.Success` 带 `meta.handoffTo` / `meta.handoffContext` | ~120 + 测试 | DC.1 |
| **DC.3** TurnRequest 加 `parentTaskPid` 字段；ConversationTurnServiceImpl runTurn 不重复建 task 当 parentTaskPid 非空；AgentReplyTask 改写：每跳调 turnService.runTurn(req with parentTaskPid, BroadcastResponseSink) → 看 outcome.meta 触发 handoff 递归 | ~250 (-200) | DC.1 + DC.2 |

合计：~450 新 / -200 删除（净 +250）。三 PR 紧密推进。

---

## 4. 单测覆盖策略

每个 PR 必须配套：
- DC.1: AgentChatPortImplExtraToolsTest — 验证 extraTools 与 registry 工具合并 / extraTools 名字与 registry 工具名重名时的优先级
- DC.2: AgentChatPortImplHandoffTest — 验证 transfer_to_agent 命中 → Success.meta 含 handoffTo；其他工具命中走原路径不破
- DC.3: AgentReplyTaskChokepointTest — 验证 group-chat 走 turnService.runTurn；handoff 链 task 父子关系仍工作；MAX_HANDOFF_DEPTH=5 仍生效；旧 LlmProvider 直调路径删干净

---

## 5. 风险 + 缓解

| 风险 | 缓解 |
|------|------|
| `extraTools` 与 registry 工具名重名 | DC.1 测试覆盖；约定 extraTools 优先（调用方知道自己注入的工具语义） |
| AgentChatPortImpl Success.meta 字段污染（其他 caller 见到 handoffTo 字段会困惑）| meta 用 `_handoff_to` 前缀 + 文档明确这是 group-chat-only 内部协议 |
| AgentReplyTask 删 LLM 直调路径后失去对 fallback / 错误处理的细粒度控制 | 测试覆盖 LLM 异常 → Failed → AgentReplyTask 看 outcome 变 Failed 时 close task |
| Q-DC.3 α 让 chokepoint 内部跳过 task 创建 — 实现 if-逻辑分叉 | TurnRequest.parentTaskPid 非空时 chokepoint 直接复用而非建新；逻辑集中在一处 |
| 群聊 / aurabot main path 共用 AgentChatPort，extraTools 字段只 group-chat 用 | optional 参数；aurabot main path 传空 List；既有 6 单测断言空时行为不变 |

---

## 6. 不在范围（明确推迟）

- D.4-frontend-migration（imSseClient.ts → WS）— D.4 决策已保留 dual transport
- ACP plan-loop 接管 group-chat agents — 与 D.3-chokepoint 正交，design v3.3 §3.6
- handoff context 持久化（当前是入参字符串；多轮 handoff 没有 history）— 跨 PR 增量

---

## 7. owner 决策汇总（v1 待 lock）

| Q | 内容 | v1 倾向 | 决策（owner 填）|
|---|------|---------|---|
| Q-DC.1 | HandoffToolProvider × Registry 关系 | β SPI 加 extraTools | TBD |
| Q-DC.2 | transfer_to_agent 信号传递 | α Success.meta | TBD |
| Q-DC.3 | task 链与 chokepoint task 整合 | α chokepoint 单 task 模型 | TBD |
| Q-DC.4 | 群聊 transport | α 双推（继承 D.4 决策）| TBD |
| Q-DC.5 | 实施分阶段 | β 三 PR | TBD |

**长期演进视角检查**：
- 6 个月后回头看，β 选项把 SPI 加参数：会后悔吗？— 不会，optional 参数是低成本扩展；γ "channel-driven AgentChatPortImpl 内部识别"反而把跨模块依赖反向，长期维护成本更高
- 若 owner 选 α 单 PR — 单 PR ~450 LOC + 删 200 — 接近不可 review 上限；β 三 PR 每个 ~100-250 LOC 更安全
- 群聊 transport 维持双推会让 D.4-frontend-migration 仍是必要的下一步 — 但 D.3-chokepoint 不应捆绑 enterprise 前端依赖

**反方 steel-man**：
- "Q-DC.1 α 注册为 ToolProvider 更抽象统一" — 真实但 ToolDiscoveryContext 当前没有 conversationId，扩展 ctx 字段比扩 SPI 参数 blast radius 更大
- "Q-DC.2 β 新加 outcome 变体语义更清晰" — 但 4 个现有 outcome 接收方（finalizeTurn dispatch / persist / event / metrics）每个都要新加 case，污染面太大

---

## 8. 实施时序（决策 lock 后填）

待 v2 (owner-decision-locked 版) 撰写。每 PR 模式照 Phase D 节奏：独立 commit + push + main fast-forward。

预估总时长：DC.1 (0.5天) + DC.2 (1天) + DC.3 (1.5天) ≈ 3 天工作量。

---

## CHANGELOG

- 2026-04-30 v1 初稿；5 个决策点 surfacing；候选 PR 切片 DC.1-DC.3
