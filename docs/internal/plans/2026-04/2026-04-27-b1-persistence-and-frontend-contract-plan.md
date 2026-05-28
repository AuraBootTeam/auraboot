# B.1 真 Persistence + 前端契约迁移 — execution plan v1（待 owner 决策）

**日期**：2026-04-27
**状态**：plan v1，**未实施**。多个决策点等 owner 拍板后才能动代码。
**关联**：
- 设计稿 v3.3 [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md) §1.1 / §1.4 / §3.6 / §3.10
- B.0 落地 [`2026-04-27-b0-named-agent-spi-migration-plan.md`](./2026-04-27-b0-named-agent-spi-migration-plan.md)
- 12 维对比 + B 路线 [`2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md`](./2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md)

## 0. 摘要

B.1 把 `TurnSideEffects.Persistence.NOOP` 换成真实写库实现（写 `ab_im_message`），同时**删除**前端在 `/chat/stream` 前后调用的 `appendUserMessage` / `appendAssistantMessage` 两个入口（design §1.4 揭示的"前端代写持久化"反模式）。

这是 Phase B 的**第一个**真改动，前 4 个 PR（A.1/A.2/A.3/A.4-A.6）和 B.0 都是结构性 refactor，0 库写。B.1 之后服务端开始写 row。

## 1. Scope

**做**：
- 引入真 `Persistence` 实现 bean：`AuraBotTurnPersistence`（替换 `Persistence.NOOP`）
- `persistInbound` 写 `ab_im_message`（sender_type=human, sender_id=userId, conversation_id, client_msg_id 用作 idempotency key）
- `persistOutbound` 写 `ab_im_message`（sender_type=Q8 决策的值, ref `TurnContext` 的 turnId）
- `ConversationTurnConfig` 切换 bean 注入：`observeOnly` → 完整 `Production` profile
- 前端 `web-admin/app/plugins/core-aurabot/components-shell/AuraBotProvider.tsx` **删除** `appendUserMessage` 与 `appendAssistantMessage` 调用
- 前端 `chatStream` API 多传 `conversationId` + `clientMsgId` 字段（design §3.5 入口 #1）
- 后端 `ChatRequest` DTO 加 `conversationId` + `clientMsgId` 字段
- `AuraBotController.streamChat` 把这两字段灌进 `TurnRequest`
- 数据迁移：历史 `sender_type=system` 的 outbound 行视 Q8 决策做 backfill
- `ChatSessionStore` key 迁移：`sessionId` → `turnId`（design §3.10 step 4），暂保留双 key 兼容

**不做**（推迟）：
- `/execute` continuation `pendingTurnId` 端到端契约（design §3.10）→ 留给 **B.6**
- `EventEmitter` 真实现（Spring `ApplicationEventPublisher` 接入）→ 留给 **B.2**
- `AuditWriter` 真实现 → 留给 **B.3**
- 群聊 / WebSocket / ImAiService 入口改造 → Phase B+ group-chat-adapter sub-design

## 2. 决策点（等 owner 拍板）

### Q-B1.1 sender_type 选 A / B / C？（design §3.6 拍过未拍死的 Q8）

design §3.6 列了 3 选项。截至 2026-04-27 prod 数据 `sender_type` 分布：`human=6, system=1, agent=0`。

| 选项 | 含义 | Pro | Con |
|------|------|-----|-----|
| **A** | outbound 统一 `sender_type=agent` + agentId（AuraBot 走新引入 `AuraBotAgentResolver` 拿 default agentId） | 与群聊路径 (#7 GroupChatMessageAdapter) 一致；为未来 cross-channel 同步铺路 | 需引入 `AuraBotAgentResolver` 与 default AuraBot agent registration；历史 `sender_type=system` 数据要 backfill 或保留双语义；前端 UI 要识别两种值 |
| **B** | 保留 `sender_type=system` + sender_id=0 | 兼容历史，前端不动 | 与群聊路径分裂；Phase B+ 跨 channel 同步会再次撞 Q8 |
| **C** | 新引入 `sender_type=aurabot` 区分 | 语义最明确 | DB CHECK 要扩；前端要再加一种值；引入新枚举不是简化 |

**design v3.3 倾向**：A + backfill SQL（per `feedback_dev_stage_breaking_ok` 允许破坏）。
**Q-B1.1 拍板需要 owner 选 A / B / C 之一。**

### Q-B1.2 `AuraBotAgentResolver` 设计

如果 Q-B1.1 = A，必须解决"AuraBot 的 agentId 怎么来"。design §3.4 提到这个 resolver 但未细化。

| 子选项 | 描述 |
|--------|------|
| α | OSS 启动时插入一个固定 agentId 的 default AuraBot agent 行（`ab_agent_definition` 加一行 code='aurabot', is_default=true），resolver 直接读 |
| β | 不写 DB，TurnContext.agentId 设为 hardcoded sentinel（如 `0L` 或 `-1L`），表示"OSS default AuraBot" |
| γ | 每个租户一行 default agent；resolver 按 tenantId 查 |

**倾向 α**：与现有 ACP agent 表结构一致；多租户语义清晰；前端仍显示"AuraBot"作为 displayName，对用户无感。
**Q-B1.2 拍板需要 owner 选 α / β / γ。**

### Q-B1.3 ChatSessionStore key 迁移时序

design §3.10 step 4：`savePending(sessionId, ...)` → `savePending(turnId, ...)`。当前 `/execute` 仍用 sessionId 作 key。

| 子选项 | 描述 | 风险 |
|--------|------|------|
| **α 同期切换**（B.1 一次改完）| store 加 turnId 主 key + sessionId 二级索引；`/execute` 同期改前端契约 | B.1 范围爆炸；前端 + 后端 + DB 同改一个 PR |
| β 分两步（推荐）| B.1 只改 inbound + outbound 写库；ChatSessionStore key 留到 **B.6** 与 `pendingTurnId` 契约一起迁移 | scope 收敛；`/execute` 端到端契约整体在 B.6 落地 |

**倾向 β**：B.1 已经够大，不要叠加。
**Q-B1.3 拍板需要 owner 选 α / β。**

### Q-B1.4 前端 `appendUserMessage` / `appendAssistantMessage` 何时删

design §3.5 入口 #4 #5："Phase B 删除"。但删除时点决定回滚难度：

| 子选项 | 描述 |
|--------|------|
| α 一次性删 | B.1 后端 + 前端同 PR：服务端开始写库 + 前端两个 endpoint 调用全删 + DB 双写消除 |
| β 灰度（双写一段时间）| 先后端开写，前端保留 `appendXxx` 但加 try-catch 吞 409；soak 1 周观察服务端写入完整性后再删前端调用 |
| γ feature flag | 服务端写库带 flag，前端调用始终保留；切 flag 时由 owner 控制 |

**倾向 α**：dev 阶段允许破坏；β 双写期间 sender_type 数据被污染（前端写的 sender_type=system，后端写 Q-B1.1 决定的值），分析痛苦；γ 添加 flag 复杂度但没明显收益。
**Q-B1.4 拍板需要 owner 选 α / β / γ。**

### Q-B1.5 `clientMsgId` 幂等机制

design §1.4 提到 `clientMsgId` 用于"服务端幂等持久化 + dedup"。但具体语义未定。

| 子选项 | 描述 |
|--------|------|
| α 唯一约束 | `ab_im_message.client_msg_id` 加 UNIQUE 约束；INSERT 命中冲突时 service 层捕获并忽略 |
| β upsert by client_msg_id | INSERT ON CONFLICT DO NOTHING |
| γ 应用层去重 | service 写前查 `SELECT 1 FROM ab_im_message WHERE client_msg_id = ?`；性能差但简单 |

**倾向 β**：原子 + 性能好；α 抛异常被 catch 是稀疏 path 测不全。
**Q-B1.5 拍板需要 owner 选 α / β / γ。**

### Q-B1.6 backfill 历史 sender_type=system 数据

仅当 Q-B1.1=A 时相关。design §3.6 v3.1 P2.8 明确指出 `ImAiService` 也写 system，纯按 sender_type=system 整改可能误伤其他系统通知。

| 子选项 | 描述 |
|--------|------|
| α 不 backfill | 保留历史；新数据按 Q-B1.1 写；前端 UI 兼容显示两种值 |
| β 全量 backfill | 一次 SQL 把所有 `sender_type=system AND sender_id=0` 改 `sender_type=agent + sender_id=<aurabot agent id>`；**风险**：可能误伤 ImAiService 写的 system 行 |
| γ 条件 backfill | 用 `card_payload.source` 或 conversation `metadata.chat_kind = 'aurabot_panel'` 等元数据反查；只迁出 AuraBot 来源 |

**倾向 α** 起步，后续按需做 γ。**Q-B1.6 拍板需要 owner 选。**

## 3. 实施顺序（待决策点定后）

### Commit 1: 后端 `Persistence` 真实现 + `ConversationTurnConfig` 切换

- 新建 `AuraBotTurnPersistence` 实现 `TurnSideEffects.Persistence`
- `persistInbound` / `persistOutbound` 写 `ab_im_message`（用 Q-B1.1 决定的 sender_type）
- 如 Q-B1.2=α：新增 `AuraBotAgentResolver` + 启动时 ensure default agent 行
- `ConversationTurnConfig.turnSideEffects` 改成 `production(persistence, metricsRecorder)` profile
- 加测试：`AuraBotTurnPersistenceTest`（真 DB，不 mock）

### Commit 2: 后端 `ChatRequest` 加 `conversationId` + `clientMsgId` + controller 灌入 `TurnRequest`

- `ChatRequest` DTO 加两字段
- `AuraBotController.streamChat` 把它们写进 `TurnRequest`
- `Persistence.persistInbound` 用 `clientMsgId` 做 dedup（按 Q-B1.5 决定的机制）
- DB schema：`ab_im_message.client_msg_id` 加 UNIQUE 约束（如 Q-B1.5=α/β）

### Commit 3: 前端契约切换

- `web-admin/app/plugins/core-aurabot/api/auraBotApi.ts` 修改 `chatStream` 函数签名加 `conversationId` + `clientMsgId`
- 按 Q-B1.4 决定的策略删 `appendUserMessage` / `appendAssistantMessage` 调用：
  - α：直接删
  - β：保留 + try-catch 吞 409，加 TODO 注释指明何时删
  - γ：feature flag 包裹
- `AuraBotProvider.tsx` 调整以传新字段

### Commit 4（仅 Q-B1.6=β/γ 时）: backfill 数据迁移

- 一次性 SQL：`UPDATE ab_im_message SET sender_type=..., sender_id=... WHERE ...`
- 加文档：迁移依据 + 回滚 SQL

## 4. 测试 & 验收

### 后端

- `AuraBotTurnPersistenceTest`（integration）：
  - 调 `runTurn(turnRequest, sink)` → 验证 `ab_im_message` 多 1 个 inbound + 1 个 outbound 行
  - 校验 sender_type / sender_id / conversation_id / client_msg_id 字段值
  - 同 `client_msg_id` 重复调 → 不重复插入（按 Q-B1.5 机制）
- `ConversationTurnServiceImplPersistenceTest`：
  - finalize Success → persistOutbound 调到，row 真存在
  - finalize Failed → persistOutbound NOT 调到（design §3.4 endTurn(Failed) 不写 outbound）

### E2E

- 跑现有 aurabot 套件（B.0 已验证 78/79）→ 仍 78/79
- 新增：`ai-panel-persistence.spec.ts` 验证一次完整 chat 后 `ab_im_message` 表多 2 行（inbound + outbound）
- sender_type 分布 baseline：B.1 后跑同样 4 个 SSE smoke → `SELECT sender_type, count(*)` 应见到 +4 inbound (`human`) + +4 outbound (Q-B1.1 决定的值)

### 验收清单

| 检查 | 必过 |
|------|------|
| 后端编译 0 errors | ✅ |
| `AuraBotTurnPersistenceTest` 全过 | ✅ |
| `aurabot_turn_*` metrics 仍 +1 per call | ✅ |
| SSE byte parity 仍与 baseline 一致 | ✅ |
| `ab_im_message` 4 个 SSE smoke 后 +8 行 | ✅ |
| 前端 UI 验证消息显示正确（不重复，不漏）| ✅ |
| 前端 `appendUserMessage` / `appendAssistantMessage` 调用按 Q-B1.4 处理 | ✅ |

## 5. 风险

| 风险 | 缓解 |
|------|------|
| `clientMsgId` UNIQUE 索引在历史数据上加约束失败 | 加约束前先 audit，必要时一次性 backfill 给历史行生成唯一 client_msg_id |
| 前后端契约不同步上线导致双写或漏写 | Q-B1.4=α 一次切，PR 必须含前端 + 后端两侧改动；preview env 验证后再合 main |
| Q-B1.1=A 落地后历史 system 行造成前端渲染分裂 | UI 加兼容代码识别 system + agent 两种值（暂态）；Q-B1.6=γ 长远清理 |
| `ConversationTurnConfig` 切换到 Production profile 时其他注入冲突 | 仅替换 `turnSideEffects` bean，不动其他；测试用 `@MockitoBean` 覆盖 |
| 真 Persistence 抛异常导致 `finalizeTurn` 失败 | A.4 finalize 已 try-catch 吞异常并记 warn；不会阻塞 outcome 返回 |

## 6. 总成本估算

- 代码：~400-500 LOC（后端 ~200 + 前端 ~50 + 测试 ~150 + SQL migration ~50）
- 时间：2-3 天（取决于 Q-B1.x 决策快慢 + backfill 复杂度）
- PR：建议拆 3 个（后端 Persistence + DTO/契约 / 前端切换 / backfill），分开 review

## 7. owner 决策汇总表

| Q | 内容 | 倾向 | 待 owner 选 |
|---|------|------|-------------|
| Q-B1.1 | sender_type 选 A/B/C | A | ⏳ |
| Q-B1.2 | AuraBotAgentResolver 实现 α/β/γ（仅 Q-B1.1=A 时相关） | α | ⏳ |
| Q-B1.3 | ChatSessionStore key 迁移时序 α/β | β | ⏳ |
| Q-B1.4 | 前端 appendXxx 删除策略 α/β/γ | α | ⏳ |
| Q-B1.5 | clientMsgId 幂等机制 α/β/γ | β | ⏳ |
| Q-B1.6 | 历史 system 数据 backfill α/β/γ | α 起步 → γ | ⏳ |

**待 owner 拍 6 项后才进 §3 实施。**

## CHANGELOG

- 2026-04-27 v1 初始化（B.0 落地后写）
