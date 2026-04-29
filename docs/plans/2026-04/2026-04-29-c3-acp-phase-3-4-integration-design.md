# C.3 ACP Phase 3-4 集成 — 设计文档 v1（待 owner 决策）

**日期**：2026-04-29
**状态**：design v1，**未实施**。文档把 aurabot tool loop 与现有 ACP runtime 的差异、5 个决策点、3 种迁移策略列出来，等 owner 拍板后才进 plan v1 + 代码。
**关联**：
- 设计稿 v3.3 [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md) §1.3 「ACP Phase 3-4 集成」+ §4 Phase C
- B 阶段 chokepoint 落地 [`2026-04-28-conv-turn-svc-phase-b-acceptance-report.md`](./2026-04-28-conv-turn-svc-phase-b-acceptance-report.md)
- ACP v4 patches [`2026-04-24-acp-v4-patches-and-rationale.md`](./2026-04-24-acp-v4-patches-and-rationale.md)
- 上位架构 `ACP-Architecture.md` §2.1 / §4.6 (Turn Lifecycle 10-Stage Loop) / §6.5 (Memory)

## 0. 摘要

C.3 把 `AuraBotChatService.doToolLoop`（aurabot 自己实现的简化 LLM-tool 循环，~250 LOC）替换为复用平台已就位的 ACP runtime（`StepLoopService` 549 LOC + `AgentRunService` 971 LOC + `AgentApprovalGateService` 682 LOC）。**不**新写 ACP 引擎；C.3 = 把 chokepoint 接到 ACP 入口。

收益：
- aurabot 自动获得 ACP 的全套能力：D1→BIF→Skill→Action→Tool 全链路、`ab_agent_run` / `ab_agent_action` / `ab_agent_step` 持久化、approval gate、result contract、observability
- 1565 行的 `AuraBotChatService` 进一步收缩（doToolLoop 整段去掉 → 调 ACP runtime）
- 与 named-agent 走完全同一条引擎（消除 aurabot vs named-agent 行为分裂）
- Memory 写回（C.2 接的 listener）与 ACP run 数据同源

风险：
- ACP runtime 默认 `@Async` fire-and-forget；chokepoint 的 sync core 模型（Q-A.4=A'）必须保留
- `AgentRunService.executeTask` 是 task-driven（先 `ab_agent_task` row + agentDef 配置）；aurabot 是 chat-driven（直接 user message → LLM）
- 现有 confirm_required（B.6 chokepoint 流）vs ACP `AgentApprovalGateService.checkAndRequestApproval`（基于 `ab_agent_approval` 表 + `plan_hash` 完整性）是两套语义，不能 naive 替换

## 1. 现状对照

| 维度 | aurabot 现状（`AuraBotChatService.doToolLoop`）| ACP runtime（`AgentRunService` + `StepLoopService`）|
|------|----------------------------------------------|---------------------------------------------------|
| 入口契约 | `runTurn(TurnRequest, sink)` → `executeAuraBotTurn(ctx, request, sink)` → `doToolLoop(...)`（同步）| `executeTask(tenantId, taskPid, agentCode)`（`@Async`，任务驱动）|
| 输入 | LLM messages 列表 + tools | `ab_agent_task.input_data` JSON + agent definition |
| LLM 调用 | `provider.chat(LlmChatRequest)`（直接同步）| `StepLoopService` 跨 step 编排 + plan + approval gate |
| Tool 路由 | `chatToolResolver.isReadOnly(name)`：read-only 自动执行；write 工具走 `confirm_required` SSE event | `ToolLoopService` + `ToolProviderRegistry`；写工具走 `AgentApprovalGateService.checkAndRequestApproval`（plan_hash + reviewer routing）|
| 状态持久化 | `ab_im_message`（B.1 chokepoint 接的）；可选 `chat_run` via `ChatRunPersistencePort` | `ab_agent_run` + `ab_agent_step` + `ab_agent_action` + `ab_agent_approval` 全套 |
| Suspend | `ChatSessionStore.PendingTool`（B.6 turnId-keyed Redis row）| `ab_agent_run` 状态 + `ab_agent_approval` row + `executeTaskWithResume(resumeFromRunPid)` |
| Resume | `turnService.resumeTurn(pendingTurnId)` → `chatService.resumeApprovedTurnFromPending(ctx, pending, sink)`（B.6） | `executeTaskWithResume(tenantId, taskPid, agentCode, resumeFromRunPid)` |
| Streaming | `ResponseSink.onTextChunk / onToolStart / onToolResult / onConfirmRequired / onDone / onError` | `ResultContractEmitter` 读 `ChatSseContext` ThreadLocal SSE emitter（pre-A.3 设计），并写 `ab_agent_action.result_contract` JSON 列 |
| 同步性 | sync core inside async controller hop（Q-A.4=A'）| `@Async` fire-and-forget，task 通过 DB 状态收尾 |
| Approval 安全 | basic confirm（仅 toolId / description）| `plan_hash` 完整性校验 + `ab_agent_approval.policy` reviewer routing |
| Tool 数 | 简单 toolCallCounts Map：每工具最多 5 次 | `agent_config.allowed_operations` + `max_tools` 在 `ab_agent_definition` 配置 |

## 2. 5 个决策点（owner 拍板）

### Q-C3.1 任务建模

aurabot chat 是否每条用户消息建一个 `ab_agent_task` row？

| 选项 | 描述 | Pro | Con |
|------|------|-----|-----|
| **A 每 turn 一 task**（建议）| `runTurn` 入口创建 `ab_agent_task` (assignee_type='ai', input_data={message, conversation_id, ...})，turnId 作 mission_id；`executeTask` 同步调起 ACP run | ACP run / step / action / approval 全部通过 task 关联，cross-feature 可观测；aurabot mission 与 group-chat mission（已 task-driven）统一 | task 表写入开销（每 chat turn +1 row）；`assignee_type='ai'` 在 task 视图里出现，可能干扰 user-facing UI |
| B turn 直接调 StepLoopService | 跳过 task / run，直接进 step loop | 改动最小 | 失去 ACP audit / approval / memory 的关联；mission_id 缺失 |
| C 双轨：现 confirm 路径不动，C.3 仅接 D1→BIF→Skill 不接 Tool | 用 ACP 做 grounding + skill 选择，tool execution 仍 aurabot 的 doToolLoop | 风险最低 | 收益小；core "替换 tool loop" 的目标没达成 |

**倾向 A**。

### Q-C3.2 同步性桥接

ACP `executeTask` 是 `@Async`；chokepoint sync core（Q-A.4=A'）要求 `runTurn` 同步返 `TurnOutcome`。怎么桥？

| 选项 | 描述 |
|------|------|
| α 调用方加同步等待 | `runTurn` 同步轮询 `ab_agent_run.status` until COMPLETED/FAILED；timeout 5min；同步返 outcome |
| **β 提取 sync core**（建议）| ACP runtime 加 sync 内部入口 `executeTaskSync(tenantId, taskPid, agentCode): RunOutcome`；`executeTask` (`@Async`) 包它。chokepoint 直接调 sync 版本，得 outcome |
| γ Spring SSE deferred result | 用 `DeferredResult<TurnOutcome>` 把异步 chain 拉回同步 SSE | 复杂；偏离现有 chokepoint 模式 |

**倾向 β**：与 Q-A.4=A' 「sync core + async at adapter」一致 — async 留给 controller 层。具体做法：把 `AgentRunService.doExecuteTask` 提升为 public sync 方法，`executeTask`/`executeTaskWithResume` 还作为 @Async wrapper（保持 IM 群聊事件驱动入口的兼容）。

### Q-C3.3 Approval 语义对齐

B.6 的 confirm_required（chokepoint 内 / `ChatSessionStore.PendingTool`）vs ACP `AgentApprovalGateService.checkAndRequestApproval`（`ab_agent_approval` 表 / plan_hash 校验）。

| 选项 | 描述 |
|------|------|
| **α 收敛到 ACP**（建议）| 删除 `ChatSessionStore.PendingTool` 流；`/execute` 端点改用 `AgentApprovalGateService.approve()`；前端 confirm card 拿 `ab_agent_approval.pid` 而非 turnId |
| β 双轨保留 | confirm_required 走 PendingTool；ACP run 内的 approval 写 ab_agent_approval；前端按 message 类型分流 |
| γ ACP 接入但 approval gate 关闭 | C.3 只接 chat → run；approval 暂时仍走 chokepoint PendingTool；后续阶段再合并 |

**倾向 γ 起步 → α 长期**：α 涉及前端契约再改一次（B.6 刚改完前端）+ 数据迁移 PendingTool→ab_agent_approval，scope 太大；先 γ 拆出独立 PR，等 ACP run 流稳定后再做 α。

### Q-C3.4 ResultContractEmitter vs ResponseSink

ACP `ResultContractEmitter` 通过 `ChatSseContext` ThreadLocal SSE emitter 推 SSE event（A.3 时已注释为「Phase B+ 迁 sink」）；chokepoint 现在用 `ResponseSink`。

| 选项 | 描述 |
|------|------|
| **α 把 emitter 替换成 sink**（建议）| `ResultContractEmitter` 改注入 `ResponseSink`（同 A.3 SseResponseSink 模式）；ChatSseContext 改成 ResponseSinkContext；下沉到 sink.onResultContract / onToolResult |
| β 保留 ChatSseContext 中转 | runTurn 把 sink 包装回 emitter 写 ChatSseContext.setEmitter()；ACP 不变 |

**倾向 α**：与 A.3-B 系列一致；ChatSseContext 退役（design v3.3 §3.4 已预告）。

### Q-C3.5 迁移分阶段策略

| 选项 | 描述 |
|------|------|
| **α 单 PR 切换**（dev-stage 允许）| 一次性把 doToolLoop 删掉，全部走 ACP；feature flag 控制（`aurabot.acp-mode=true/false`）后兜底 |
| β 渐进迁移：先 LIGHT_CHAT 跳过，CONTEXTUAL_ANSWER 走旧，ACP_RUN 走新 | 用 C.1 的 triage bucket 做分流；逐步把 CONTEXTUAL_ANSWER 也切到 ACP；最后下掉 doToolLoop | 最低风险但 PR 链长 |
| γ shadow run | runTurn 同时跑 doToolLoop（产生 outcome）和 ACP run（仅写库不影响 outcome）；对比两边一致后再切 | 测试期 2 倍 cost；偏离 dev-stage 简化原则 |

**倾向 β**：bucket 分流自然；每一步小 PR 可单独 review + 回滚；与 design §3.5 入口适配映射的 phased thinking 一致。

## 3. 候选 PR 切片（决策点定后）

按倾向 A + β + γ起步 + α(sink) + β(bucket-driven) 假设：

| PR | 内容 | 估 LOC | 依赖 |
|----|------|------|------|
| C.3a | `AgentRunService` 提取 `executeTaskSync(...)` public 方法（sync core）+ 单测 | ~80 | — |
| C.3b | `ResultContractEmitter` 改注入 ResponseSink（替换 ChatSseContext 中转）+ 现有 ACP 测试更新 | ~150 | — |
| C.3c | `runTurn` 在 ACP_RUN bucket 时建 `ab_agent_task` row + 调 `executeTaskSync` + 包装 RunOutcome → TurnOutcome | ~200 | C.3a + C.3b |
| C.3d | CONTEXTUAL_ANSWER bucket 也切到 ACP（验证 D1→BIF→Skill 在简单解释场景行为) | ~100 | C.3c |
| C.3e | 删除 `AuraBotChatService.doToolLoop` + 相关 helpers（剩 streamFinalResponse）+ `ChatSessionStore.PendingTool` 转移到 `ab_agent_approval` 路径 | ~300 (-1000+) | C.3d |

合计估算：~830 LOC 新增 / ~1000+ LOC 删除（净 -200）。分 5 个 PR，每个独立可 review。

## 4. 单测覆盖策略

每个 C.3 PR 必须配套：
- ACP run 真发起（integration test 跑通 `executeTaskSync`）
- chokepoint 4 outcome 仍正常分派（dispatch / finalize 测试不破）
- SSE 事件 chunk/done/tool_start/tool_result 字节流仍与 sse-baseline-2026-04-26.sha256 一致（C.3b 的 sink 接入后必须保持）
- triage bucket → ACP routing 决策正确（C.3c 验证）

预计每个 PR 5-10 个新单测。

## 5. 风险 + 缓解

| 风险 | 缓解 |
|------|------|
| ACP runtime 旧代码假定 ChatSseContext.setEmitter；改 sink 可能把 ResultContractEmitter 写散到非 chat 入口 | C.3b 单独 PR 锁住 emitter→sink 迁移；保留 ResultContractEmitter shim 类直到旧调用方都迁完 |
| `ab_agent_task` 每 chat turn +1 row 导致表膨胀 | 加 retention policy（older than 30d auto archive）；`assignee_type='ai'` filtered out from user task views |
| ACP `@Async` 模型与 chokepoint sync 模型冲突 | C.3a 单独 PR 测 sync 提取；保留 async wrapper 兼容 IM 事件入口 |
| Approval gate 双轨期前端 UX 混乱 | Q-C3.3=γ 暂保留 PendingTool；前端 confirm card UX 不变；α 阶段再切 |
| doToolLoop 删了之后简单 chat 也走全量 ACP，性能开销大 | LIGHT_CHAT bucket 早 return（不进 ACP）；CONTEXTUAL_ANSWER bucket 决策 D1 跳过快路径 |

## 6. 不在范围（明确推迟）

- mobile / WebSocket / IM-event-driven 入口（design §3.5 #7 #8）— 仍 Phase B+ group-chat-adapter sub-design
- ACP Phase 5-10 的 cross-channel sync — 与 C.3 正交
- 移除 `ChatRunPersistencePort`（aurabot 现在写的）— 等 ACP `ab_agent_run` 接管后再清

## 7. owner 决策汇总表

| Q | 内容 | 倾向 | 待 owner 选 |
|---|------|------|-------------|
| Q-C3.1 | 任务建模 A/B/C | A 每 turn 一 task | ⏳ |
| Q-C3.2 | 同步性桥接 α/β/γ | β 提取 executeTaskSync | ⏳ |
| Q-C3.3 | Approval 语义对齐 α/β/γ | γ 起步 → α 长期 | ⏳ |
| Q-C3.4 | ResultContractEmitter sink 迁移 α/β | α 改注入 sink | ⏳ |
| Q-C3.5 | 迁移分阶段策略 α/β/γ | β bucket-driven 渐进 | ⏳ |

**待 owner 拍 5 项后才写 C.3 plan v1 + 进 §3 PR 切片。**

## CHANGELOG

- 2026-04-29 v1 初始化（C.2 落地后写）
