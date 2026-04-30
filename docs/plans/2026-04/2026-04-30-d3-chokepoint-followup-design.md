# D.3-chokepoint Follow-up — HandoffToolProvider × AgentChatPort SPI Integration

> **Status**: v3 (2026-04-30) — DC.1 + DC.2 landed; DC.3 + DC.4 deferred pending sub-design after implementation-time discovery that group-chat prompt/history/tool semantics need substantial AgentReplyContext absorption work not anticipated in v2 §3. See §9 "DC.3 实施期发现" for details.
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

## 7. owner 决策汇总（v2 已 lock 2026-04-30）

| Q | 内容 | **决策** | 关键判断 |
|---|------|---------|---|
| Q-DC.1 | HandoffToolProvider × Registry 关系 | **β** SPI 加 extraTools | 会话 scope 工具不应污染 tenant-scoped registry；optional 参数最小 blast radius |
| Q-DC.2 | transfer_to_agent 信号传递 | **α** Success.meta(_handoff_to/_handoff_context) | 新加 outcome 变体污染 4 个 caller；γ 让 SPI impl 承担业务流程协调；α 改动最小 |
| Q-DC.3 | task 链与 chokepoint task 整合 | **α** chokepoint 单 task 模型 + parentTaskPid 传参 | β 嵌套 task 让 mission view 复杂；γ 让 chokepoint 装饰化（task 路径分叉） |
| Q-DC.4 | 群聊 transport | **β** 一次性删 SSE + enterprise 前端迁 WS（**v1 → v2 决策修正，前因后果见 §7.1**） | 我们 OSS + enterprise 同 owner / 同 workspace / 同 dev-stage — 不符合 dual-publish 的"控制不了消费者发版"前置条件；强行 dual-publish 引入伪复杂度 + sunset 维护负担 |
| Q-DC.5 | 实施分阶段 | **β** 4 PR（DC.1 SPI / DC.2 handoff / DC.3 routing / DC.4 跨仓 transport 删除）| 单 PR ~600 LOC 不可 review；feature flag 在 dev-stage 红线下不必要 |

### 7.1 前因后果 — 为什么 Q-DC.4 从 α 改到 β

**v1 决策（错的）**：α 双推 — 继承 D.4 dual-transport 决策。理由：enterprise `ent-im-chat` 主动订阅 `/api/im/stream`，单方面 OSS 侧迁移会破坏 enterprise；保持双推等 enterprise 前端独立迁移完后再 cleanup。

**v1 的判断盲点**：把 enterprise 当成"独立第三方消费者"对待。这个框架来自业界 dual-publish 的标准前置条件：

> Dual-publish 模式之所以是 transitional 标准做法，是因为它解决一个特定问题：**后端不能同步推动消费者客户端发版**（典型场景：Stripe / Slack 第三方 API 用户 / 移动 App 6 个月发版周期 / 浏览器扩展用户安装周期）。

**实际情况打破了这个前提**：

| 维度 | dual-publish 前提 | 我们的实际情况 |
|---|---|---|
| 消费者主体 | 外部 / 多组织 | 同一个 owner / 同一个工作目录 |
| 代码物理位置 | 跨网络 / 跨公司 | `/Users/ghj/work/auraboot/auraboot-enterprise/` ← 就在隔壁 |
| 发版协调 | 不可控 | 一次 session 可以同时改 |
| 稳定性约束 | prod 用户在线 | dev-stage（AGENTS.md 红线允许 breaking changes） |
| Rollback | 客户端旧版本无法立即修 | git revert 一次搞定两仓 |

**修正后的决策（β）**：跨仓 one-shot migration —— DC.4 在同一个 PR sequence 里同时改 OSS 后端（删 SseEmitterManager）+ enterprise 前端（imSseClient.ts → WS 订阅）。同 session 完成 + 启动验证。

**长期净比较**：

| | α dual-publish + sunset | β 一次性到位 |
|---|---|---|
| OSS 改动量 | 保留 SseEmitterManager + 双推显式调用 | 删 ~150 LOC SseEmitterManager 路径 |
| 企业改动量 | 不改 | imSseClient.ts → WS（~80 LOC 改写）|
| 长期债务 | sunset metric / dashboard / 跟踪删除 PR | 0 |
| 6 个月后 | 看运气：metric 跌零顺利下线 OR 永久双轨 | 干净的单 transport |
| 反模式风险 | 高（dual-publish 没人记得 sunset → 永久双轨）| 0 |

**判断信号**（AGENTS.md "长期演进视角"红线）：α 的核心理由是"避免单方面迁移破坏 enterprise"——但 enterprise 不是单方面，是同 team 同 session 可同步改。这种"避免 X 风险"作为唯一推理由的方案通常方向反了。

**D.4 commit message 关于 dual-transport 的决策（`06b77d87`）**仍然 valid 作为"双 transport 当时的状态记录"，但下一步行动应是迁移到单 transport，不是把 dual-transport 永久化。DC.4 PR 同时把 D.4 javadoc 中"do not delete without coordinated frontend migration"的"warning"翻成"resolved by DC.4 in this PR sequence"。

**长期演进视角检查**：

**长期演进视角检查**：
- 6 个月后回头看，β 选项把 SPI 加参数：会后悔吗？— 不会，optional 参数是低成本扩展；γ "channel-driven AgentChatPortImpl 内部识别"反而把跨模块依赖反向，长期维护成本更高
- 若 owner 选 α 单 PR — 单 PR ~450 LOC + 删 200 — 接近不可 review 上限；β 三 PR 每个 ~100-250 LOC 更安全
- 群聊 transport 维持双推会让 D.4-frontend-migration 仍是必要的下一步 — 但 D.3-chokepoint 不应捆绑 enterprise 前端依赖

**反方 steel-man**：
- "Q-DC.1 α 注册为 ToolProvider 更抽象统一" — 真实但 ToolDiscoveryContext 当前没有 conversationId，扩展 ctx 字段比扩 SPI 参数 blast radius 更大
- "Q-DC.2 β 新加 outcome 变体语义更清晰" — 但 4 个现有 outcome 接收方（finalizeTurn dispatch / persist / event / metrics）每个都要新加 case，污染面太大

---

## 8. 实施时序（owner 决策已锁，4 PR 紧密推进）

| PR | 决策来源 | 验收红线 |
|----|---------|---------|
| **DC.1** `AgentChatPort.runAgentTurn(ctx, request, sink, extraTools)` 签名扩展 + AgentChatPortImpl 把 extraTools 与 ToolProviderRegistry 发现的列表合并 | Q-DC.1=β | 单测：extraTools null/empty 行为与既有完全一致；extraTools 与 registry 工具名重名时 extraTools 优先（带 warn 日志）；既有 AgentChatPortImpl 6/6 测试不破 |
| **DC.2** AgentChatPortImpl 识别 transfer_to_agent 工具命中 → `TurnOutcome.Success(meta._handoff_to, _handoff_context)`；不执行该工具 | Q-DC.2=α | 单测：handoff 命中 → Success.meta 含 _handoff_to/_handoff_context；非 handoff 工具走原路径不破；aurabot main path（无 handoff tool）行为不变 |
| **DC.3** `TurnRequest.parentTaskPid` 字段；ConversationTurnServiceImpl 在 parentTaskPid 非空时不重复建 task；AgentReplyTask 改写：每跳调 `turnService.runTurn(req with parentTaskPid, sink, extraTools=[handoffTool])`；看 outcome.meta._handoff_to 触发 handoff 递归 | Q-DC.3=α + Q-DC.5=β step3 | 集成测试：群聊 @mention → runTurn 被调；handoff 链每跳建 ab_agent_task 父子；MAX_HANDOFF_DEPTH=5 仍生效；C.2 memory L1 writeback fire；旧 LLM 直调路径删除 -200 LOC。**保留** SseEmitterManager.sendToUsers 调用直至 DC.4 |
| **DC.4** 跨仓 one-shot transport 删除：OSS 删 SseEmitterManager + ImSseController + SseEventType + agentchat.sse 整个 package + AgentReplyTask 内残留 SSE 调用；enterprise `imSseClient.ts` → 改用现有 ImWebSocket 连接订阅 TYPING_INDICATOR / MESSAGE 帧 | Q-DC.4=β + Q-DC.5=β step4 | 同 session 验证：reset-and-init.sh + 浏览器跑通群聊 @mention 流式输出（含 typing dots → message render）+ enterprise ent-im-chat 插件页面验证；OSS 后端 -150 LOC，enterprise 前端 ~80 LOC 改写 |

每 PR 独立 commit + push + main fast-forward 模式。DC.4 涉及 enterprise 仓 commit，会分别 push 两仓。

预估总时长：DC.1 (0.5天) + DC.2 (1天) + DC.3 (1.5天) + DC.4 (1天，含 enterprise 前端 + 同 session 验证) ≈ 4 天工作量。

---

## 9. DC.3 实施期发现 — AgentReplyContext absorption is a sub-design

DC.3 (route `AgentReplyTask` through `turnService.runTurn`) revealed a coupling that v2 §3 LOC estimate didn't account for. **DC.1 + DC.2 are landed (`131f8890` + `d7f9175b`); DC.3 is deferred pending the sub-design work below.**

### 9.1 The coupling

| 维度 | `AgentReplyTask` 现状 | `AgentChatPortImpl.runAgentTurn` |
|---|---|---|
| 系统提示词 | `AgentReplyContext.buildSystemPrompt(agentDto, conversationId, tenantId)` — 群聊语境（soul profile + 多 agent 描述 + 群聊角色定位） | `buildSystemPrompt(agentDef)` — 1:1 chat 模型 |
| 历史消息 | `replyContext.buildHistory(conversationId, tenantId, contextWindow)` — 从 `ab_im_message` 拉群聊全员 history（含其他 agent 回复） | 来自 `ChatRequest.history`（前端传的 1:1 history） |
| 工具列表 | `agent.getTools()` 直挂的工具 | `ToolProviderRegistry.discoverAll(ctx)` — tenant-scoped 发现 |

直接把 AgentReplyTask 切到 `agentChatPort.runAgentTurn(ctx, ChatRequest, sink, extraTools)` 会丢失群聊语境（agent 不知道自己在群聊里，失去多 agent 协作能力）。

### 9.2 Sub-design 方向选项

- **选项 A**: `AgentChatPort` SPI 加 optional 字段（`customSystemPrompt` / `historyOverride` / `pinnedToolDefs`）让调用方注入预构建上下文；AgentChatPortImpl 看到非空就用调用方版本，否则走默认。
  - Pro: SPI 演进自然；调用方知道群聊语境
  - Con: SPI surface 持续膨胀

- **选项 B**: 把 `AgentReplyContext` 的群聊语境组装搬进 `AgentChatPortImpl` —— `AgentChatPortImpl` 检测 `ChatRequest.channel == "im_group"` 时切换到群聊提示词 / 群聊 history 来源 / agent.getTools() 工具表
  - Pro: chokepoint 真正成为单一入口
  - Con: AgentChatPortImpl 复杂度上升；channel-driven 分支多

- **选项 C**: 引入 `GroupChatAgentChatPort` 扩展 SPI（`AgentChatPort` 子接口）专门处理群聊；普通 `AgentChatPort` 不变
  - Pro: 关注点分离
  - Con: SPI 分裂；ConversationTurnService dispatch 需识别用哪个 port

### 9.3 已落地的 DC.1 + DC.2 价值

即便 DC.3 暂缓，DC.1 + DC.2 不是空跑：

- **DC.1 extraTools** SPI 让任何 named-agent 调用方可以注入会话 scope 工具（不仅仅是 handoff —— 未来其他场景同样可用）
- **DC.2 transfer_to_agent → Success.meta** 让 handoff 信号成为 chokepoint 标准协议；任何走 `AgentChatPort.runAgentTurn` 的调用方都能识别 handoff 而不是黑盒到工具执行

DC.3-sub-design 启动前，AgentReplyTask 仍是直接调 `provider.chat`（D.3 task chain 已落，handoff via 自定义 tool execute 路径仍工作）。

### 9.4 DC.4 状态

DC.4（删 OSS SseEmitterManager + 迁 enterprise imSseClient.ts）依赖 DC.3 完成（AgentReplyTask 不再发 STREAM_CHUNK / STREAM_END SSE 事件）。所以 DC.4 也随 DC.3 deferred。

D.4 文档化的 dual-transport 决策（commit `06b77d87`）仍然 valid 作为现状记录。一次性到位的 cross-repo 迁移待 DC.3-sub-design 完成后才能跟随。

---

## CHANGELOG

- 2026-04-30 v3 DC.1 + DC.2 landed (`131f8890` + `d7f9175b`); DC.3 + DC.4 deferred pending §9 sub-design — AgentReplyContext 群聊语境 absorption (3 选项) 需要单独设计 lock
- 2026-04-30 v2 owner 决策锁定 5 项；§7.1 新增"前因后果"节解释 Q-DC.4 从 α (dual-publish) 修正到 β (one-shot)；§8 锁定 4 PR 实施时序（v1 是 3 PR，DC.4 跨仓 transport 删除新加为独立 step）
- 2026-04-30 v1 初稿；5 个决策点 surfacing；候选 PR 切片 DC.1-DC.3
