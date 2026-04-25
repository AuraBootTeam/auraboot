# ACP v4 补丁方案与决定前因后果

**状态**：execution-ready（未执行，等待 owner 点头）
**日期**：2026-04-24
**关联文档**：
- 被替代：`auraboot/docs/plans/2026-04/2026-04-24-agent-control-plane-v4-design.md`（v4 主文档，本计划将 `git rm` 它）
- 目标：`auraboot-enterprise/docs/agent/` 下 6 份文档（4 改 + 2 新）
- 决策上游：v1→v2→v3→v4 四轮架构对话（保留在 git 历史，未单独存档）

---

## 0. 这份文档是什么 / 为什么存在

经过 4 轮 v1→v4 架构演进 + 与 `enterprise/docs/agent/` 既有 ACP 体系对照后的复盘结论：

1. **v4 文档（530 行）作为独立架构路线不应继续走**，与 ACP 5 层语义下沉、BIF / Action Fidelity / Substrate-neutral Skill / Memory 三维等既有概念产生平行设计冲突
2. **v4 净新增的 3 个有价值点应作为补丁回流到 ACP 体系**：Pre-Grounding Triage、Effect Taxonomy（三层）、RuntimeAuthorizationService 增量授权
3. **本文档保存完整的补丁内容 + 4 个微观决定的前因后果**，供 owner 复核与未来工程师追溯

---

## 1. 背景：v4 文档复盘

### 1.1 v4 是什么

v4 是一份从 v1（"Route + Capability + Effect + Executor"四层）演进到 v4（加入 EffectPolicy 双方法 + loop-back）的设计稿。完整内容保留在被替代文档中（执行第 1 步将 `git rm`）。

### 1.2 v4 的核心问题

对照 `enterprise/docs/agent/README.md` Tier 0/1/1a 治理体系后发现：

| v4 的"创新" | 存量已有的对应物 | 谁更成熟 |
|---|---|---|
| 4 层接口（Route/Capability/Effect/Executor） | 5 层语义下沉 L5→L0 + 6 个一等执行对象 | 存量更成熟（多 BIF 这一层） |
| `ExecutorMode` 四分 | 5 Executor + SkillEngine 3 模式 + Substrate 5 类 | 存量更成熟（substrate-neutral） |
| `SkillPackage` YAML 格式 | `skill.json + steps.json + prompt.md` 三件套 | 存量已落地 |
| `EffectBundle.atomic + rollback` | Action Fidelity per-operation 三级 + Saga 补偿 | 存量更成熟 |
| `authorizeIncremental` (loop-back) | Turn Lifecycle 10-Stage + Interrupt Protocol + StepLoopService | 存量已设计，部分已实现 |
| `Approval Gate` 异步 resumeToken | Interrupt Protocol + Pending Messages Queue（吸收 Hermes L122/L130） | 存量已落地 |
| `EXECUTE_CODE` sandbox/credential 限制 | Sandbox Router + Docker Backend + Credential Proxy + Prompt Injection Guard | 存量碾压 v4 |
| Subagent 黑名单 | Subagent Delegation 硬约束 | 存量更严 |
| Memory writeback | Memory Type × Lifecycle × Scope 三维 + ActiveMemoryService + UserSoulProfile | 存量已落地 |

v4 还**完全错过**：BIF as IR / Action Fidelity 三级 / Substrate-neutral 5 substrate / Memory 三维 / Channel Gateway / Interrupt Protocol / Sandbox Credential Proxy / Turn Lifecycle 10-Stage / Learning Loop / AuraBot vs ACP 人格-运行时分离。

### 1.3 决议

| 项 | 决议 |
|---|------|
| v4 主文档 | **删除**（不进 Tier 0/1/1a，不进 Tier 3 backlog；保留即误导） |
| v4 净新增点 A: Pre-Grounding Triage | **回流** 到 `ACP-Ideal-Agent-Design.md §6.1.2a` |
| v4 净新增点 B: Effect Taxonomy 三层 | **回流** 到新建 `contracts/effect-taxonomy.md` + 关联 3 文件改字段 |
| v4 净新增点 C: 增量授权接口 | **回流** 到新建 `contracts/runtime-authorization.md`（不进 `skill-engine.md`） |
| Capability Layer (v4 主轴) | **不回流**（存量已规划为 P1，当前 Phase 3-5 没收口前不动） |
| ExecutorMode 4 分 / SkillPackage YAML / EffectBundle atomic 等 | **不回流**（与存量 5 Executor / 三件套 / Fidelity 三级冲突） |

---

## 2. 三个补丁的完整内容

执行顺序：**A 与 C 独立 → B 依赖 C**。建议顺序 A → C → B.1 → B.2 → B.3 → B.4。

### Patch A：Pre-Grounding Triage

**目标文件**：`enterprise/docs/agent/ACP-Ideal-Agent-Design.md`
**插入位置**：§6.1.2 末尾（line 553 后），§6.1.3 之前
**新增小节**：§6.1.2a
**篇幅**：~95 行

````markdown
#### 6.1.2a Pre-Grounding Triage（Lightweight Path Short-Circuit，v2026-04 加）

**关键产品判断**：不是所有聊天都应该进入 ACP 编译链路。

当前 `AuraBotChatService` + `ChatToolResolver` 的"无条件 Grounding + 无条件平台工具注入"模式有两个真实问题：
1. **token 浪费**：trivial 闲聊（"今天天气如何"）走完整 D1 → BIF → Skill → Action → Tool 链路，单 turn 多消耗 1k+ tokens
2. **安全面扩大**：纯解释性问题（"这个表单字段什么意思"）也会暴露完整 platform tool surface 给 LLM

Session Router（§6.1.2）决议完 channel + profile + acp_user 后，**在进入 §6.2 D1 Grounding 之前**插入一个 triage 判断，决定本 turn 是否进入 ACP 编译链路。

##### Triage 三档输出

| 档位 | 含义 | 后续路径 |
|------|------|---------|
| `light_chat` | 纯闲聊 / 通用问答（无平台语义） | Bypass D1 / Skill / Action / Tool；直接 LLM-only 回复 |
| `contextual_answer` | 解释性回答，依赖 page/record context 但**不**执行平台动作 | Bypass D1 / Skill / Action；允许只读 context tool（`schema.lookup` / `record.view`），单 turn 上限 1 次 |
| `acp_run` | 完整 ACP 编译链路 | 进入 §6.2 Active Grounding (D1) |

##### Triage 决策器接口

```java
package com.auraboot.acp.runtime.triage;

public interface PreGroundingTriage {
    TriageVerdict triage(AgentEvent event,
                         ChannelSession session,
                         ConversationHistoryDigest digest);
}

public record TriageVerdict(
    TriageBucket bucket,                 // light_chat | contextual_answer | acp_run
    double confidence,                    // [0,1]
    List<String> reasonCodes,             // 可解释性
    Set<String> allowedReadOnlyTools      // 仅 contextual_answer 时填，默认 ≤1
) {}

public enum TriageBucket {
    LIGHT_CHAT,
    CONTEXTUAL_ANSWER,
    ACP_RUN
}
```

##### 决策依据（按优先级，任一命中即决议）

1. **Channel 显式覆写**：webhook / BPM 节点 → 必然 `acp_run`，不参与 triage
2. **Profile 默认策略**：`support_chat` profile 默认 `light_chat`；`crm_ops` 等事务性 profile 默认 `acp_run`
3. **历史对话热度**：本 session 最近 5 turns 都是 `light_chat` → 默认延续，除非新消息出现平台关键字
4. **关键字命中**：平台动词（"创建/更新/删除/审批/查询/统计/列表"）→ `acp_run`；纯解释/比较/总结 → `contextual_answer` 或 `light_chat`
5. **LLM 兜底**：上述都不命中时调用低成本模型（haiku 级）做轻量分类，**绝不调 D1**

##### 失败语义

- Triage 失败（超时 / 错误）→ fallback 到 `acp_run`（最严，不允许静默降级到 light）
- `light_chat` 命中后用户后续消息出现 `acp_run` 关键字 → 当前 turn 单独升级，不改变历史 turns 标记

##### 与 §6.1.5 Interrupt Protocol 的关系

`light_chat` / `contextual_answer` 不进入 §6.1.5 的 pending queue 与 interrupt 状态机（无长 run）。Triage 命中 `acp_run` 才走完整 §6.9 Stage 1-10 lifecycle。

##### 不变式

- Triage 必须 `stateless + idempotent`（同 `(message, session_state)` → 同 verdict）
- Triage **不写**任何 ACP 持久化表（`ab_agent_run` / `ab_agent_bif` / `ab_agent_action` 一概不写）
- `light_chat` / `contextual_answer` 只写最小化对话历史（`ab_agent_conversation_message`）
- Triage 决策必须落 `ab_ai_trace`，便于事后审计 "为什么这个 turn 走了 light path"

##### Schema 增量

```sql
-- ab_agent_conversation_message 增加 triage 元数据
ALTER TABLE ab_agent_conversation_message
  ADD COLUMN IF NOT EXISTS triage_bucket VARCHAR(30),       -- light_chat | contextual_answer | acp_run
  ADD COLUMN IF NOT EXISTS triage_confidence NUMERIC(3,2),
  ADD COLUMN IF NOT EXISTS triage_reason_codes JSONB;
```

##### 与现有代码的对接

| 现状 | 改造 |
|------|------|
| `AuraBotChatService.doStreamChat` 入口直接调 D1 Grounding | 入口先调 `PreGroundingTriage.triage(...)`；命中 light/contextual 时走轻量分支 |
| `ChatToolResolver` 无条件注入平台工具 | 仅在 `acp_run` 时注入完整 surface；`contextual_answer` 注入 `allowedReadOnlyTools` 子集；`light_chat` 不注入 |
| 双 grounding 重复探测 | `light_chat` / `contextual_answer` 不进 D1；`acp_run` 时 D1 单点执行 |
````

---

### Patch C：RuntimeAuthorizationService

**新文件**：`enterprise/docs/agent/contracts/runtime-authorization.md`
**新文件篇幅**：~155 行
**附加改动**：`contracts/runtime-core.md` 末尾追加 ~6 行引用

#### C.1 新文件全文

````markdown
# Runtime Authorization Contract

> Tool 执行 / Skill 加载 / Subagent delegation 的 cross-cutting 授权接口。
>
> **不属于 SkillEngine API**（避免 SkillEngine 知道 IAM/治理）；StepLoopService 与
> ToolLoopService 调用本接口决定一次具体调用是否放行。
>
> 与 `runtime-core.md` 的关系：本文件依赖 `ExecutionContext`，扩展 governance 能力。
> 与 `effect-taxonomy.md` 的关系：本文件使用 `EffectClass` 枚举，不重复定义。

## 接口

```java
package com.auraboot.acp.runtime.authorization;

import com.auraboot.acp.runtime.ExecutionContext;

/**
 * 运行时授权服务。Stage 7 EXECUTE 期间，每次 ToolCall / SkillLoad / Subagent dispatch
 * 之前由 caller（StepLoopService / ToolLoopService / SubagentDispatcher）调用。
 *
 * 不与 SkillEngine 耦合：SkillExecutor SPI 本体不调本接口；由其上层 wrapper 调用。
 */
public interface RuntimeAuthorizationService {

    /** 计划期：Stage 4 PLAN 末尾调用一次，评估本 turn 预批的 effect 集合 */
    PlanAuthorization authorizePlan(PlanAuthorizationInput input);

    /** 运行期：每次工具调用前，增量授权（复用 plan 已批的部分） */
    IncrementalAuthorization authorizeIncremental(
        ExecutionContext ctx,
        ToolCallIntent intent
    );
}

public record PlanAuthorizationInput(
    ExecutionContext ctx,
    String bifId,                                    // ab_agent_bif.pid
    String skillCode,                                // 当前 turn 主 Skill
    Set<EffectClass> declaredByPlan,                 // Plan 推断会用到的 effects
    Set<String> proposedToolRefs                     // namespaced: dsl: / mcp: / connector: / ...
) {}

public record PlanAuthorization(
    Set<EffectClass> preAuthorizedEffects,           // turn 内复用，无需再次询问
    Set<EffectClass> requiresApprovalEffects,        // 用前必须 ApprovalGate
    Set<EffectClass> forbiddenEffects,
    Map<EffectClass, String> rejectedBy,             // 必填: 哪个输入源否决的
    EffectLifetime defaultLifetime
) {}

public record ToolCallIntent(
    String toolRef,                                  // namespaced
    Set<EffectClass> requiredEffects,                // 来自 Tool/Skill declared_effects
    BlastRadius blastRadius,
    Map<String, Object> argDigest                    // 用于 ApprovalGate 渲染
) {}

public record IncrementalAuthorization(
    boolean granted,
    boolean requireApproval,
    String approvalRequestId,                        // 若 requireApproval=true
    String rejectedReason,                           // 若 granted=false
    String rejectedBy
) {}

public enum BlastRadius { REVERSIBLE, SHARED_STATE, IRREVERSIBLE }

public enum EffectLifetime {
    PER_INVOCATION,    // 默认: 每次都重新授权
    PER_BUNDLE,        // bundle 完成前持续
    PER_TURN,          // 单 turn 内有效
    PER_SESSION        // 整个 session（仅 LOW risk + 只读）
}
```

## 与现有 Approval Gate 的关系

- 本接口**不取代** §6.4 Approval Gate / BIF.risk≥L3 自动审批触发
- `authorizeIncremental` 内部判断需要审批时，**调** Approval Gate 创建 `ab_agent_approval` 记录并返回 `approvalRequestId`；caller 进入 §6.1.5 Interrupt Protocol 等待
- 也就是说：本接口是 governance 决策的**入口**，Approval Gate 是其 backend 之一

## 不变式

1. `authorizePlan` 与 `authorizeIncremental` 必须共享同一策略引擎
2. `PlanAuthorization.rejectedBy` 任何被拒 effect 都必须填，否则整个决策无效
3. 合成规则：**最严者胜**（principle of least privilege）——任一输入源拒绝即拒绝
4. 失败必须 **fail-closed**：网络/DB 错误 → 视为 `granted=false, rejectedReason="authz_unavailable"`，绝不 fail-open
5. `EffectLifetime.PER_SESSION` 仅允许 RiskClass.LOW 且不含 WRITE_* / TERMINAL_EXEC / SECRET_ACCESS

## 输入源（合成规则）

按优先级排列。任一拒绝即整体拒绝：

| 输入源 | 来源 | 优先级 |
|--------|------|--------|
| 用户权限 | `permissions.json` + 角色 binding | 否决权 |
| 租户策略 | tenant-level override | 否决权 |
| Channel 策略 | `ab_agent_tool_acl` 按 channel/profile 限制 | 否决权 |
| Capability 默认策略 | `CapabilityProfile.declaredEffects`（P1 引入） | 提议 |
| Runtime 临时降权 | 例如"本页只读模式" | 否决权 |

## 调用点

| Caller | 何时调用 | 调用方法 |
|--------|----------|----------|
| Stage 4 PLAN | Plan 生成完毕、StepLoop 启动前 | `authorizePlan(...)` 一次 |
| `ToolLoopService` | 每次 ToolCall 执行前 | `authorizeIncremental(ctx, intent)` |
| `SkillEngine` 上层 wrapper | Skill load 时（仅当 skill.declared_effects 非空） | `authorizeIncremental(ctx, intent)` |
| `SubagentDispatcher` | spawn child run 前 | `authorizeIncremental(ctx, intent)` for capability subset |

> **SkillExecutor SPI 本体不调本接口**（保持 SkillExecutor 与 governance 解耦）。

## 持久化

```sql
-- 新表: ab_agent_authorization_decision
CREATE TABLE IF NOT EXISTS ab_agent_authorization_decision (
    id              BIGSERIAL PRIMARY KEY,
    pid             VARCHAR(26) UNIQUE NOT NULL,
    tenant_id       BIGINT NOT NULL,
    run_id          VARCHAR(26) NOT NULL,            -- ab_agent_run.pid
    step_index      INTEGER,
    tool_call_index INTEGER,
    decision_kind   VARCHAR(20) NOT NULL,            -- plan | incremental
    requested_effects JSONB NOT NULL,                -- ["READ_PLATFORM_DATA", ...]
    granted_effects JSONB,
    rejected_effects JSONB,                          -- {EffectClass: rejectedBy_source}
    require_approval BOOLEAN DEFAULT FALSE,
    approval_id     VARCHAR(26),                     -- ab_agent_approval.pid
    decision_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_authz_run ON ab_agent_authorization_decision(run_id);
CREATE INDEX idx_authz_approval ON ab_agent_authorization_decision(approval_id)
  WHERE approval_id IS NOT NULL;
```

## CHANGELOG

- 2026-04-24 v1 初始化（v4 补丁回流；将 incremental authorization 从 SkillEngine 解耦）
````

#### C.2 `runtime-core.md` 末尾追加（在 `## CHANGELOG` 之前插入）

```markdown
## 关联 Contracts

- `runtime-authorization.md` —— Runtime 授权（Tool/Skill/Subagent 调用 gate），依赖本文件的 `ExecutionContext`
- `effect-taxonomy.md` —— EffectClass 8 类枚举（被 runtime-authorization 与 Action/Skill 字段共享）
```

---

### Patch B：Effect Taxonomy 三层

**依赖**：Patch C（`runtime-authorization.md` 必须先存在，B.1 才能引用）
**改动文件**：4 个

| 子补丁 | 文件 | 类型 | 篇幅 |
|--------|------|------|------|
| B.1 | `contracts/effect-taxonomy.md` | 新建 | ~125 行 |
| B.2 | `design/skill-substrate-contract.md` §3.1 + §3.2 | Edit | ~25 行 |
| B.3 | `specs/01-ActionContractSpec.md` § DDL ALTER + 新 §1.4 | Edit | ~30 行 |
| B.4 | `schemas/tables.sql` | Edit | ~15 行（新增 valid_effect_class function + ALTER 引用） |

#### B.1 新文件 `contracts/effect-taxonomy.md`

````markdown
# Effect Taxonomy Contract

> Skill / Tool / Action 的"效果"分类，供 RuntimeAuthorizationService 使用。
>
> Effect 是**执行能力的元数据标签**，不替代 `Action.fidelity` / `Action.side_effect_type` /
> `Action.risk_level` / `Action.reversal_mode`。

## 定位：Policy → Enforcement → Audit 三阶段

```
Skill/Tool 声明           → declared_effects     (Policy:    导入期/运行期声明)
RuntimeAuthorization 决策 → authorized_effects   (Enforcement: pre-flight gate)
Action/ToolCall 执行记录   → actual_effects      (Audit:    事后审计)
```

三阶段必须用**同一枚举集合**。事后审计可通过 `actual ⊆ declared` 校验 skill 是否表里如一。

## EffectClass 枚举

```java
package com.auraboot.acp.runtime.authorization;

/**
 * 8 类 effect。新增前必须更新 schemas/tables.sql 的 valid_effect_class function，
 * 以及全部 declared_effects / actual_effects 字段值集 CHECK。
 */
public enum EffectClass {
    READ_CONTEXT,           // 读 page / record / conversation 上下文
    READ_PLATFORM_DATA,     // 读 model 数据（DynamicController query / NamedQuery）
    WRITE_DRAFT,            // 写草稿 / 暂存（用户可见但未提交）
    WRITE_PLATFORM_STATE,   // 写 model 数据（Command exec, 持久化）
    EXTERNAL_NETWORK,       // 调外部 API（LLM provider 之外）
    FILE_WRITE,             // 写文件系统（artifact 落盘）
    TERMINAL_EXEC,          // 执行 shell / sandbox code
    SECRET_ACCESS           // 读密钥 / token / credential
}
```

## 八类 effect 的语义边界

| Effect | 默认风险 | 典型 Tool/Skill | 与 Action.fidelity 的常见组合 |
|--------|----------|------------------|----------------------------|
| `READ_CONTEXT` | LOW | `page.describe`, `record.view`, `conversation.history` | `full` |
| `READ_PLATFORM_DATA` | LOW | `data.query`, `namedquery.run` | `full` |
| `WRITE_DRAFT` | LOW | `draft.create`, `artifact.save_draft` | `full` |
| `WRITE_PLATFORM_STATE` | HIGH | `cmd.create`, `cmd.update`, `cmd.transition` | `full` 或 `semantic` |
| `EXTERNAL_NETWORK` | MEDIUM | `web.fetch`, `mcp.call`, `connector.invoke` | 多为 `semantic` |
| `FILE_WRITE` | MEDIUM | `file.write`, `ppt.render`, `pdf.export` | `full` |
| `TERMINAL_EXEC` | HIGH | `code.run`, `shell.exec`（sandbox 内） | `semantic` 或 `blackbox` |
| `SECRET_ACCESS` | HIGH | `secret.read`, `oauth.refresh`, `credential.issue` | `semantic`（隐藏 secret 值） |

## Effect 与现有概念的明确分工（五维正交）

| 概念 | 维度 | 谁定义 | 何时用 |
|------|------|--------|--------|
| `Action.risk_level` (L0-L4) | BIF 推断的语义风险 | Grounding Layer (RiskEvaluator, specs/03) | 决定是否触发 Approval Gate |
| `Action.fidelity` (full/semantic/blackbox) | 审计精度 | ActionRecorder (ACP-Ideal §6.4.1) | 决定 audit 界面展示与回放能力 |
| `Action.side_effect_type` (state_change/...) | 业务副作用类型 | ActionRecorder (specs/01 §1.3) | 治理报表分类 |
| `Action.reversal_mode` (auto_undo/...) | 回滚策略 | Skill 设计者 (specs/01 §1.2) | 决定补偿动作 |
| **`EffectClass`** | **执行能力分类** | **Skill/Tool 声明** | **RuntimeAuthorization pre-flight + 事后 effect diff audit** |

**这五个维度互不替代，正交存在**。新需求不要把 effect 退化为其它四者的别名。

## 三层字段位置

| 阶段 | 字段 | 类型 | 持久化位置 |
|------|------|------|------------|
| **Declare** | `declared_effects` | `EffectClass[]` | Skill: `design/skill-substrate-contract.md §3.1`<br>Tool: `design/skill-substrate-contract.md §4.2` Tool Registry |
| **Authorize** | `authorized_effects` | `EffectClass[]` | `RuntimeAuthorizationService.PlanAuthorization`（`contracts/runtime-authorization.md`） + `ab_agent_authorization_decision.granted_effects` |
| **Actual** | `actual_effects` | `EffectClass[]` | `ab_agent_action.actual_effects JSONB`（`specs/01 §1`） |
| **Rejected by** | `rejected_by` | `Map<EffectClass, String>` | 三层都可填（声明被拒 / 授权被拒 / 执行偏离） |

## Declared vs Actual 差异 audit

事后通过 `actual_effects ⊆ declared_effects` 校验 Skill 是否守约：

| 情形 | 解释 | 处理 |
|------|------|------|
| `actual ⊆ declared` | Skill 守约 | 正常 |
| `actual − declared ≠ ∅` | Skill 跑出超过声明的 effect | **告警**：可能是 Skill 漏声明，或 prompt-driven Skill 行为漂移；触发 owner review |
| `declared − actual ≠ ∅` | Skill 声明了但没用到 | 信息：可能 declared 过宽，建议收紧 |

audit 由 `LearningLoop` 周期跑（每周），统计每个 Skill 的 effect 守约率，计入 `design/learning-loop.md` 的指标看板。

## 字段值集 CHECK 约束

详见 `schemas/tables.sql` 中 `valid_effect_class` function。新增 EffectClass 枚举值时**必须**：
1. 更新本文件 `EffectClass` 枚举
2. 更新 `schemas/tables.sql` 的 `valid_effect_class` function
3. 跑 `./scripts/check-docs-drift.sh`

## CHANGELOG

- 2026-04-24 v1 初始化（v4 补丁回流；effect 作为 cross-cutting 元数据，与 fidelity/risk/side_effect/reversal 五维正交）
````

#### B.2 `design/skill-substrate-contract.md` 改动

##### B.2a 在 §3.1 末尾（line 182 `connector_mapping` block 之后），`### 3.2` 之前插入

```yaml
# ──────────── Declared Effects（v2026-04 加，effect taxonomy 三层之 Declare 层） ────────────
declared_effects:
  - READ_PLATFORM_DATA
  - WRITE_PLATFORM_STATE
  # 8 个枚举值定义见 contracts/effect-taxonomy.md
  #
  # 与 side_effects 的关系:
  #   side_effects     = 业务语义副作用（per-operation: 改 crm_lead.status）
  #   declared_effects = 执行能力分类（per-skill: 需要 WRITE_PLATFORM_STATE 能力）
  # 二者不互相 derive，必须显式声明。
  #
  # 与 RuntimeAuthorizationService 的对接:
  #   Plan stage 把所有 candidate Skill 的 declared_effects union → authorizePlan() 输入
  #   Skill load 时若 declared_effects 中含 SECRET_ACCESS / TERMINAL_EXEC，
  #   authorizeIncremental() 会触发 owner clearance check
```

##### B.2b §3.2 必填性表追加一行（line 188 表格内）

```markdown
| `declared_effects` | ✅ | ✅ | ✅ | ✅ | ✅ |
```

（declared_effects 是所有 substrate 必填，空数组允许但必须显式 `declared_effects: []`）

#### B.3 `specs/01-ActionContractSpec.md` 改动

##### B.3a 在 §1 表注释（line 77 v1.1 ALTER 扩展段落）末尾追加

````markdown
> **v2026-04 ALTER 扩展（effect taxonomy 三层之 Actual 层）**：`actual_effects` / `rejected_by` 字段不在 P0 DDL 中，作为补丁通过 `ALTER TABLE ab_agent_action ADD COLUMN IF NOT EXISTS ...` 增加。完整 ALTER 见 `schemas/tables.sql §2.2`。
>
> ```sql
> ALTER TABLE ab_agent_action
>   ADD COLUMN IF NOT EXISTS actual_effects JSONB,    -- ["READ_PLATFORM_DATA", ...]
>   ADD COLUMN IF NOT EXISTS rejected_by JSONB;       -- {"WRITE_PLATFORM_STATE": "tenant_policy"}
> ```
````

##### B.3b 在 §1.3 之后插入新 §1.4

```markdown
### 1.4 `actual_effects` / `rejected_by`（v2026-04 加，effect taxonomy 三层之 Audit 层）

| 字段 | 类型 | 说明 |
|------|------|------|
| `actual_effects` | JSONB array | 实际触发的 EffectClass 集合（8 类枚举见 `contracts/effect-taxonomy.md`） |
| `rejected_by` | JSONB object | 若 action 因授权失败 abort：哪个 effect 被哪个输入源拒绝。例：`{"WRITE_PLATFORM_STATE": "tenant_policy"}` |

**与五维 Action 元数据的分工**：effect 是**执行能力**分类，与 `risk_level`（语义风险）、`fidelity`（审计精度）、`side_effect_type`（业务副作用）、`reversal_mode`（回滚策略）四维**正交**。详见 `contracts/effect-taxonomy.md "Effect 与现有概念的明确分工"`。

**与 declared_effects diff 审计**：周期任务比对 `Action.actual_effects` ⊆ `Skill.declared_effects`。超出部分触发 Skill owner review，详见 `contracts/effect-taxonomy.md "Declared vs Actual 差异 audit"`。
```

#### B.4 `schemas/tables.sql` 改动（在文件末尾全局函数定义区追加）

```sql
-- ============================================================
-- Effect Taxonomy 共享 CHECK function (v2026-04)
-- ============================================================
CREATE OR REPLACE FUNCTION valid_effect_class(eff TEXT) RETURNS BOOLEAN AS $$
BEGIN
    RETURN eff = ANY(ARRAY[
        'READ_CONTEXT',
        'READ_PLATFORM_DATA',
        'WRITE_DRAFT',
        'WRITE_PLATFORM_STATE',
        'EXTERNAL_NETWORK',
        'FILE_WRITE',
        'TERMINAL_EXEC',
        'SECRET_ACCESS'
    ]);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ab_agent_action: 增加 effect 三层之 Actual 层字段
ALTER TABLE ab_agent_action
  ADD COLUMN IF NOT EXISTS actual_effects JSONB,
  ADD COLUMN IF NOT EXISTS rejected_by JSONB;

-- 注：declared_effects 不进 DB（只在 Skill YAML 声明，导入时校验）
--    authorized_effects 持久化在 ab_agent_authorization_decision.granted_effects
```

---

## 3. 4 个微观决定的前因后果

所有改动都是 **additive**（只加字段/枚举值/表，不删旧的）。即使 6 个月后判断错了，可以通过新增 ALTER 调整，不需要 breaking change。

### 决定 1：Triage 三档命名 = `light_chat / contextual_answer / acp_run`

#### 决定来源

这其实是 owner 自己定的（见 v4 复盘对话："三档只保留语义：`light_chat / contextual_answer / acp_run`。不要叫新的 agent route。"）。本文档只在确认。

#### 判断标准

| 标准 | 检查 |
|------|------|
| 与现有 ACP 术语不冲突 | ✅ ACP 文档里没有同名概念 |
| 三个名字互相能区分 | ✅ light=无 context、contextual=有 context 但不动手、acp=完整链路 |
| 给读代码的工程师能秒懂 | ✅ 名字本身解释了语义，不需要查文档 |

#### 回头路

`triage_bucket` 是 enum 字符串字段，改名只需要 ALTER + migration 一行 SQL。低代价。

---

### 决定 2：EffectClass 8 个枚举值

#### 决定要点

EffectClass 是给 Skill / Tool / Action 打的"能力标签"。每加一个标签 = 全平台所有 Skill/Tool 作者将来都要决定"我属不属于这个标签"。**枚举值越多越精细，但 onboarding 成本越高**。

#### 考虑过加但故意没加的 3 个

| 候选 | 加？ | 理由 |
|------|------|------|
| `MEMORY_WRITE`（写 L1/L2 memory） | **否** | 现有 `Memory Type × Lifecycle × Scope` 三维模型自己治理（`§4.3 + §6.5`），effect 不应该插一脚。memory 写入是 agent 自身状态，不是对外副作用 |
| `SEND_NOTIFICATION`（推消息给人） | **否** | 已经有 `Action.side_effect_type='human_notification'`。effect 是**能力分类**，notification 在能力层面就是 EXTERNAL_NETWORK；它的"给人看到"语义由 side_effect_type 表达 |
| `LLM_CALL`（调模型） | **否** | LLM 调用是 agent 的基本动作，不是受治理的"能力"；token 成本走 `CostBudget` 单独管，不混入 effect |

#### 留着的 8 个

```
READ_CONTEXT          ← page/record/conversation 上下文
READ_PLATFORM_DATA    ← DSL query / NamedQuery
WRITE_DRAFT           ← 草稿 / 未提交
WRITE_PLATFORM_STATE  ← Command exec / 持久化
EXTERNAL_NETWORK      ← LLM 之外的对外 API
FILE_WRITE            ← 写文件系统
TERMINAL_EXEC         ← shell / sandbox code
SECRET_ACCESS         ← 凭据 / token / OAuth
```

#### 为什么是 8 个不是 5 个或 15 个

- **5 个不够**：READ/WRITE 二分太粗，"读 context"和"读 platform data"风险面差很多（前者本地、后者跨 model 鉴权）；"写 draft"和"写 state"也必须分（前者无副作用、后者影响业务）
- **15 个太细**：每加一个 enum 值就是给所有 Skill 作者增加一个"我属不属于"判断题。8 个能覆盖 OWASP 主要风险类（数据访问 / 副作用 / 外部 / 敏感凭据），且各自映射到具体的现实威胁

#### 回头路

新增 EffectClass 是 **additive change**：
1. `EffectClass` 加一个 enum 值
2. `valid_effect_class` SQL function 加一行
3. 跑 `check-docs-drift.sh`

存量 Skill 不受影响（它们的 declared_effects 数组不动）。**新加值的难度 = 改一个 PR**。

---

### 决定 3：`RuntimeAuthorizationService` 接口形态 = 双方法单 service

#### 候选三种

| 方案 | 形态 | 优点 | 缺点 |
|------|------|------|------|
| A. 单方法 | `authorize(scope, request)` 一个方法，scope=PLAN/INCREMENTAL | 统一接口 | 失去类型安全；plan vs incremental 输入字段差太大，挤一个方法里很难看 |
| B. 双方法（**选这个**） | `authorizePlan` + `authorizeIncremental` | 类型清晰；共享内部策略引擎；Plan 阶段做"批量预授权"减少 runtime gate 次数 | 接口面 2 个方法 |
| C. 拆两个 service | `PlanAuthorizationService` + `InflightAuthorizationService` | 完全解耦 | DI 复杂；策略引擎重复；调用方要注入两个 bean |

#### 选 B 的理由

1. **共享策略引擎**：plan 期决定"什么 effect 自动放行整 turn"和 incremental 期决定"这次具体调用过不过"必须用同一套规则。如果是两个 service（C），同步两套规则会出 bug
2. **Stage 4 PLAN 已经知道一整轮的 candidate Skill 集合**：这是 plan-time 预授权的天然时机，能省掉 runtime 期 50% 的 gate 调用（性能账）
3. **类型安全**：plan 输入是 `Set<EffectClass>`，incremental 输入是单个 `ToolCallIntent`，硬塞一个方法（A）就要用 `Object` 或 sealed type，丑

#### 强制 plan 还是可选 plan

让 `authorizePlan` **mandatory**（每个 turn 都必须调一次）。原因：
- 如果可选，开发者会"先不调，需要时再说"，最后变成"全部走 incremental"，性能差
- mandatory 的副作用：caller 即使不知道 plan 信息也得调一次（可以传空 `declaredByPlan` set）。代价小

#### 回头路

接口在 `contracts/runtime-authorization.md`，按 Tier 0 治理规则改 contract = 改一份文档 + 改实现。代价中等（已有 caller 也要改），但不是数据迁移级。**真实回头路：先冻结 6 个月，跑过实际 Phase 4-5 后再 v2**。

---

### 决定 4：新表 `ab_agent_authorization_decision` vs 复用现有表

#### 现有相关表

| 表 | 记录什么 | 创建时机 |
|---|----------|----------|
| `ab_agent_approval` | 一次人工审批请求 + 决定 | 仅当需要人工审批时 |
| `ab_agent_action` | 一次写副作用执行（含 before/after） | 仅当 Action 实际执行时 |

#### 它们漏了什么

漏掉一类记录：**"被自动放行" 与 "被自动拒绝" 的授权决策**。

- 自动放行 → 没有 `ab_agent_approval` 记录（不需要人工）
- 自动拒绝 → 也没有 `ab_agent_approval`（被拒前就死了）→ 也没有 `ab_agent_action`（没执行）

→ **没有任何表记录"agent 想做 X，被 tenant 策略拦了"** —— 这是合规审计黑洞。

#### 三个候选

| 方案 | 形态 | 优缺 |
|------|------|------|
| A. 把字段塞 `ab_agent_action` | Action 表多几列 `requested_effects` / `granted_effects` | 拒绝的请求没有 Action → 不会落 → **审计黑洞还在** |
| B. 把字段塞 `ab_agent_approval` | Approval 表加自动放行/拒绝 | 改变 Approval 表语义（不再只是"人工审批"），破坏现有读端代码假设 |
| C. 新表 `ab_agent_authorization_decision`（**选这个**） | 每次授权决策一行（含自动放行 / 拒绝 / 触发审批） | 多一张表，但语义干净 |

#### 选 C 的理由

1. **语义不重叠**：
   - `authorization_decision` = "agent 想做 X，系统说 yes/no/ask-human"（**事件**）
   - `approval` = "人工审批的 ask 与 answer"（**事件**，仅当需要时）
   - `action` = "实际执行写操作的事实"（**事实**）
   - 三件事在时间上不一一对应：1 个 authorization 可能不触发 approval（自动批），可能不触发 action（被拒）
2. **合规价值最大化**：未来跑"上季度本租户被拒了多少次 SECRET_ACCESS 请求？" 这种合规报表必须有专表
3. **JSONB 不污染主表**：Action 表已经够宽了（30+ 字段），再塞授权细节会让 Action 查询变重

#### 不加 `authorization_id` FK 到 `ab_agent_action` 的理由

可以加，能给 Action 直接溯源到 authorization 决策。但：
- 已经能通过 `(run_id, step_index, tool_call_index)` 三元组 join 出来
- 加一列就要写 migration + 改所有 ActionRecorder 调用
- **延后**：等真有审计需求查"这条 action 是哪个授权放行的？"再加。**ALTER ADD COLUMN 永远可以做**

#### 回头路

`ab_agent_authorization_decision` 是**纯追加表**（agent 跑就写），不影响现有任何代码读路径。删掉这张表只损失审计，不影响系统正常跑。**回头路最干净**。

---

## 4. 执行清单

| 步骤 | 动作 | 文件 | 可逆性 |
|------|------|------|--------|
| 0 | `git rm auraboot/docs/plans/2026-04/2026-04-24-agent-control-plane-v4-design.md` | 1（删） | git restore 可恢复 |
| 1 | Edit `enterprise/docs/agent/ACP-Ideal-Agent-Design.md` 加 §6.1.2a（Patch A） | 1 | 删行就回滚 |
| 2 | Write `enterprise/docs/agent/contracts/runtime-authorization.md`（Patch C 新文件） | 1 | 删文件就回滚 |
| 3 | Edit `enterprise/docs/agent/contracts/runtime-core.md` 末尾加引用（Patch C 附加） | 1 | 删行就回滚 |
| 4 | Write `enterprise/docs/agent/contracts/effect-taxonomy.md`（Patch B.1 新文件） | 1 | 删文件就回滚 |
| 5 | Edit `enterprise/docs/agent/design/skill-substrate-contract.md` §3.1 + §3.2（Patch B.2） | 1 | 删行就回滚 |
| 6 | Edit `enterprise/docs/agent/specs/01-ActionContractSpec.md` § DDL + 新 §1.4（Patch B.3） | 1 | 删行就回滚 |
| 7 | Edit `enterprise/docs/agent/schemas/tables.sql` 末尾追加 function + ALTER（Patch B.4） | 1 | 删行就回滚 |
| 8 | 跑 `./scripts/check-docs-drift.sh` 确认无新 drift | — | 验证 |

总计 **8 文件**（1 删 + 3 新 + 4 改）。

---

## 5. 待解决的开放问题（不阻塞执行，但应在 Phase 3-5 收口前回头看）

| # | 问题 | 建议 |
|---|------|------|
| 1 | Triage LLM 兜底用的"低成本模型"具体是哪个？ haiku-4.5 / 自定义 / 关闭？ | Phase 5 前定，与计费策略配套 |
| 2 | `EffectLifetime.PER_SESSION` 在多 channel 同 session（如同一用户跨 Web 和 Slack）下生效范围？ | Channel Gateway 拆解时定 |
| 3 | `ab_agent_authorization_decision` 保留期？（合规可能要求 ≥3 年） | 与 `ab_agent_bif` retention 策略对齐 |
| 4 | Skill `declared_effects` 漂移告警的渠道（email / 站内通知 / Slack）？ | LearningLoop 完整化时定 |
| 5 | Capability Layer (P1) 进入时，`CapabilityProfile.declaredEffects` 与本方案的关系如何收敛？ | Phase 5 收口后单独评审 |

---

## 6. 决定者与时间线

- **设计 owner**：Y. Ghj (yaoyi.hz@gmail.com)
- **决定日期**：2026-04-24
- **执行触发**：owner 在对话中明确批准（"开干" 或等价表达）
- **完成判据**：8 步执行清单全部完成，`check-docs-drift.sh` 返回 0
- **后续 review 时点**：Phase 3 收口（约 2026-05 中），届时回看本方案与实际接入是否一致

---

## CHANGELOG

- 2026-04-24 v1 初始化（汇总 v1→v4 演进 + 4 个微观决定 + 3 个补丁完整内容）
