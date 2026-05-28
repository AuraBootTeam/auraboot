# DSL 中的 AI 一等公民设计(2026-05-07)

## 1. 背景与动机

2026-04 ACP 闭环(`ConversationTurnService.runTurn` chokepoint + Approval Gate + ResultContract)在 OSS 落地后,后端已具备"可信企业级 AI 调用"的全部基础设施 —— 但**前端表达层仍是 hardcode tsx**:`NbaSuggestionBar` / `ActivityTimeline` / `SmartAgentToolPicker` 都是手写 React,**没有 DSL 语法把这些能力暴露给业务侧**。

业界对标:
- Notion AI / Airtable AI:工具内嵌 AI,但**调不到企业内部 API**
- Salesforce Einstein:声明式 AI 字段已有,但绑死自家平台
- AuraBoot 优势:ACP 已能调 Command / 工具 / MCP,**只需把声明式语法补齐**,即获得"业务流程内嵌可信 AI"差异化

本 spec 设计 DSL 三个层级的 AI 表达(字段 / Block / Action),复用现有后端 chokepoint,**禁止旁路**。

## 2. 设计目标

1. **声明式优先**:业务侧只写 JSON,不写 React/Java
2. **Chokepoint 复用**:所有 AI 调用必须经 `ConversationTurnService.runTurn`,共用 audit / approval / 度量
3. **三道闸默认开**:成本(token budget)/ 幻觉(provenance 强制)/ 权限(走 CommandPipeline)— DSL 不能关
4. **渐进引入**:Phase 1-2 ship 字段层即可落地试点;Block / Action 后续 Phase 增量

## 3. 范围

### 3.1 In scope

| Level | 概念 | DSL 入口 | Phase |
|---|---|---|---|
| L1 | AI Field | `field.extension.ai` | A1 / A2 |
| L2 | AI Block | `blockType: ai-summary` / `ai-suggestion-list` | A3 |
| L3 | AI Action | `action.type: ai-plan` | A5 |

### 3.2 Out of scope(本 spec 不涵盖)

- **AI 直接改 DB**:禁止;`ai-plan` 必须转换为 Command 调用
- **多 agent 协作 / orchestration**:走现有 ACP `agent_team`,不在 DSL 层暴露
- **训练 / 微调 / RAG 索引管理**:Agent 配置侧,本 spec 不涉及
- **移动端 SDUI 落地**:Phase 1 仅 Web;移动端待 P9 阶段并入

## 4. 架构总览

### 4.1 chokepoint 选择(2026-05-07 review 修正)

ACP 后端有两个入口,**AI 字段层应走 stateless 入口**,不是对话流入口:

| 入口 | 形态 | 适合本 spec? |
|---|---|---|
| `ConversationTurnService.runTurn` | 对话流(SSE/sink),持久化 conversation_id + channel_session,触发 IM/audit/`ab_im_message` 写入 | ❌ 太重 — AI 字段不是对话 |
| `AgentRunService.executeTaskSync` | 批量任务(`ab_agent_task` + `ab_agent_run`),返回 `RunOutcome`,无 IM/无 SSE | ✅ |

**现成模式**:`CustomerServiceAgentListener` 已用此路径(`InboundEmailEvent → create ab_agent_task → AgentRunService.executeTask()`)。AI 字段计算复用同样形态。

L3 `ai-plan` action 因为有 user-facing approval gate + 可能多步,**继续走 runTurn**(走对话流是合适的)。

### 4.2 流向图

```
┌──────────────────────────────────────────────────────┐
│ DSL 配置(JSON)                                       │
│   field.extension.ai = { agent, prompt, trigger,... }│
└──────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────┐
│ 前端 AiFieldRenderer / AiSummaryBlockRenderer         │
│   - debounce trigger / manual button                  │
│   - provenance + confidence 显示(Phase 1 best-effort)│
└──────────────────────────────────────────────────────┘
              │ POST /api/ai/fields/recompute  (L1)
              │ POST /api/ai/blocks/{id}/generate (L2)
              │ POST /api/ai/actions/{id}/plan (L3)
              ▼
┌──────────────────────────────────────────────────────┐
│ AiFieldComputer (L1) / AiBlockController (L2)         │
│   - cache lookup (ab_ai_field_cache)                  │
│   - budget check (aurabot_usage)                      │
│   - 构造 ab_agent_task                                │
└──────────────────────────────────────────────────────┘
              │
              ▼
┌──────────────────────────────────────────────────────┐
│ AgentRunService.executeTaskSync   ← L1/L2 chokepoint  │
│   audit + ResultContract,返回 RunOutcome              │
│                                                       │
│ ConversationTurnService.runTurn   ← L3 only(对话流)  │
│   approval gate + sink                                │
└──────────────────────────────────────────────────────┘
```

## 5. DSL 语法详解

### 5.1 Level 1:AI Field

```jsonc
{
  "code": "summary",
  "dataType": "TEXT",
  "extension": {
    "ai": {
      "agent": "aurabot",
      "prompt": "$i18n:field.summary.prompt",
      "context": ["${description}", "${attachments}"],
      "trigger": { "on": "field-change", "fields": ["description"], "debounceMs": 1500 },
      "cache": { "scope": "record", "invalidateOn": ["description", "attachments"] },
      "budget": { "modelTier": "haiku", "maxTokens": 500 },
      "approval": "none",
      "fallback": { "type": "manual-edit", "showStaleHint": true }
    }
  }
}
```

**Zod 校验红线**:
- `modelTier` enum `haiku|sonnet`(opus 需 platform_admin 全局配额)
- `cache.scope` 必填(`record|tenant|global`)
- `trigger.on` enum `manual|field-change|on-load`(`on-load` 列表页禁用,只允许 detail/form)
- `prompt` 必须以 `$i18n:` 开头(强制走 i18n 三层解析)

### 5.2 Level 2:AI Block

```jsonc
{
  "blockType": "ai-summary",
  "agent": "aurabot",
  "context": {
    "currentRecord": true,
    "relatedRecords": [{ "model": "task", "via": "project_id", "limit": 20 }]
  },
  "actions": [
    { "label": "应用为描述", "type": "command", "commandKey": "project.update_desc",
      "payloadMapping": { "description": "${ai.output}" } }
  ],
  "presentation": { "showProvenance": true, "showConfidence": true, "regenerateButton": true }
}
```

新增 blockType:
- `ai-summary` —— 单段输出
- `ai-suggestion-list` —— 多 item,每 item 可触发 Command(复用现有 `NbaSuggestionBar` 通用化)

### 5.3 Level 3:AI Action

```jsonc
{
  "label": "AI 自动分配",
  "type": "ai-plan",
  "agentKey": "task-allocator",
  "context": { "selectedRows": true },
  "approval": "before-action",
  "executeAs": "command-batch",
  "dryRun": true
}
```

执行链路:`runTurn` → 输出 `[CommandCall...]` → Approval Gate(用户预览)→ 批量经 CommandPipeline 落地。

## 6. 后端实现

### 6.1 新增组件

| 组件 | 文件(新增) | 职责 |
|---|---|---|
| `AiFieldComputer` | `platform/.../meta/ai/AiFieldComputer.java` | 字段层:trigger / cache / budget,封装 runTurn |
| `AiBlockController` | `platform/.../meta/ai/AiBlockController.java` | `POST /api/ai/blocks/{blockId}/generate` |
| `AiActionResolver` | `platform/.../meta/ai/AiActionResolver.java` | `ai-plan` → Command 序列转换 |
| `AiFieldCacheMapper` | `platform/.../meta/ai/AiFieldCacheMapper.java` | `ab_ai_field_cache` CRUD |

### 6.2 DDL

```sql
CREATE TABLE ab_ai_field_cache (
  id BIGSERIAL PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  model_code VARCHAR(64) NOT NULL,
  record_id BIGINT NOT NULL,
  field_code VARCHAR(64) NOT NULL,
  inputs_hash VARCHAR(64) NOT NULL,
  agent_version VARCHAR(64) NOT NULL,
  output TEXT NOT NULL,
  provenance JSONB,
  confidence NUMERIC(3,2),
  model_tier VARCHAR(16),
  tokens_consumed INT,
  computed_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, model_code, record_id, field_code)
);
-- UNIQUE 已自带索引覆盖前 4 列,不再额外建 idx_ai_cache_lookup
CREATE INDEX idx_ai_cache_cleanup ON ab_ai_field_cache (computed_at);  -- TTL 清理用
```

**缓存键**:UNIQUE 约束的 4 元组定位 cache 行,行内 `inputs_hash` 与 `agent_version` 校验**是否仍然有效**。读流程:
1. `SELECT ... WHERE tenant_id=? AND model_code=? AND record_id=? AND field_code=?` (走 UNIQUE 索引)
2. 命中后比对 `inputs_hash == 当前 inputs hash` 且 `agent_version == 当前 agent version`
3. 任一不等 → cache miss,触发重算并 UPSERT

### 6.3 agent_version 与 inputs_hash 定义

**agent_version**(明确公式):
```
agent_version = sha256(
  agent_code + "|" +
  resolved_prompt_text + "|" +     // i18n 解析后的最终 prompt
  model_tier + "|" +
  tools_signature                  // tool 名称排序后的 join
).substring(0, 16)
```

**inputs_hash**:
```
inputs_hash = sha256(
  ordered_join(每个 context 表达式解析后的实际值)
).substring(0, 16)
```

agent 配置改造(prompt 改、tier 切、tools 增减)→ agent_version 变 → 所有 cache 行 stale。源字段值变 → inputs_hash 变 → 该行 stale。两者独立失效,不混淆。

### 6.4 触发集成

- `CommandPipeline.PostExecutionPhase` 检测 record 变更后,异步发布 `AiFieldRecomputeEvent`
- `AiFieldComputer` 监听该事件,按 `trigger.fields` 判定是否需重算
- 重算结果写 `ab_ai_field_cache`;前端通过 record 详情查询接口拿 fresh cache(Phase 1 不做 SSE 推送,Phase 2 评估)

### 6.5 Chokepoint 约束

- **禁止**:`AiFieldComputer` 不得直接调 `AnthropicClient`,必须经 `AgentRunService.executeTaskSync`
- **禁止**:`ai-plan` 不得直接执行 SQL,必须转 Command 走 `CommandPipeline`
- **Phase 1 best-effort**:`provenance` 字段尝试通过 prompt engineering(`<sources>` 标签)收集,后端不强校验 sources 真实性 —— Phase 2 增加 server-side 校验(对照 context 字段值匹配)。这样降级换 Phase 1 可落地

### 6.6 成本闸(批量编辑保护)

- `trigger.on=field-change` 在**批量编辑模式**(`BulkEditModal` 触发)下自动降级为 manual,即使 DSL 里写了 field-change
- 检测方式:`CommandPipeline` 标记 `executionMode=BULK` 时,跳过 `AiFieldRecomputeEvent` 发布
- 用户提交后批量记录列表显示"AI 字段需手动刷新",避免 50 行批量 = 50 次 LLM 调用

每租户日 token budget 硬上限走现有 `aurabot_usage` 计数,触顶后 AI 字段返回 stale + UI 黄条警告。

## 7. 前端实现

| 改动 | 文件 |
|---|---|
| `AiFieldRenderer.tsx` | `web-admin/app/framework/meta/rendering/components/AiFieldRenderer.tsx` |
| `AiSummaryBlockRenderer.tsx` | `web-admin/app/framework/meta/rendering/blocks/AiSummaryBlockRenderer.tsx` |
| `AiSuggestionListBlockRenderer.tsx` | 同上目录 |
| `RuntimeFieldRenderer` 路由 | `extension.ai` 存在 → AiFieldRenderer 包裹 |
| BlockRenderer 分发器注册 | `BlockRenderer.tsx` |
| ComponentRuntimeManifest 注册 | `ComponentRuntimeManifest.ts` |
| Studio 配置面板(Schema-driven) | `web-admin/app/plugins/core-designer/.../schemas/aiFieldSchema.ts` |
| Zod 校验 schema | `web-admin/app/framework/meta/validation/schemas/aiExtension.ts` |

**Schema-driven 强制**:配置面板必须用 `PropertySchema[] + SchemaBlockConfigPanel`,禁止手写(遵守 CLAUDE.md "Studio = Schema-driven" 红线)。

## 8. 关键决策(已自主拍板)

1. **prompt 走 `$i18n:`** —— 复用 i18n 三层解析,自带多语言
2. **AI 字段不走 RollUp 通道** —— RollUp 同步重算,AI 异步限流,独立闸口
3. **默认 agent = `aurabot`** —— 与 `AuraBotAgentResolver` LAZY_SEED 兜底一致
4. **provenance Phase 1 best-effort,Phase 2 真校验** —— 避免 Phase 1 工时失控
5. **`ai-plan` 必走 Command** —— 禁止 AI 直改 DB
6. **`modelTier` 默认 haiku** —— 成本优先,opus 需平台级审批
7. **L1/L2 走 AgentRunService.executeTaskSync(stateless),L3 走 runTurn(对话流)** —— 分清两条 chokepoint
8. **批量编辑模式 AI 字段降级为 manual** —— 防 100 行 = 100 次调用

## 9. 测试策略

### 9.1 后端

- `AiFieldComputerIntegrationTest`:cache hit/miss / trigger / budget overflow 拒绝
- `AiBlockControllerIntegrationTest`:approval gate 集成 / SSE 推送
- `AiActionResolverIntegrationTest`:ai-plan → Command 序列转换 / dryRun

`@MockBean AnthropicClient`,真 PG + Redis(遵守 BaseIntegrationTest)。

### 9.2 E2E

`web-admin/tests/e2e/specs/ai-field-lifecycle.spec.ts`:
- 打开详情 → 改源字段 → debounce 后 AI 字段刷新
- provenance 引用记录可见
- regenerate 按钮 → 强制重算
- 断言具体输出(用 `@MockBean` 固定 fixture)

### 9.3 成本回归

CI 加 `assertTokensConsumed < threshold`,防 prompt 退化。

## 10. Phase 拆分(每 Phase = 1 PR)

| Phase | 内容 | 周 | 依赖 |
|---|---|---|---|
| **A1** | L1 字段(只 manual trigger,无 cache);走 `AgentRunService.executeTaskSync` | W1-2 | — |
| **A2** | 缓存表 + auto-trigger + budget + 批量编辑保护 | W3 | A1 |
| **A3** | L2 ai-summary block | W4 | A2 |
| **A4** | Studio Schema-driven 配置面板 + Zod schema 校验 | W5 | A3 |
| **A5** | L3 ai-plan action(approval gate 集成,可能拆 A5a/A5b) | W6-8 | A4 |

**总计 7-8 周**(原估 5-6 周 ×1.3 缓冲;A5 因 PlanApprovalGate 是新概念,工时单独加权)。

### Phase 1 验收 Go/No-Go

A1 ship 后强制 review,以下任一不达标则**整体 spec reset**,不进入 A2:
- `AgentRunService.executeTaskSync` 是否真能承载 AI 字段计算(无 conversation 副作用)
- `ab_agent_task` / `ab_agent_run` 行数是否爆炸(每字段一次 task 是否可承受)
- 实测端到端延迟 是否 < 3s(haiku 模型,典型 prompt)

### 业务验收场景(端到端 demo)

**场景 1:HR 工单自动摘要**
1. 模型 `hr_ticket` 加 `ai_summary` 字段(L1)
2. 用户在表单页修改 `description` → 1.5s 后 `ai_summary` 自动刷新
3. 详情页加 `ai-summary` block(L2),展示更长摘要 + 引用相关工单
4. 工具栏加"AI 自动分配"按钮(L3),plan 预览 → 用户接受 → 批量 Command 执行

**场景 2:任务列表智能建议**
1. 列表页加 `ai-suggestion-list` block(L2)
2. 基于当前筛选条件,AI 推荐 3 个待办优先级
3. 每条建议带"采纳"按钮,触发 Command

A1-A2 ship 即可落地场景 1 步骤 1-2。

## 11. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 列表页 100 行 × AI 字段 = 100 次 LLM | `trigger.on=on-load` 列表页禁用;字段必须 manual 或 field-change |
| Prompt 退化导致成本爆炸 | CI token regression test + 每 PR 比对 baseline |
| AI 输出注入 XSS | 输出走 React 文本节点,禁止 `dangerouslySetInnerHTML`;Markdown 渲染走 sanitizer |
| Cache 键碰撞 | inputs_hash 包含所有 context 字段值,agent_version 包含 prompt hash |
| Approval gate 旁路 | Code review 强制 grep `runTurn` 调用,Phase A5 加 architecture test |

## 12. 与现有规范对齐

- ✅ `ConversationTurnService` chokepoint(CLAUDE.md 红线)
- ✅ Studio Schema-driven(CLAUDE.md 红线)
- ✅ i18n 强制(CLAUDE.md 红线)
- ✅ 配置优先(常规 CRUD 禁止 hardcode tsx)
- ✅ Dev stage breaking-changes-preferred:无 fallback,直接新语法

## 13. 后续(Out of scope but tracked)

- **A6**:AI Block 生成 Pivot 视图(自然语言 → SavedView)—— 与 Pivot 设计文档协同
- **A7**:移动端 SDUI 接入 AI Block —— P9 阶段
- **A8**:Agent team orchestration DSL —— Q4 评估
