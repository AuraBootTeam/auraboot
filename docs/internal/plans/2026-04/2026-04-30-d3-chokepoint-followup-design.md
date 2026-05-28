# D.3-chokepoint Follow-up — HandoffToolProvider × AgentChatPort SPI Integration

> **Status**: v6 closure (2026-05-07; minor hash correction 2026-05-08) — DC.1–DC.4 + GAP-293/295/296/311 follow-up chain all landed (OSS commits `131f8890 → d7f9175b → 780fc027 → f4b9dede → c4e3e5a4 → d5093130 → 372b9272 → 3c53d327` + enterprise `e922c3789`; v6-doc commit `e5adbc90`). §11 contains the commit-hash table, long-term-evolution actual-vs-claim audit, v5 → v6 deviation log, final test snapshot, and deferred-item routing. **No new decisions in v6**; this version is the design's archival closure.
> **v5 (2026-04-30)** — owner reviewed v4 §10 from long-term-evolution lens; locked **A'**: caller-owned context via **server-only `AgentTurnOverrides`**, NOT public `ChatRequest` fields. v5 §10.7 captures the 5 must-fix issues that v4 missed (security boundary on ChatRequest, named-agent outbound identity drift, task lifecycle protocol, handoff schema field-name bug, DC.4 underestimation). v5 §10.8 refines the PR sequence accordingly.
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

## 10. DC.3-redesign — 三个 sub-design 选项详细对比（2026-04-30 v4）

> 此节给 owner review 用。每个选项给出：(a) 代码形状预览 (b) 长期演进 6/12 个月预测 (c) reverse 反方 steel-man (d) 6 个月后悔检查。最后给出 v4 倾向 + 反方理由。

### 10.1 起点：当前两条路径对应的代码层次

```
AgentReplyTask (~327 LOC) — 群聊路径
  ├─ Step 1-2: 加载 AgentDefinition + TYPING SSE
  ├─ Step 3:   buildHistory(conv, tenant, ctxWin)        ← 群聊 ab_im_message 跨 agent tape
  ├─ Step 4:   buildSystemPrompt(agentDto, conv, tenant) ← 多 agent 群聊语境
  ├─ Step 5:   buildTools(conv, tenant, currentAgentId)  ← agent.getTools() + handoff tool
  ├─ Step 6:   resolveProvider + buildLlmChatRequest
  ├─ Step 7:   provider.chat(...) ← 直调 LLM
  ├─ Step 8:   handle response (end_turn / tool_use / handoff recursion)
  └─ Step 9:   STREAM_CHUNK / STREAM_END SSE + saveAgentMessage

AgentChatPortImpl.runAgentTurn (~600 LOC) — 1:1 chat 路径
  ├─ Step 1:   加载 AgentDefinition (by agentCode)
  ├─ Step 2:   resolveProvider + config
  ├─ Step 3:   buildSystemPrompt(agentDef)               ← 1:1 chat 模型
  ├─ Step 4:   discoverToolDefinitions (ToolProviderRegistry) ← tenant scope
  │           + DC.1 mergeExtraTools(extraTools)
  ├─ Step 5:   restoreOrBuildMessages (ChatRequest.history + 持久 tape)
  ├─ Step 6:   多轮 LLM tool loop:
  │            - provider.chat
  │            - end_turn → streamFinalResponse → Success
  │            - tool_use:
  │              - DC.2 transfer_to_agent → buildHandoffOutcome → Success.meta._handoff_to
  │              - requiresConfirmation → sink.onConfirmRequired, PendingTool 存
  │              - read-only → 执行 + tool_result 喂回循环
  ├─ Step 7:   max rounds / LLM 异常 → Failed
  └─ Step 8:   传入的 sink 接收 onTextChunk / onDone / onError / onToolResult / ...
```

**核心观察**：步骤 6（LLM tool loop）80%+ 是相同的；差异集中在步骤 1-5（**context 准备**）。

### 10.2 选项 A — `ChatRequest` 加 optional 字段（caller 预构建 context）

#### 代码形状

```java
// ChatRequest 加 3 个 optional 字段
public class ChatRequest {
    // existing: agentCode, message, history, sessionId, pageContext, options, ...
    + private String customSystemPrompt;       // 非 null 时 AgentChatPortImpl 用此
    + private List<LlmChatRequest.Message> historyOverride;  // 非 null 时用此
    + private List<ToolDefinition> pinnedToolDefs;  // 非 null 时用此（替代 ToolProviderRegistry 发现）
}

// AgentReplyTask 重写
ChatRequest req = new ChatRequest();
req.setAgentCode(agent.getAgentCode());
req.setMessage(triggerContent);
req.setSessionId("im-conv-" + conversationId);
req.setCustomSystemPrompt(replyContext.buildSystemPrompt(agentDto, conversationId, tenantId));
req.setHistoryOverride(replyContext.buildHistory(conversationId, tenantId, contextWindow));
req.setPinnedToolDefs(buildAgentAttachedToolDefs(agent));

ResponseSink sink = new GroupChatSseBridgingSink(sseEmitterManager, humanMemberIds, ...);
List<ToolDefinition> extraTools = List.of(handoffToolDef);
TurnOutcome outcome = agentChatPort.runAgentTurn(ctx, req, sink, extraTools);

// Handle outcome.meta._handoff_to → 关 task / 开 child / recurse
```

`AgentChatPortImpl.runAgentTurn` 内部三个分支：
```java
String systemPrompt = request.getCustomSystemPrompt() != null
        ? request.getCustomSystemPrompt()
        : buildSystemPrompt(agentDef);  // existing default
List<LlmChatRequest.Message> messages = request.getHistoryOverride() != null
        ? request.getHistoryOverride()
        : restoreOrBuildMessages(...);  // existing default
List<ToolDefinition> toolDefs = request.getPinnedToolDefs() != null
        ? request.getPinnedToolDefs()
        : discoverToolDefinitions(...);  // existing default
toolDefs = mergeExtraTools(toolDefs, extraTools);  // DC.1 unchanged
```

#### Pros / Cons

**Pros**:
- AgentChatPortImpl 单 SPI 单 entry，Channel 知识在 AgentReplyTask 一处
- 现有 1:1 chat / aurabot 主路径**零行为变化**（不传 optional 字段就走默认）
- 步骤 6（LLM tool loop）100% 单实现共享 —— bug 修一处，所有 channel 受益
- 未来新 channel（webhook / BPM / 调度）模式相同：caller 预构建 context，feed through optional fields
- ChatRequest 字段增长可观察：metric `agentchatport_caller_prebuild_count{field=...}` 高 → 该字段是真实需求；低 → 该字段死掉，可删

**Cons**:
- ChatRequest 字段数量 8 → 11，长期可能继续涨
- "if non-null, use this; else build default" 分支逻辑在 AgentChatPortImpl 内
- "chokepoint claim" 严格说不是"all context built here"而是"all LLM calls go through here"，前者更弱

#### 6/12 个月演进预测
- 6 个月：除 AgentReplyTask 外，可能 1-2 个新 caller 用 optional 字段（webhook trigger / scheduled agent）；ChatRequest 字段增长缓慢
- 12 个月：optional 字段稳定在 3-5 个；metric 数据告诉 owner 哪些字段真用、哪些死掉
- **6 个月后悔检查**："早知道当时该选 B 把 group-chat 吸进 AgentChatPortImpl"？— 不会后悔；distributed context-build 是 SRP 自然结果，每个 channel 拥有自己的 context source

#### Steel-man 反方
> "Optional 字段创造模糊 API contract — 调用方不知道哪些字段组合产生正确行为。Chokepoint claim 弱化。"

回应：optional + "if present takes precedence" 是 Java 生态成熟模式（Spring 大量使用）。chokepoint claim 的核心是"all LLM calls use the same loop and side effects"，A 满足。

---

### 10.3 选项 B — `AgentChatPortImpl` 吸收 `AgentReplyContext`（channel-driven 内部分支）

#### 代码形状

```java
// TurnContext 加 channel 字段
public record TurnContext(..., String channel, ...);  // "im_group" / "aurabot" / "webhook" / ...

// AgentChatPortImpl 内部 channel 分支
public TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink,
                                  List<ToolDefinition> extraTools) {
    // ... 加载 agentDef / 解析 provider 不变 ...

    boolean isGroupChat = "im_group".equals(ctx.channel());

    String systemPrompt = isGroupChat
            ? buildGroupChatSystemPrompt(agentDef, ctx.conversationId(), ctx.tenantId())
            : buildSystemPrompt(agentDef);

    List<LlmChatRequest.Message> messages = isGroupChat
            ? loadGroupChatHistory(ctx.conversationId(), ctx.tenantId(), DEFAULT_CONTEXT_WINDOW)
            : restoreOrBuildMessages(request.getSessionId(), request.getHistory(), request.getMessage());

    List<ToolDefinition> toolDefs = isGroupChat
            ? buildAgentAttachedToolDefs(agentDef)
            : discoverToolDefinitions(...);
    toolDefs = mergeExtraTools(toolDefs, extraTools);

    // ... step 6 LLM tool loop 不变 ...
}
```

需要：
- `TurnContext.channel` 新字段（5 个 TurnContext 构造点全部加）
- `AgentChatPortImpl` 注入 `AgentReplyContext` + `GroupChatMessagePort`（来自 `agentchat` 包）
- AgentReplyTask 缩成 ~50 LOC：dispatch + handoff 递归

#### Pros / Cons

**Pros**:
- AgentChatPortImpl 是真正的 single LLM entry — chokepoint claim 最强
- AgentReplyTask 缩成薄 dispatcher，大量代码消灭
- ChatRequest 字段不变

**Cons**:
- **跨包反向依赖**：`agent.service.AgentChatPortImpl`（核心 agent 模块）需要依赖 `agentchat.spi.GroupChatMessagePort` + `agentchat.reply.AgentReplyContext`（应用层模块）。当前依赖方向 `agentchat → agent`，反过来意味着核心模块 import 应用模块 → 模块边界破坏
- AgentChatPortImpl 复杂度上升 ~+200 LOC；每新增 channel 加一个分支
- "isGroupChat" 字符串比较散落在多处；switch case 多 channel 时需 strategy 模式重构 → 又走向 A
- TurnContext.channel 字段全链路传递（5+ 构造点 + Persistence + EventEmitter 路径都要意识到 channel 语义）

#### 6/12 个月演进预测
- 6 个月：`im_group` 分支落地；新加 `webhook` / `bpm` channel 又加分支
- 12 个月：AgentChatPortImpl 内部 5+ channel 分支；isXxx 比较或 strategy map 散落多处
- **6 个月后悔检查**："早知道该用 A 把 channel 知识留在 caller"？— 后悔可能性高。Channel-driven god class 是已知反模式（OOP 历史教训：每加一个分支就考虑是不是该多态了）

#### Steel-man 反方
> "True chokepoint 必须 single canonical impl。Channel-driven 分支 IS 正确做法 —— 与 C.3c 的 bucket-driven 分派同源。"

回应：bucket dispatch 在边界上（chokepoint → ACP runtime vs chokepoint → chat impl），那两条路径**根本不同**（一条 task-based plan 循环，一条 LLM tool loop）。在 AgentChatPortImpl 内部 step 6 的 LLM tool loop 99% 相同，只有 step 1-5 context 不同 —— 这是 SRP 应该拆开的地方而不是统一的地方。

---

### 10.4 选项 C — `GroupChatAgentChatPort` 子接口（两个并行 impl）

#### 代码形状

```java
interface AgentChatPort {  // 基础（1:1 chat）
    TurnOutcome runAgentTurn(...);
}

interface GroupChatAgentChatPort extends AgentChatPort {
    // 群聊专用 hook（如果需要）
    TurnOutcome runAgentTurnWithGroupContext(...);
}

class AgentChatPortImpl implements AgentChatPort { ... }       // 1:1 chat (~600 LOC)
class GroupChatAgentChatPortImpl implements GroupChatAgentChatPort {
    ...                                                         // 群聊 (~400 LOC, 大量复制 1:1 的 LLM tool loop)
}

// ConversationTurnService 按 channel 分派
if ("im_group".equals(channel)) {
    groupChatPort.runAgentTurnWithGroupContext(...);
} else {
    agentChatPort.runAgentTurn(...);
}
```

#### Pros / Cons

**Pros**:
- 关注点清晰分离：1:1 chat 一个 impl，群聊一个 impl
- 各自可独立演进，不互相干扰

**Cons**:
- **代码重复**：步骤 6（LLM tool loop）80%+ 重复一遍。tool execution / confirmation / handoff signal / message persistence 全部各做一份
- DC.1 + DC.2 在两个 impl 各实现一次 → 漂移风险（已经有先例：D.4 SseEmitterManager 与 ImMessageBroadcaster 平行 transport，最终是技术债）
- ConversationTurnService dispatch 多一个 channel 分支（基本就是 B 选项的同样问题往外移了一层）
- "chokepoint" 概念分裂 —— 哪个才是 named-agent 的 canonical 入口？

#### 6/12 个月演进预测
- 6 个月：两个 impl 同步；新 bug 修在哪个看作者哪个 PR
- 12 个月：两个 impl 漂移；1:1 修了 retry 逻辑，群聊没修 → 群聊 retry 行为悄悄不一致；用户报 bug 时定位困难
- **6 个月后悔检查**："早知道该用 A 让两条路径共享 LLM tool loop"？— 后悔可能性最高。两个并行 impl 漂移是经典反模式

#### Steel-man 反方
> "两个 impl 共享基类是标准 Java 实践。"

回应：当 80%+ 代码共享时，基类成为事实上的 god class（回到 B 选项的问题）。真正分离需要 <50% 共享，这里不适用。

---

### 10.5 v4 倾向 + 反方理由

| | 选项 A | 选项 B | 选项 C |
|---|---|---|---|
| chokepoint claim | "all LLM calls 走同一 loop"（语义稍弱但够用） | "all named-agent context 在一处构建"（语义最强） | "named-agent 有 2 个 impl，按 channel 选"（claim 削弱） |
| LLM tool loop 共享 | ✓ 100% | ✓ 100% | ✗ 复制 80% |
| Channel 知识位置 | caller 拥有（自然） | AgentChatPortImpl（god class 风险） | 各 impl 各自含 |
| 跨模块依赖方向 | 不变（agent ← agentchat 单向） | **反向**（agent → agentchat） | 不变 |
| 新增 channel 成本 | 写一个 caller，注入 optional fields | 加分支或 strategy entry | 写一个新 impl + 更新 dispatcher |
| 6 个月后悔风险 | 低 | 中（god class） | 高（双 impl 漂移） |
| 反模式倾向 | 接近"distributed factory"（已知模式） | 接近"channel god class"（已知反模式） | 接近"parallel implementations"（最强反模式） |

**v4 倾向**：**选项 A**（带 metric 监督）。

理由：
1. AGENTS.md "长期演进视角" 红线："禁止推荐让接口契约 / chokepoint / 抽象 claim **装饰化**"。A 让 chokepoint claim 真实 — `runAgentTurn` 是 100% LLM-loop 唯一入口；只是 context 准备权交给 caller。这是 SRP 的自然结果。
2. B 的 god class 模式 + 跨模块反向依赖在 6-12 个月后是已知反模式（chokepoint refactor 自己就在修类似的反模式 —— 谁会想再加一个）。
3. C 是最强反模式（双 impl 漂移）。
4. A 字段增长用 metric 自我监督：optional 字段使用率低 → 删；高 → 是真实模式。对应 AGENTS.md 的"sunset metric / observability discipline"。

**A 增强建议**：

```java
// AgentChatPortImpl 加 metric 桩
if (request.getCustomSystemPrompt() != null) {
    Metrics.increment("agentchatport.caller_prebuild", "field", "systemPrompt", "channel", ctx.channel());
}
// 同样为 historyOverride / pinnedToolDefs

// 6 个月后审查 metric。某字段所有 channel 都使用 → 升级为非 optional；某字段只有 1 channel 使用 → 设计决策记录"该字段是 channel-specific extension"
```

**反方 steel-man**（公平起见）：

> "选项 A 把 group-chat 知识放 AgentReplyTask，6 个月后 GroupChatAgentRouter / 邮件回复 agent / 群聊任意场景都各自构建 group-chat-specific context — 等于代码复制只是分散到多个 caller。"

回应：这是真问题。缓解：把 `AgentReplyContext.buildSystemPrompt + buildHistory` 升级为公开的群聊语境构建器（可注入到任何 caller），名字可改为 `GroupChatTurnContextAssembler`。它是个工具类，不是 SPI；所有群聊 caller 共用。这把 A 的"context 由 caller 构建"细化成"context 由 caller 调用共享 assembler 构建"，不再是 caller 各自重复。

> "选项 B 真的会变 god class 吗？也许只有 group-chat 一个分支永远存在。"

回应：可能。但关键是 SPI 决策不应押注"未来不会有新 channel"。A 对未来 channel 是开放的（加 optional 字段或不加都可），B 对未来 channel 是封闭的（必须改 AgentChatPortImpl）。

---

### 10.6 落地 PR 切片（owner 锁 A 后）

| PR | 内容 | 估 LOC | 依赖 |
|----|------|------|------|
| **DC.3a** `ChatRequest` 加 3 optional 字段（customSystemPrompt / historyOverride / pinnedToolDefs）+ AgentChatPortImpl 在 step 1-5 检测并使用 | ~120 + 3 单测 | DC.1 + DC.2 |
| **DC.3b** 抽 `GroupChatTurnContextAssembler`（公开工具类）封装现有 `AgentReplyContext.buildSystemPrompt + buildHistory`；目标多 caller 共享 | ~80 (-50)，纯重组 | — |
| **DC.3c** AgentReplyTask 重写：注入 AgentChatPort + GroupChatTurnContextAssembler；构建预制 ChatRequest 调 runAgentTurn；handoff 通过 outcome.meta._handoff_to | ~250 (-200) | DC.3a + DC.3b |
| **DC.3d** Metric 桩 (`agentchatport.caller_prebuild`) + 文档化 sunset 标准（"6 个月后所有 channel 都不使用某字段 → 删") | ~30 | DC.3a |
| **DC.4** （unchanged from v2）跨仓 OSS 删 SseEmitterManager + enterprise imSseClient.ts → WS | ~150 (-150) + ~80 enterprise | DC.3c |

合计：~630 LOC 新（含 metric） / -400 LOC 删除（净 +230）。每 PR 独立可 review。

预估时长：DC.3a (1天) + DC.3b (0.5天) + DC.3c (1.5天) + DC.3d (0.5天) + DC.4 (1天) ≈ 4.5 天。

---

## 10.7 v5 — A' 锁定（owner review of v4）+ 5 项 must-fix

owner 接受 v4 长期方向（A vs B vs C 反模式判断），但拒绝 v4 落地形状（"ChatRequest 加 optional 字段"）。锁定为 **A'**：caller-owned context 但通过 **server-only 内部对象** 传入，不暴露给客户端。

### A vs A' 关键区别

| | v4 选项 A | v5 选项 A' |
|---|---|---|
| context 注入面 | `ChatRequest` 加 `customSystemPrompt` / `historyOverride` / `pinnedToolDefs` 字段 | 新增 server-only `AgentTurnOverrides` 对象，仅 server 内部 caller 构造 |
| 接受路径 | `/chat/stream` 的 `@RequestBody ChatRequest` 反序列化 | 不反序列化；HTTP 控制器永远传 `null` overrides |
| 客户端能否注入 | **能**（即便不文档化，攻击者发现字段就能注入 system prompt / 工具定义） | **不能**（DTO 边界外） |
| AgentChatPort SPI 扩展 | `runAgentTurn(ctx, request, sink, extraTools)` 留 4 个参数 | `runAgentTurn(ctx, request, sink, extraTools, overrides)` 加第 5 个参数（或 builder/options 包对象） |

A' 在长期演进上与 A 完全等价（chokepoint claim 真实 / SRP / 跨模块依赖方向不变 / metric 自我监督），但**安全边界对**。v4 的 §10.5 倾向 A 的论证全部对 A' 同样成立。

### 5 项 must-fix（v4 漏掉的）

#### Fix 1 — `ChatRequest` 是公开 DTO，安全边界禁止扩展

**问题**：[ChatRequest.java:14](../../platform/src/main/java/com/auraboot/framework/aurabot/dto/ChatRequest.java) 直接被 `/api/ai/aurabot/chat/stream` 用 `@RequestBody` 接收。v4 提议加 `customSystemPrompt` / `pinnedToolDefs` → 客户端可注入系统提示词 + 工具定义 → prompt injection / tool定义伪造 / 越权访问数据。这不是字段膨胀问题，是**安全边界**问题。

**v5 修正**：
- 不动 `ChatRequest`
- 新增 server-only 对象（候选名 `AgentTurnOverrides` 或 `PreparedAgentTurnContext`）：
  ```java
  public final class AgentTurnOverrides {
      private final String systemPromptOverride;
      private final List<LlmChatRequest.Message> messagesOverride;
      private final List<ToolDefinition> toolDefsOverride;
      private final List<ToolDefinition> extraTools;          // 替代 DC.1 的额外参数（一并合入此处）
      private final Boolean persistSessionTape;               // 可选：群聊不需要持久 tape，aurabot 需要
      // builder + 不可变 + 包内/明确 visibility
  }
  ```
- `AgentChatPort.runAgentTurn` 签名（最终版）：
  ```java
  TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink,
                           AgentTurnOverrides overrides);  // overrides=null 时全走默认路径
  ```
  （DC.1 的 `extraTools` 参数被 overrides 吸收，统一一处）
- HTTP 控制器路径：`AuraBotController` 调 `agentChatPort.runAgentTurn(ctx, req, sink, null)` —— overrides 永远 null。**关键测试**：写一个 controller 集成测试，让客户端在 ChatRequest 里塞 systemPrompt 字段（如果 attacker 知道有这个 server-only 字段名），验证 server 不识别、不路由进 overrides。

#### Fix 2 — `persistOutbound` 写 outbound 行时硬编码 aurabot agent

**问题**：[AuraBotTurnPersistence.java:153](../../platform/src/main/java/com/auraboot/framework/conversation/AuraBotTurnPersistence.java) 用 `agentResolver.resolve(tenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE)` 解析 agentId。群聊 named-agent (Alpha/Beta) 切进 `runTurn` 后，所有 outbound `ab_im_message` row 会落成 `sender_id=aurabot_agent_id`，而不是 Alpha/Beta 的 id。Phase D Q-D.3=α 历史行 backfill 用 `'system'+0 → aurabot_agent_id` 假设 aurabot 是唯一 agent —— 群聊路径切入后这个假设破裂。

**v5 修正**：
- `TurnContext` 加 `agentCode` 字段（`String`，非 `Long agentId` 因为 AuraBotAgentResolver 已经做 lazy-seed by code）
- `ConversationTurnServiceImpl.beginTurn` 把 `request.agentCode()` 落到 `TurnContext.agentCode`（aurabot 路径填 `"aurabot"`，named-agent 路径填具体 code）
- `AuraBotTurnPersistence.writeAgentRow` 改为：
  ```java
  String resolveAgentCode = ctx.agentCode() != null ? ctx.agentCode() : AuraBotAgentResolver.DEFAULT_AGENT_CODE;
  long agentId = agentResolver.resolve(ctx.tenantId(), resolveAgentCode);
  ```
- 5 个 `new TurnContext(...)` 构造点全部加新字段（与 D.1 加 `inboundMessageId` 同模式）
- 集成测试：群聊场景跑通 → 验证 `ab_im_message.sender_id == agent_alpha.id`

**为什么是 ctx.agentCode 不是 overrides.agentCode**：agentCode 是 turn lifecycle 全程必备身份字段（persistInbound / persistOutbound / metrics / event 都可能要看），属于 TurnContext。overrides 是"可选的 context 替换"语义。

#### Fix 3 — `ab_agent_task` 单 task 模型缺协议闭环

**问题**：[ConversationTurnServiceImpl.java:417 dispatchToNamedAgent](../../platform/src/main/java/com/auraboot/framework/conversation/ConversationTurnServiceImpl.java) 当前路径 **不创建 task**；[ConversationTurnServiceImpl.java:504 dispatchToAcpRun](../../platform/src/main/java/com/auraboot/framework/conversation/ConversationTurnServiceImpl.java) 才创建。Phase D 设计写"chokepoint 单 task 模型 + parentTaskPid 传参"，但 named-agent 路径根本没建过 task，所以"AgentReplyTask 不再开 task，让 chokepoint 接管"会**丢 task 链**。

**v5 必须明确的协议**：

| 谁创建 task | 谁关闭 task | handoff 时 child 的 parentTaskPid 来源 |
|---|---|---|
| `ConversationTurnServiceImpl.dispatchToNamedAgent` 入口（在调 AgentChatPort 前） | `finalizeTurn` 在 outcome 终态时按 outcome 类型关闭 | runTurn 通过 `TurnOutcome.Success.meta.taskPid` 暴露给 caller，caller (AgentReplyTask) handoff 时把它作为下一次 runTurn 的 `parentTaskPid` 传 |

具体落地：
- `TurnRequest` 加 `parentTaskPid` (caller→runTurn 输入)
- `TurnContext` 加 `taskPid` (runTurn 内部产出，传给 AgentChatPort)
- `dispatchToNamedAgent`：
  ```java
  String taskPid = (request.parentTaskPid() != null)
          ? createChildTaskRow(ctx, request, request.parentTaskPid())
          : createRootTaskRow(ctx, request);
  TurnContext ctxWithTask = ctx.withTaskPid(taskPid);
  TurnOutcome outcome = agentChatPort.runAgentTurn(ctxWithTask, ...);
  ```
- `finalizeTurn`：根据 outcome 类型关闭 task
  - Success / Interrupted → status='completed'
  - Failed → status='failed' + error_message
  - PendingConfirmation → status='in_progress' (留给 resume 后续关)
  - Success with `_handoff_to` → status='completed' + reason='handoff_to:targetCode'
- `runTurn` 在 outcome.meta 里加 `_taskPid`：caller 调 runTurn 后从 outcome 拿到自己 turn 的 taskPid 做 handoff
- AgentReplyTask 不再开/关 task —— D.3 commit 在 AgentReplyTask 内的 task 写入路径全部删

**Phase D D.3 task 写入要 revert**：D.3 (`afbc283e`) 让 AgentReplyTask 写 task，DC.3 改成 chokepoint 写。AgentReplyTask 的 D.3 task 写代码在 DC.3c 删。

#### Fix 4 — handoff schema 字段名不一致（DC.2 实际有 bug）

**问题**：[HandoffToolProvider.java:57](../../platform/src/main/java/com/auraboot/framework/agentchat/handoff/HandoffToolProvider.java) tool input schema 用 `agent_code`；[AgentChatPortImpl.java:676 (DC.2)](../../platform/src/main/java/com/auraboot/framework/agent/service/AgentChatPortImpl.java) 读 `input.get("targetAgentCode")`。**真实 handoff tool 命中时 `_handoff_to` 始终为空** —— DC.2 的测试用 `targetAgentCode` 自欺欺人。

**v5 修正**（在 DC.3d 一并修）：
- `AgentChatPortImpl.buildHandoffOutcome` 改为优先读 `agent_code`，向后兼容读 `targetAgentCode`：
  ```java
  Object target = input.get("agent_code");
  if (target == null) target = input.get("targetAgentCode");  // 兼容老测试 / 未来其他 handoff tool 实现
  ```
- DC.2 已有的 4 测试中 3 个改成断言真实 schema (`agent_code`)；保留 1 个用 `targetAgentCode` 验证向后兼容
- 新增"用真实 `HandoffToolProvider.getToolDefinition` 产出的 schema 喂给 AgentChatPortImpl"端到端测试，避免再次出现 DC.2 类型的 schema 漂移

#### Fix 5 — DC.4 enterprise 前端工作量被严重低估

**问题**：原 v2/v3 设想 enterprise 前端"复用现有 ImWebSocket 连接订阅 TYPING_INDICATOR / MESSAGE 帧"。实际 [imSseClient.ts:17](../../../auraboot-enterprise/web-admin-ext/plugins/ent-im-chat/overlay/app/chat/services/imSseClient.ts) 只有 SSE client，**没有现成 IM WebSocket client** 可订阅。并且 [GroupChatPage.tsx:197](../../../auraboot-enterprise/web-admin-ext/plugins/ent-im-chat/overlay/app/chat/components/group/GroupChatPage.tsx) consumer 期望 SSE event 形如 `data.message`，而 OSS 的 WS frame payload 是直接 row data —— payload 形状不同。

**v5 修正**（DC.4 范围扩大）：
- 新增 enterprise `imWsClient.ts`（参考 OSS 的 `web-admin/app/.../imWebSocketClient.ts` 实现路径）
- 新增 payload adapter：把 WS frame 的 `MESSAGE / TYPING_INDICATOR` 帧适配成 ent-im-chat 现有 consumer 期望的 `data.message` 等字段格式（或者直接改 consumer，二选一在 DC.4 时决定）
- WS frame type 规范化（OSS WsFrame.type 当前用大小写不一的字符串）作为 DC.4 必修项
- DC.4 的工作量从 ~80 LOC enterprise 改写 上调到 ~200-300 LOC（新 client + adapter + consumer 适配）
- DC.4 同 session 浏览器验证 enterprise ent-im-chat 群聊页面群聊 → AI agent 回复 → typing 动画 → 消息渲染完整链路

### v5 锁定的 PR 切片（5 PRs，按 owner 调整）

| PR | 内容 | 依赖 |
|----|------|------|
| **DC.3a** 新增 server-only `AgentTurnOverrides` + AgentChatPort SPI 改用此参数（吸收原 DC.1 的 extraTools 参数）；AgentChatPortImpl 内部 step 1-5 检测并使用 overrides；REST controller 路径恒传 null overrides；写"REST 不能注入 overrides"的安全测试 | DC.1 + DC.2 |
| **DC.3b** 抽 `GroupChatTurnContextAssembler`（公共工具类）封装 `AgentReplyContext` 的 buildSystemPrompt + buildHistory；目标多群聊 caller 共享；不引入新依赖方向 | — |
| **DC.3c** named-agent 路径补全：`TurnContext` 加 `agentCode` + `taskPid`；`AuraBotTurnPersistence` 用 ctx.agentCode 解析 agentId；`ConversationTurnServiceImpl.dispatchToNamedAgent` 创建/关闭 task（含 parentTaskPid 链）；AgentReplyTask 重写为 dispatch 调 runTurn；revert D.3 在 AgentReplyTask 内的 task 写入 | DC.3a + DC.3b |
| **DC.3d** 修 handoff schema 字段名（DC.2 bug fix）+ 真实 HandoffToolProvider 产出的 schema 端到端测试 + metric `agentchatport.caller_overrides_used{field=...}` + sunset 标准文档化 | DC.3a |
| **DC.4** 跨仓 transport 删除（unchanged scope）+ enterprise 新 `imWsClient.ts` + payload adapter + frame type 规范化 + ent-im-chat consumer 适配 | DC.3c |

预估总时长：DC.3a (1天) + DC.3b (0.5天) + DC.3c (2天 — task 协议 + identity drift 双修) + DC.3d (0.5天) + DC.4 (2天 — enterprise WS client) ≈ **6 天**。比 v4 估的 4.5 天上调 1.5 天，主要是 DC.3c 的 task lifecycle 协议 + DC.4 enterprise WS 工作。

### v5 与 v4 的关键差异速览

| 维度 | v4 | v5 |
|---|---|---|
| context 注入面 | 公开 `ChatRequest` 字段 | server-only `AgentTurnOverrides` |
| 安全模型 | 漏 — REST 客户端可注入 system prompt | 修 — DTO 边界严格 |
| named-agent outbound identity | 未涉及 | DC.3c Fix 2 |
| task lifecycle 协议 | "加 parentTaskPid" 含糊 | DC.3c 明确 谁创建/谁关闭/handoff parent_id 来源 |
| DC.2 handoff schema bug | 未发现 | DC.3d 修 |
| DC.4 enterprise 工作量 | ~80 LOC | ~200-300 LOC + WS client + payload adapter |
| PR 数 | 4 (DC.3a/b/c + DC.4) | 5 (DC.3a/b/c/d + DC.4) |

---

## 11. v6 Closure (2026-05-07)

> v5 锁定的 DC.1–DC.4 + post-DC.4 GAP-311 followup 全部 land。v6 不引入新决策,只做交付审计 + 长期演进 actual-vs-claim audit + 推迟项的去向。

### 11.1 DC.1–DC.4 + GAP-311 commit 表

| Step | 范围 | OSS commit | 状态 |
|---|---|---|---|
| DC.1 | HandoffToolProvider × ToolProviderRegistry 分层(Q-DC.1=β) + AgentChatPort.runAgentTurn extraTools 参数 | `131f8890` | ✅ |
| DC.2 | transfer_to_agent 命中 → Success.meta._handoff_to(Q-DC.2=α) | `d7f9175b` | ✅ |
| DC.3a | server-only AgentTurnOverrides + SPI 第 5 参数 + security tests(v5 Fix 1) | `780fc027` | ✅ |
| DC.3b | rename AgentReplyContext → GroupChatTurnContextAssembler + tests | `f4b9dede` | ✅ |
| DC.3c | chokepoint owns task lifecycle + AgentReplyTask 走 runTurn(v5 Fix 2 + Fix 3) | `c4e3e5a4` | ✅ |
| DC.3d | handoff schema bug + caller_overrides_used metric + sunset doc(v5 Fix 4) | `d5093130` | ✅ |
| DC.4-OSS | OSS 删 SSE transport,统一 ImMessageBroadcaster WS(v5 Fix 5,β 一次性) | `372b9272` | ✅ |
| DC.4-EE | enterprise ent-im-chat 适配 WS frame | `e922c3789` (enterprise) | ✅ |
| GAP-293/295/296/311 chain | post-runTurn MESSAGE broadcast + ImMessageSentEvent publisher 接线 + ChannelSessionResolver dispatch + AuraBot bootstrap seed | `3c53d327` (on `origin/main`; 11 files, +665/-20) | ✅ pending E2E |

### 11.2 长期演进 actual-vs-claim audit

> 对应 v5 §10.5 的"长期演进价值"主张,逐条核对兑现证据。

| 维度 | v5 claim | actual | 验证锚点 |
|---|---|---|---|
| chokepoint 真实(non-decorative) | "1 个 chokepoint vs 8 物理路径" | ✅ 群聊路径全经 runTurn;AgentReplyTask / ImAiService / AuraBotController 同入口 | grep `turnService.runTurn` 主路径全覆盖 |
| god-class 反模式避免 | 选 A' 不选 B,避免 AgentChatPortImpl 累积 channel-driven 分支 | ✅ AgentChatPortImpl 仍纯接口实现;channel 语境在 caller 层(AgentReplyTask 通过 GroupChatTurnContextAssembler) | 文件 LOC 监控 |
| parallel-impl 反模式避免 | 选 A' 不选 C,避免 GroupChatAgentChatPort 子接口 | ✅ 仅 1 个 AgentChatPort impl | 没有 GroupChat- 前缀的 AgentChatPort 子类 |
| security boundary 修(v5 Fix 1) | "ChatRequest 不暴露 systemPromptOverride 等字段" | ✅ HTTP 控制器永远传 null overrides;DC.3a security test 覆盖 | DC.3a `780fc027` |
| outbound identity 正确(v5 Fix 2) | "群聊 outbound row sender_id = Alpha/Beta agentId,不再硬编 aurabot" | ✅ TurnContext.agentCode + AuraBotAgentResolver.resolve 链路 | DC.3c `c4e3e5a4` + AuraBotTurnPersistence:163 |
| task lifecycle 协议(v5 Fix 3) | "chokepoint 创建/关闭,handoff parent_id 链" | ✅ DC.3c.taskPid + AgentReplyTask handoff 透传 parentTaskPid | DC.3c |
| handoff schema 字段名(v5 Fix 4) | "agent_code,non-targetAgentCode" | ✅ + 端到端 schema test | DC.3d `d5093130` |
| dual-transport sunset(v5 Fix 5,β 决策) | "OSS 删 SseEmitterManager + enterprise 同步迁 WS,不留 dual-publish 维护负担" | ✅ DC.4-OSS + DC.4-EE 同 batch land | `372b9272` + `e922c3789` |
| group-chat reply visibility | "持久化后 WS 自动推送(对齐 ImAiService 模式)" | ✅ GAP-311 batch | `89039d0f`;E2E pending |
| metric 自我监督 | "`agentchatport.caller_overrides_used{field=...}` 工具能识别 caller 侵占面" | ✅ DC.3d 加 metric | `d5093130` |

### 11.3 v5 → v6 偏离记录

| 偏离 | v5 假设 | actual | 影响 |
|---|---|---|---|
| PR 数 | "DC.3a/b/c/d + DC.4 共 5 PR" | OSS 7 + EE 1 = 8 commit(DC.3 拆 a/b/c/d 4 PR;DC.4 OSS + EE 各 1) | 无功能差异;符合 v5 §10.8 表 |
| GAP-311 group-chat reply broadcast 时序 | v5 §6 不在范围,留作"未涉及"项 | post-DC.4 立即开 GAP-311 followup;Phase 1 探索发现 publisher 链路全仓 0 publisher,scope 主动扩到 publisher 接线 | 揭出 v5 没看到的更深 gap;ConvTurnSvc 链路最终能真正端到端工作 |
| ImMessageSentEvent 字段 | v5 未触及 | GAP-311 加 `Long seq` 字段(post-runTurn 持久化行查询用) | event v1 → v2 |
| TurnContext.channelSessionId | v5 未涉及 | GAP-295 同链路同时收口:ChannelSessionResolver 接 beginTurn 后非 null | 与 v5 正交但同链路一并 ship |
| AuraBotAgentResolver bootstrap | v5 假设 lazy seed 兜底足够 | GAP-296 补 reset 脚本 Step 7.9 per-tenant INSERT(lazy 兜底保留) | hot path 不再触发 lazy 分支 |

### 11.4 final test count snapshot (2026-05-07)

- **单测(scope:conversation + agentchat + im.service.ImAiServiceTest):118/118 全绿**
- 关键集合:
  - `ConversationTurnServiceImplDispatchTest` 8/8(含 GAP-295 channelSessionId × 2)
  - `AgentReplyTaskChokepointTest` 10/10(含 GAP-311 broadcast × 3)
  - `GroupChatAgentRouterTest` 5/5(全新,锁 seq 透传)
  - `ImAiServiceTest` 6/6(D.2 chokepoint 路径)
  - `SseResponseSink` 12/12(byte 基线)
  - `TurnCompletionMemoryListener` 9/9(C.2 gates,ship 在 DC.* 之外)
- **E2E**:`web-admin/tests/e2e/aurabot/group-chat-agent-reply.spec.ts` 已写,docker isolated stack 实跑 deferred — 跑通后 GAP-311 状态翻 DONE。

### 11.5 推迟项归宿

| 项 | 去向 |
|---|---|
| Phase B+ group-chat-adapter sub-design | GAP-294 独立立项(P2);设计提议路径 `enterprise/docs/agent/contracts/group-chat-adapter.md` |
| Phase C.1 PreGroundingTriage 接入 | Phase B 验收报告 §6 候选项;PreGroundingTriage SPI 已在 OSS,仅缺 dispatch 前接入 |
| Phase C.2 Memory L1 writeback | `TurnCompletionMemoryListener` 已 ship(独立批次,本批仅跑测试覆盖) |
| Phase C.3 ACP Phase 3-4 集成 | Phase B 验收报告 §6 候选项;ACP Stage Lifecycle 替换 aurabot tool loop |
| TurnSuspendedEvent 无消费方 | Phase B 验收报告 §5 low — Phase C 接 memory L1 时再订阅 |
| TurnContext.traceId 仍 null | Phase B 验收报告 §5 medium — outbound row card_payload 带 traceId 需 trace 链路 |
| resumeTurn toolId 不带 | Phase B 验收报告 §5 low — 多 pending 场景再加 |
| ConfirmDecision CANCELLED 前端 trigger | Phase B 验收报告 §5 low — 当前 cancelTool 走 DENIED |
| GAP-295 resume 路径 channelSessionId | 子 followup — PendingTool 加 `channelSessionPid` 字段后跨 resume 重 attach;本批仅 dispatch 路径覆盖 |

---

## CHANGELOG

- 2026-05-08 v6 closure hash audit:发现 §11.1 GAP-311 行 commit hash 写错(`89039d0f` 不存在);实际 land 的是 `3c53d327`(GAP-293/295/296/311 chain bundle,11 files +665/-20,在 `origin/main`)。修正 §11.1 表格 + status header,无内容偏离。doc-only,无 commit。
- 2026-05-07 v6 closure DC.1–DC.4 + GAP-311 follow-up 全部 land;§11 写交付审计 + 长期演进 actual-vs-claim audit + v5→v6 偏离 + final test snapshot + 推迟项归宿。无新决策。本设计文档自此进入 archival 状态。
- 2026-04-30 v5 owner reviewed v4 长期方向接受；落地形状 lock 为 **A'**（server-only `AgentTurnOverrides` 替代公开 ChatRequest 字段，安全边界修正）；§10.7 列 5 项 must-fix（含 DC.2 真实 bug：handoff schema 字段名 agent_code vs targetAgentCode）；§10.8 PR 切片调整为 DC.3a-d + DC.4 共 5 PR。
- 2026-04-30 v4 §10 三个 sub-design 选项详细对比 + 6/12 个月演进预测 + steel-man + v4 倾向 (选项 A) + DC.3a-d 落地切片. **Pending owner review.**
- 2026-04-30 v3 DC.1 + DC.2 landed (`131f8890` + `d7f9175b`); DC.3 + DC.4 deferred pending §9 sub-design — AgentReplyContext 群聊语境 absorption (3 选项) 需要单独设计 lock
- 2026-04-30 v2 owner 决策锁定 5 项；§7.1 新增"前因后果"节解释 Q-DC.4 从 α (dual-publish) 修正到 β (one-shot)；§8 锁定 4 PR 实施时序（v1 是 3 PR，DC.4 跨仓 transport 删除新加为独立 step）
- 2026-04-30 v1 初稿；5 个决策点 surfacing；候选 PR 切片 DC.1-DC.3
