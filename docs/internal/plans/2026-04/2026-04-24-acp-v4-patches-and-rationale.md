# ACP v4 补丁方案与决定前因后果

**状态**：revised v1.4（2026-04-26 step 0 验证 + α 决策落地；可开始执行 step 1+2+6 的 contract 文件创建）
**日期**：2026-04-24（v1） / 2026-04-26（v1.2 + v1.3 + v1.4）
**关联文档**：
- 被替代：`auraboot/docs/plans/2026-04/2026-04-24-agent-control-plane-v4-design.md`（v4 主文档，本计划将 `git rm` 它）
- 目标：`auraboot-enterprise/docs/agent/` 下 ~10 个文件改动（详见 §3 执行清单）
- 决策上游：v1→v2→v3→v4 四轮架构对话（保留在 git 历史，未单独存档）

> **2026-04-26 review 收口**：
> - **v1.2** 合入第一轮 review 9 项修正（依赖方向 / Patch A 拆 Tier 0 / 授权粒度 / 审计表主体 / Effect CHECK / declared_effects 持久化 / README 索引 / PER_SESSION scope / 计数错）
> - **v1.3** 合入第二轮 review 9 项修正（DDL 单源收口 / authorizePlan 输入 per-call / ToolCallIntent 补字段 / argDigest 拆 hash+preview / ExecutionContext 加 channelSessionId / IM 表选型转 blocker / declared_effects 两阶段必填 / Patch B 依赖措辞 / 完成判据 step 数）
>
> 详见末尾 CHANGELOG 与各 patch 内 `[v1.2]` / `[v1.3]` 标记段落。**step 0 三项验证未 unblock 前不能开干。**

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

> **[v1.2 修正]** 原 v1 写错了依赖方向。`runtime-authorization.md`（C）的接口里使用 `EffectClass`，所以 C **依赖** B.1（effect-taxonomy.md），不是反过来。
>
> **正确执行顺序**（详见 §3 执行清单）：
> 1. **B.1**（effect-taxonomy.md，定义 EffectClass 枚举与三层语义）
> 2. **C**（runtime-authorization.md，引用 EffectClass）
> 3. **Tier 0 索引更新**（README + contracts/README）
> 4. **A.0**（pre-grounding-triage Tier 0 contract + schemas/tables.sql 真实 DDL）
> 5. **A.1**（ACP-Ideal 叙述补丁，仅引用 A.0 contract，不重复 Java/DDL）
> 6. **B.2 / B.3 / B.4**（字段落点 + ab_agent_skill ALTER + CHECK 约束）
> 7. v4 主文档归档
> 8. drift audit

### Patch A：Pre-Grounding Triage

> **[v1.2 修正]** 原 v1 把 Java 接口与 DDL 直接塞进 `ACP-Ideal-Agent-Design.md`（Tier 1a），违反 README §0 "Java 接口签名和 DDL 只能在 Tier 0 定义"。Patch A 拆为两部分：
>
> - **A.0 Tier 0 contract**：新建 `contracts/pre-grounding-triage.md`，承载 `PreGroundingTriage` 接口签名 + 关联 schema 引用
> - **A.1 ACP-Ideal 叙述**：§6.1.2a 只描述设计动机、决策依据、与现有代码对接，**不重复**接口/DDL 定义，仅引用 A.0
> - **A.2 真实 DDL**：写入 `schemas/tables.sql`（`ab_agent_conversation_message` 当前不存在 → 同时新建该表的最小 schema）

#### A.0 新文件 `contracts/pre-grounding-triage.md`（Tier 0）

````markdown
# Pre-Grounding Triage Contract

> Channel Gateway 决议完 channel + profile 后，在进入 D1 Grounding 之前调用本接口，
> 决定本 turn 是否需要进入 ACP 完整编译链路。
>
> 与 `runtime-core.md` 的关系：本接口产出的 `TriageVerdict` 决定后续是否执行 Stage 3-10。

## 接口

```java
package com.auraboot.acp.runtime.triage;

import com.auraboot.acp.runtime.ExecutionContext;

public interface PreGroundingTriage {

    /**
     * Stage 2.5（COMPRESS_CHECK 之后，GROUND 之前）调用一次。
     * 必须 stateless + idempotent；同 (message, session_state) → 同 verdict。
     * 不允许写任何 ACP 持久化表（ab_agent_run / ab_agent_bif / ab_agent_action）。
     */
    TriageVerdict triage(AgentEvent event,
                         ChannelSession session,
                         ConversationHistoryDigest digest);
}

public record TriageVerdict(
    TriageBucket bucket,
    double confidence,                       // [0,1]
    List<String> reasonCodes,
    Set<String> allowedReadOnlyTools         // 仅 contextual_answer 时填，默认 ≤1
) {}

public enum TriageBucket {
    LIGHT_CHAT,
    CONTEXTUAL_ANSWER,
    ACP_RUN
}
```

## 不变式

1. `stateless + idempotent`
2. **不写** `ab_agent_run` / `ab_agent_bif` / `ab_agent_action`
3. `light_chat` / `contextual_answer` 只允许写 `ab_agent_conversation_message`
4. Triage 决策必须落 `ab_ai_trace`
5. 失败（超时/错误）→ fallback 到 `acp_run`（绝不 fail-open 到 light）
6. `contextual_answer` 的 `allowedReadOnlyTools` 工具调用次数硬上限 1 次

## 关联 Schema

- `ab_agent_conversation_message`：新增 `triage_bucket / triage_confidence / triage_reason_codes` 三列（详见 `schemas/tables.sql §X.Y`）

## CHANGELOG

- 2026-04-26 v1 初始化（v4 补丁回流；Tier 0 单源接口签名）
````

#### A.1 `ACP-Ideal-Agent-Design.md` §6.1.2a 叙述补丁

**目标文件**：`enterprise/docs/agent/ACP-Ideal-Agent-Design.md`
**插入位置**：§6.1.2 末尾，§6.1.3 之前
**新增小节**：§6.1.2a
**篇幅**：~60 行（不含 Java 接口与 DDL，仅叙述 + 引用）

````markdown
#### 6.1.2a Pre-Grounding Triage（Lightweight Path Short-Circuit，v2026-04 加）

> **接口与 schema 单源**：`PreGroundingTriage` Java 接口签名见 `contracts/pre-grounding-triage.md`；
> `ab_agent_conversation_message` 表 DDL 与 triage 字段见 `schemas/tables.sql`。
> 本节只描述设计动机、决策依据与现有代码对接，不重复签名/DDL。

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

##### 决策依据（按优先级，任一命中即决议）

1. **Channel 显式覆写**：webhook / BPM 节点 → 必然 `acp_run`，不参与 triage
2. **Profile 默认策略**：`support_chat` profile 默认 `light_chat`；`crm_ops` 等事务性 profile 默认 `acp_run`
3. **历史对话热度**：本 session 最近 5 turns 都是 `light_chat` → 默认延续，除非新消息出现平台关键字
4. **关键字命中**：平台动词（"创建/更新/删除/审批/查询/统计/列表"）→ `acp_run`；纯解释/比较/总结 → `contextual_answer` 或 `light_chat`
5. **LLM 兜底**：上述都不命中时调用低成本模型（haiku 级）做轻量分类，**绝不调 D1**

##### 与 §6.1.5 Interrupt Protocol 的关系

`light_chat` / `contextual_answer` 不进入 §6.1.5 的 pending queue 与 interrupt 状态机（无长 run）。Triage 命中 `acp_run` 才走完整 §6.9 Stage 1-10 lifecycle。

##### 与现有代码的对接

| 现状 | 改造 |
|------|------|
| `AuraBotChatService.doStreamChat` 入口直接调 D1 Grounding | 入口先调 `PreGroundingTriage.triage(...)`；命中 light/contextual 时走轻量分支 |
| `ChatToolResolver` 无条件注入平台工具 | 仅在 `acp_run` 时注入完整 surface；`contextual_answer` 注入 `allowedReadOnlyTools` 子集；`light_chat` 不注入 |
| 双 grounding 重复探测 | `light_chat` / `contextual_answer` 不进 D1；`acp_run` 时 D1 单点执行 |
````

#### A.2 Triage 元数据持久化（**[v1.3] 表选型未决，列入 blocker**）

> **[v1.3 review P1.4]** 原 v1.2 直接 `CREATE TABLE ab_agent_conversation_message`，但 OSS core 已有 `ab_im_conversation` / `ab_im_message` 作为 IM 消息存储（参见 `project_oss_enterprise_boundary` memory：IM/AuraBot 都在 OSS）。再建 `ab_agent_conversation_message` = 第二套消息表，会制造存储分裂 + 跨表 join 噩梦 + 后续不知道该读哪张表。

**两个候选**：

| 选项 | 形态 | 优 | 缺 |
|------|------|----|----|
| **A. 复用 `ab_im_message`** | `ALTER TABLE ab_im_message ADD COLUMN triage_*` | 单一消息存储；agent + 真人发言天然在一张表上下文连续 | IM 表语义被 ACP 入侵；triage 列对纯人对人消息恒为 null |
| **B. 新建 `ab_agent_conversation` + `ab_agent_message` 完整两张表** | 仿 IM 但独立 | ACP 边界清晰；不污染 IM | 双消息存储；跨表 join；前端 timeline 拼接复杂 |

**[v1.4 step 0 验证完成 + 决策落地]**：选项 A 通过，但暴露持久化精度问题。详见下文。

##### Step 0 验证结果（2026-04-26 跑完）

| 项 | 结果 | 证据 |
|---|------|------|
| Q1 `ab_im_message.sender_type` 支持 agent | ✅ | 字段为 `VARCHAR(20)`，schema 注释 `human \| agent`，`ImConstants.SENDER_TYPE_AGENT` 已定义；`GroupChatMessageAdapter:175,205` 实际写入 |
| Q2 租户隔离 + ACL 与 ACP 一致 | ✅ | `tenant_id BIGINT NOT NULL` + 索引；`ab_im_conversation` 已有 `conductor_agent_id` / `bound_model_code` / `ai_context_window` |
| Q3 AuraBot SSE 输出落 ab_im_message | ⚠️ partial | `AuraBotConversationService.sendMessage` ✅；`GroupChatMessageAdapter` ✅；**`AuraBotChatService.doStreamChat`（直连 SSE 入口）❌ 完全不写 ab_im_message** |

prod 数据 `sender_type` 当前分布：human=6, system=1, **agent=0** → 直连 SSE 路径已运行但无 agent 消息持久化。

##### 暴露的 precondition：AuraBotChatService 持久化缺失

Pre-Grounding Triage 要插入在 `AuraBotChatService.doStreamChat`，但该路径**不持久化消息**。triage_bucket / triage_confidence / triage_reason_codes 加到 `ab_im_message` 上没用——直连 SSE 根本不写这张表。

##### α vs β 选型决策（α 胜出）

| 方案 | 形态 | 选 |
|------|------|---|
| **α 同步持久化** | doStreamChat 入口 `imMessageService.sendMessage(user msg)` + SSE 结束时同步落 agent msg | ✅ |
| **β 异步持久化** | `@Async` / 事件总线 fire-and-forget | ❌ |
| **γ 另起一张 ab_agent_triage_decision** | 与 P1.4 担忧的"分裂存储"相悖 | ❌ |

**α 的延迟代价**（实测估算）：

| 环境 | 单 INSERT 耗时 | 对首 token 影响 |
|------|----------------|-----------------|
| PostgreSQL 本地 unix socket | 0.3-1ms | 几乎不可测 |
| PostgreSQL 同机房 TCP | 1-3ms | <1% (vs ~500ms LLM 首 token) |
| 跨机房 / 云托管 RDS | 3-10ms | ~1-2% |

总每 turn 新增 ~2-10ms，远低于 LLM 首 token 200-1000ms。`ab_im_message` 4 个索引（pkey + dedup + search + sync），无 FK cascade、无触发器，写入廉价。PostgreSQL INSERT 吞吐 1-5 万/s，到 10 万 turn/s 量级前 LLM 成本会先把你压垮。

**为什么不选 β**：
1. 延迟开销（< 1% 总 turn 时间）实在太小，β 的"性能优势"不存在
2. β 让 "chat 已发出 + audit 缺失" 成为可能 → triage 决策追溯不到 → 违反治理初衷
3. β 需要 @Async + 队列 + 失败重试 + 监控；α 只需 2 行 service 调用
4. `GroupChatMessageAdapter` 已经是同步落库，AuraBot 直连保持一致

**唯一硬约束（α 必须做的）**：INSERT 用**独立短事务**，禁止把 DB 连接持有到 SSE 结束。两次 `imMessageService.sendMessage` 各起各的短事务，5-30s 的 SSE 流不锁连接。

---

##### A.2 实际 DDL（v1.4 选项 A 落地）

```sql
-- ab_im_message 加 triage 元数据（选项 A：复用 IM 消息表）
ALTER TABLE ab_im_message
  ADD COLUMN IF NOT EXISTS triage_bucket VARCHAR(30)
     CHECK (triage_bucket IS NULL OR triage_bucket IN ('light_chat', 'contextual_answer', 'acp_run')),
  ADD COLUMN IF NOT EXISTS triage_confidence NUMERIC(3,2)
     CHECK (triage_confidence IS NULL OR (triage_confidence >= 0 AND triage_confidence <= 1)),
  ADD COLUMN IF NOT EXISTS triage_reason_codes JSONB;

CREATE INDEX IF NOT EXISTS idx_im_msg_triage_bucket
  ON ab_im_message(triage_bucket)
  WHERE triage_bucket IS NOT NULL;
```

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
    String planHash,                                 // ★ v1.3: 本 Plan 的 hash，写入 ab_agent_authorization_decision.plan_hash
    List<PlannedCall> plannedCalls                   // ★ v1.3: per-call 建模，不再是 effect union
) {}

/**
 * v1.3 新增：Plan 阶段对每个 candidate 工具调用的精细描述。
 * 一个 Plan 可能产出多个 PlannedCall（一个 Skill 可能调多个 Tool）。
 * 每个 PlannedCall 单独评估，生成对应的 GrantScope（或被拒）。
 *
 * 没有 PlannedCall 列表 → authorizePlan 退化成"按 effect 类整 turn blanket 放行"，
 * 这正是 v1.2 review P1.3 指出的 anti-pattern。
 */
public record PlannedCall(
    String skillCode,                                // 哪个 Skill 触发的调用
    String toolRef,                                  // 具体 toolRef（namespaced）
    Set<EffectClass> requiredEffects,                // 该调用需要的 effects
    BlastRadius blastRadius,
    String argHashPattern,                           // 可选：sha256 前缀或精确值约束（不知道精确 args 时填 null）
    Map<String, Object> argPreview                   // 可选：redacted args（用于 ApprovalGate UI 渲染）
) {}

public record PlanAuthorization(
    String planHash,                                 // 与本 Plan 绑定，runtime 比对
    List<GrantScope> preAuthorizedGrants,            // ★ v1.2: 不是 Set<EffectClass>，必须按 scope 建模
    List<GrantScope> requiresApprovalGrants,         // 用前必须 ApprovalGate（仍带 scope 约束）
    Set<EffectClass> forbiddenEffects,               // 全 turn 禁止的 effect
    Map<EffectClass, String> rejectedBy,             // 必填: 哪个输入源否决的
    EffectLifetime defaultLifetime
) {}

/**
 * v1.2 新增：授权不能只按 EffectClass 给整 turn blanket 放行。
 * 必须绑定到具体 (toolRefPattern, skillCodePattern, blastRadius 上限, 参数约束)，
 * 否则一次低风险写会让整 turn 内任意 WRITE_PLATFORM_STATE 全部豁免 ApprovalGate。
 *
 * 匹配规则（authorizeIncremental）：
 *   一个 ToolCallIntent 与一个 GrantScope 匹配当且仅当：
 *     1. effect 相等
 *     2. toolRefPattern 匹配 intent.toolRef（glob，null = 任意）
 *     3. skillCodePattern 匹配（null = 任意）
 *     4. intent.blastRadius ≤ maxBlastRadius
 *     5. argDigestConstraint 满足 intent.argDigest（null = 任意）
 *     6. lifetime 未过期
 *     7. planHash 等于当前 Plan
 *   命中 → granted，跳过审批；未命中 → 走 fresh 评估（可能触发 ApprovalGate）
 */
public record GrantScope(
    EffectClass effect,
    String toolRefPattern,           // null = 任意；支持 glob，例 "dsl:cmd_crm_*"
    String skillCodePattern,         // null = 任意
    BlastRadius maxBlastRadius,      // intent 不能超过此级
    String argHashConstraint,        // ★ v1.3 P2.6: 改名 argDigestConstraint → argHashConstraint（与 DDL/Intent 一致）
    EffectLifetime lifetime,
    String policyId,                 // 引用授权时所用 policy 版本
    int policyVersion
) {}

public record ToolCallIntent(
    // ★ v1.3 P1.2: 补齐 GrantScope 匹配规则需要的全部输入字段
    String toolRef,                                  // namespaced，匹配 GrantScope.toolRefPattern
    String skillCode,                                // 哪个 Skill 在调用，匹配 GrantScope.skillCodePattern
    String currentPlanHash,                          // 当前 Plan hash，匹配 GrantScope.planHash
    Integer toolCallIndex,                           // Step 内第几次 ToolCall（写 audit 表）
    Set<EffectClass> requiredEffects,                // 来自 Tool/Skill declared_effects
    BlastRadius blastRadius,                         // 必须 ≤ GrantScope.maxBlastRadius
    String argHash,                                  // ★ v1.3 P2.6 拆分：sha256(canonical_args)，用于 GrantScope 匹配 + 持久化
    Map<String, Object> argPreview                   // ★ v1.3 P2.6 拆分：redacted args，用于 ApprovalGate UI 渲染（不入 hash）
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
    PER_TURN,          // 单 turn 内有效；scope key = (tenant_id, run_id)
    PER_SESSION        // 整个 session 有效；scope key = (tenant_id, user_id, profile_id, channel_session_id)
                       // 仅允许 RiskClass.LOW + 只读（READ_CONTEXT / READ_PLATFORM_DATA）
                       // [v1.2] scope key 必须显式持久化到 ab_agent_authorization_decision，
                       //        runtime 缓存按完整 4 元组 key 查询，禁止只用 user_id 跨 channel/profile 命中
}
```

## 对 `runtime-core.md` 的依赖扩展（v1.3）

PER_SESSION lifetime 的 scope key 需要 `channel_session_id`，但当前 `ExecutionContext` 只有 `channel`（频道类型字符串）+ `profileId`，没有 channel session 维度。

**Patch C 必须同步 Edit `runtime-core.md`** 给 `ExecutionContext` 加一个字段：

```java
public record ExecutionContext(
    long tenantId,
    long userId,
    String runId,
    int stepIndex,
    String channel,
    String profileId,
    String channelSessionId,            // ★ v1.3 新增：ab_agent_channel_session.pid，PER_SESSION scope key 必需
    Map<String, Object> runState,
    TokenBudget tokenBudget,
    CostBudget costBudget
) {}
```

调用方迁移：现有 `ExecutionContext` 构造点（StepLoopService / SkillExecutor wrapper）必须从 `ChannelSession.lookupOrCreate(...)` 取 `pid` 注入。本字段允许 null（兼容 webhook 等无 channel session 入口），但带 `EffectLifetime.PER_SESSION` 的 grant 在 channelSessionId=null 时降级为 PER_TURN。

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

> **[v1.3]** Tier 0 单源治理：所有 `ab_agent_*` 表的 CREATE TABLE 由 `schemas/tables.sql` 承载，
> contract 文档只引用 + 描述列语义。本节不重复 DDL，完整 CREATE TABLE 见 Patch B.4。

`ab_agent_authorization_decision` 字段语义说明（DDL 在 schemas/tables.sql）：

| 字段 | 类型 | 用途 |
|------|------|------|
| `pid / tenant_id / run_id / step_index / tool_call_index` | 标识 + 关联 | 与 ab_agent_run/action 对齐 |
| `decision_kind` | plan \| incremental | 决策时机 |
| `tool_ref / skill_code / arg_hash / blast_radius` | 主体信息（X = "agent 想做的事"） | incremental 必填，plan 可空。被拒请求没 Action 可 join，单靠这些字段重建完整故事 |
| `requested_effects / granted_effects / rejected_effects` | JSONB EffectClass[] | 请求与决策结果，CHECK 通过 valid_effect_class_array |
| `plan_hash / grant_scope` | Plan 关联 | incremental 复用 plan grants 时携带 |
| `policy_id / policy_version / decision_reason` | 治理元数据 | 治理 policy 版本可追溯 + 人话解释 |
| `require_approval / approval_id` | 审批链 | 触发审批时填 ab_agent_approval.pid |
| `session_scope_key` | `{tenant}:{user}:{profile}:{channel_session}` | PER_SESSION lifetime 缓存所需 4 元组 key |

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

> **[v1.3 P2.5 修正]** 原 v1.2 写"依赖 Patch C"反了。**B.1（effect-taxonomy.md）必须先于 C（runtime-authorization.md）**，因为 C 的接口里使用 `EffectClass` 枚举，C 需要引用 B.1。详见 §2 开篇与 §4 执行清单。
>
> 内部依赖：B.4（schemas/tables.sql）依赖 B.1（EffectClass 命名集合）；B.2/B.3 各自独立可并行。

**依赖**：B.1 → C → B.4；B.2/B.3 任意时机
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

> **[v1.2]** Declare 层必须**双重落地**：YAML 声明（导入校验源）+ DB 列（runtime 加载源 + 周审计 join）。
> 原 v1 写"declared_effects 不进 DB"是错的——RuntimeAuthorizationService 在 Stage 4 加载 candidate skill 时
> 必须从 `ab_agent_skill` 直接读 declared_effects，不能反查 YAML 文件。

| 阶段 | 字段 | 类型 | 持久化位置 |
|------|------|------|------------|
| **Declare** | `declared_effects` | `EffectClass[]` | YAML 源：`design/skill-substrate-contract.md §3.1` skill.yaml<br>**DB 源**：`ab_agent_skill.declared_effects JSONB`（导入时从 YAML 灌入；runtime 与 audit 都从 DB 读）<br>Tool 同理：tool registry 表新增列（具体表名待 tool registry 实现确定，可能是 `ab_agent_tool` 或 namespace 注册表） |
| **Authorize** | `authorized_effects` | `EffectClass[]` | `RuntimeAuthorizationService.PlanAuthorization`（`contracts/runtime-authorization.md`） + `ab_agent_authorization_decision.granted_effects` |
| **Actual** | `actual_effects` | `EffectClass[]` | `ab_agent_action.actual_effects JSONB`（`specs/01 §1`） |
| **Rejected by** | `rejected_by` | `Map<EffectClass, String>` | 三层都可填（声明被拒 / 授权被拒 / 执行偏离） |

### Declare 层导入流程

```
Skill YAML → SkillImporter.parse()
                ↓
      校验 declared_effects 全部 ∈ EffectClass enum
                ↓
      INSERT/UPDATE ab_agent_skill SET declared_effects = ?
                ↓
   导入完成；runtime 加载与 weekly audit 全部读 DB 列
```

YAML 是源、DB 是 cache 兼 runtime 接口。两者必须一致，由 SkillImporter 保证（YAML 改了就 reimport）。

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
| `declared_effects` | ⚠️ | ⚠️ | ⚠️ | ⚠️ | ⚠️ |
```

> **[v1.3 P2.7 修正]** 原 v1.2 写"所有 substrate 必填"破坏了 additive 叙述。现有 ab_agent_skill 行没有此字段，硬必填会导致存量 skill 全部 import 失败 / runtime load 失败。
>
> 改为**两阶段必填**：
>
> **Phase 1（v1.3 落地时，宽限期）**：
> - YAML 缺 `declared_effects` → SkillImporter 默认补 `[]` + 写一条 WARN 日志（owner 看到要补声明）
> - DB 列 `declared_effects` NULL = 视同 `[]`（runtime load 不报错）
> - validator 不 reject 缺字段的 skill；只在 import 报告里列出 "声明缺失" 名单
> - 现有 ab_agent_skill 行用 `UPDATE ab_agent_skill SET declared_effects = '[]'::jsonb WHERE declared_effects IS NULL` 一次 backfill
>
> **Phase 2（≥ 30 天后，由 owner 启动严格期）**：
> - YAML 缺字段 → SkillImporter reject
> - DB 列加 NOT NULL（先验证 backfill 100% 完成）
> - validator 强制要求显式 `declared_effects`（即使是空数组）
>
> Phase 1 → Phase 2 切换由 owner 决策，不在本方案范围。

`declared_effects` 必须在 Phase 2 前完成所有 Skill 的真实声明，否则 RuntimeAuthorizationService 与 weekly audit 的精度受限。

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

> **[v1.2]** 三处修正：
> 1. `ab_agent_skill.declared_effects` 必须落 DB（runtime 加载 + audit join 都需要）
> 2. 所有 EffectClass JSONB 字段必须加 CHECK（用 `valid_effect_class_array` 包装函数）
> 3. 单元素 function 与数组校验 function 都提供

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

-- 数组校验包装：检查 JSONB array 内每个元素都是合法 EffectClass
CREATE OR REPLACE FUNCTION valid_effect_class_array(arr JSONB) RETURNS BOOLEAN AS $$
DECLARE
    elem TEXT;
BEGIN
    IF arr IS NULL THEN RETURN TRUE; END IF;
    IF jsonb_typeof(arr) <> 'array' THEN RETURN FALSE; END IF;
    FOR elem IN SELECT jsonb_array_elements_text(arr) LOOP
        IF NOT valid_effect_class(elem) THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ============================================================
-- Declare 层：ab_agent_skill 增加 declared_effects 列
-- ============================================================
ALTER TABLE ab_agent_skill
  ADD COLUMN IF NOT EXISTS declared_effects JSONB
    CHECK (declared_effects IS NULL OR valid_effect_class_array(declared_effects));

CREATE INDEX IF NOT EXISTS idx_skill_declared_effects
  ON ab_agent_skill USING GIN (declared_effects);

-- ============================================================
-- Actual 层：ab_agent_action 增加 actual_effects + rejected_by
-- ============================================================
ALTER TABLE ab_agent_action
  ADD COLUMN IF NOT EXISTS actual_effects JSONB
    CHECK (actual_effects IS NULL OR valid_effect_class_array(actual_effects)),
  ADD COLUMN IF NOT EXISTS rejected_by JSONB;

-- ============================================================
-- Authorize 层：ab_agent_authorization_decision（v1.3 从 contract 文档收回）
-- 单源治理：CREATE TABLE 只在 schemas/tables.sql；contract 文档引用列语义。
-- ============================================================
CREATE TABLE IF NOT EXISTS ab_agent_authorization_decision (
    id              BIGSERIAL PRIMARY KEY,
    pid             VARCHAR(26) UNIQUE NOT NULL,
    tenant_id       BIGINT NOT NULL,
    run_id          VARCHAR(26) NOT NULL,            -- ab_agent_run.pid
    step_index      INTEGER,
    tool_call_index INTEGER,
    decision_kind   VARCHAR(20) NOT NULL
        CHECK (decision_kind IN ('plan', 'incremental')),

    -- 主体信息（X = "agent wanted to do X"）
    tool_ref        VARCHAR(200),                    -- "dsl:cmd_crm_lead_transition"
    skill_code      VARCHAR(200),
    arg_hash        VARCHAR(64),                     -- v1.3: 改名 arg_digest → arg_hash（与 GrantScope 一致）
    blast_radius    VARCHAR(20)
        CHECK (blast_radius IS NULL OR blast_radius IN ('REVERSIBLE', 'SHARED_STATE', 'IRREVERSIBLE')),

    -- 请求与决策（CHECK 通过 valid_effect_class_array，详见上方 function）
    requested_effects JSONB NOT NULL
        CHECK (valid_effect_class_array(requested_effects)),
    granted_effects   JSONB
        CHECK (granted_effects IS NULL OR valid_effect_class_array(granted_effects)),
    rejected_effects  JSONB,                         -- {EffectClass: rejectedBy_source}（key 严格校验放 P2）

    -- Plan 关联
    plan_hash       VARCHAR(64),
    grant_scope     JSONB,                           -- 命中的 GrantScope 序列化

    -- 治理元数据
    policy_id       VARCHAR(26),
    policy_version  INTEGER,
    decision_reason TEXT,                            -- 人话解释

    -- Approval 链
    require_approval BOOLEAN DEFAULT FALSE,
    approval_id     VARCHAR(26),                     -- ab_agent_approval.pid

    -- PER_SESSION lifetime 缓存所需 scope key
    session_scope_key VARCHAR(200),                  -- {tenant}:{user}:{profile}:{channel_session}

    decision_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_authz_run            ON ab_agent_authorization_decision(run_id);
CREATE INDEX idx_authz_approval       ON ab_agent_authorization_decision(approval_id)
  WHERE approval_id IS NOT NULL;
CREATE INDEX idx_authz_tool_ref       ON ab_agent_authorization_decision(tenant_id, tool_ref, decision_at DESC)
  WHERE tool_ref IS NOT NULL;
CREATE INDEX idx_authz_session_scope  ON ab_agent_authorization_decision(session_scope_key)
  WHERE session_scope_key IS NOT NULL;
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

> **[v1.2 修正]** 原 v1 的步骤顺序与依赖方向写错，且漏了：(a) Tier 0 索引文件更新；(b) Patch A 拆为 Tier 0 contract + 叙述补丁；(c) `ab_agent_skill.declared_effects` ALTER；(d) JSONB CHECK 约束；(e) 文件计数错（v1 写"6 docs"又写"8 文件 = 1 删 + 3 新 + 4 改"，实际不一致）。
>
> 修正后：**11 文件**（1 删 + 3 新 + 7 改）。

| 步骤 | 阶段 | 动作 | 文件 |
|------|------|------|------|
| 0 | 验证 | **[v1.4 已完成 2026-04-26]** Q1 ✅ Q2 ✅ Q3 ⚠️ partial。决策：选项 A（ALTER ab_im_message）+ α 同步持久化 + 新增 step 5.5（AuraBotChatService 持久化改造）。详见 Patch A.2 step 0 验证结果段 | — |
| 5.5 | precondition（α 决策） | **[v1.4 新增]** 改造 `AuraBotChatService.doStreamChat` 入口同步落用户消息 + SSE 完成时同步落 agent 消息（独立短事务，不锁连接）。具体：`imMessageService.sendMessage(...)` 两次调用，分别在 stream 开始前 / 结束后 | 改 1（实现代码而非文档） |
| 1 | B.1 | **Write** `enterprise/docs/agent/contracts/effect-taxonomy.md`（新建 Tier 0 contract，定义 EffectClass 8 类 + 三层语义） | 新 |
| 2 | C | **Write** `enterprise/docs/agent/contracts/runtime-authorization.md`（新建 Tier 0 contract，依赖 effect-taxonomy 的 EffectClass + 含 ab_agent_authorization_decision DDL） | 新 |
| 3 | C 附加 | **Edit** `enterprise/docs/agent/contracts/runtime-core.md` 末尾加 "## 关联 Contracts" 节 | 改 |
| 4 | 索引 | **Edit** `enterprise/docs/agent/README.md` Tier 0 表格 + Tier 1 入口推荐 加入新 contract 行 | 改 |
| 5 | 索引 | **Edit** `enterprise/docs/agent/contracts/README.md` 加入 effect-taxonomy / runtime-authorization / pre-grounding-triage 三行 | 改 |
| 6 | A.0 + A.2 | **Write** `enterprise/docs/agent/contracts/pre-grounding-triage.md`（新建 Tier 0 contract）+ **Edit** `enterprise/docs/agent/schemas/tables.sql` 加 `ab_agent_conversation_message` 表/列 + triage CHECK | 1 新 + 1 改 |
| 7 | A.1 | **Edit** `enterprise/docs/agent/ACP-Ideal-Agent-Design.md` 插入 §6.1.2a 叙述（不含接口/DDL，仅引用 A.0） | 改 |
| 8 | B.2 | **Edit** `enterprise/docs/agent/design/skill-substrate-contract.md` §3.1 加 declared_effects YAML 字段 + §3.2 必填性表新增行 | 改 |
| 9 | B.3 | **Edit** `enterprise/docs/agent/specs/01-ActionContractSpec.md` § DDL ALTER 注释 + 新 §1.4 actual_effects 说明 | 改 |
| 10 | B.4 | **Edit** `enterprise/docs/agent/schemas/tables.sql` 末尾追加 valid_effect_class function + valid_effect_class_array + ab_agent_skill ALTER + ab_agent_action ALTER + ab_agent_authorization_decision CHECK | 改（与 step 6 同文件，可合并一次 Edit） |
| 11 | v4 删除 | `git rm auraboot/docs/plans/2026-04/2026-04-24-agent-control-plane-v4-design.md` | 删 |
| 12 | 验证 | 跑 `./scripts/check-docs-drift.sh`；非 0 必须修到 0 才算完 | — |

**计数**：1 删（step 11）+ 3 新（step 1, 2, 6 part 1）+ 7 改（step 3, 4, 5, 6 part 2, 7, 8, 9；step 10 与 step 6 part 2 合并）= **11 文件操作**。

> **依赖图**：
> ```
> step 1 (B.1) ─┬─→ step 2 (C) ─→ step 3 (C 附加)
>               ↓
>               └─→ step 6 (A.0 contract) → step 7 (A.1 叙述)
> step 1 (B.1) ─→ step 8 (B.2 YAML) ─→ step 10 (B.4 schemas)
> step 4/5 (索引) 在 step 1+2+6 后才能更新
> step 11 (v4 删) 与其他无依赖，但放最后避免 owner 中途反悔
> step 12 (drift audit) 必须最后
> ```

---

## 5. Hermes 路径对比 — 为什么 ACP 选了不同方向

回答常被问到的问题：既然 Hermes-agent 已经有成熟的 unified AIAgent loop / toolsets / execute_code / delegate_task / progressive skill disclosure，为什么 ACP 不直接照搬？

**根因**：Hermes 与 ACP 在解决不同问题。Hermes 优化**单用户高自主性 + 快速迭代**；ACP 优化**多租户企业治理 + 可审计性**。同一组原语放两个产品里，外围治理脚手架差一个数量级。

### 5.1 6 个根本性差异

#### 5.1.1 信任主体不同 — 这是根因

| | Hermes | ACP |
|---|---|---|
| 谁被信任 | LLM | 运行时 (Runtime) |
| 核心信念 | "给 LLM 强工具集，让它自己想办法" | "LLM 不能直接碰 DB，必须穿过受控运行时" |
| 后果 | Skill 是 hint (markdown)，工具是直接可调原语 | Skill 是合约 (schema + tests + owner)，写操作必须包装为 Action |

这一差异决定所有外围设计：BIF / Action Fidelity / EffectClass 三层 / RuntimeAuthorizationService —— 全部是"为不可信 LLM 建治理框架"。Hermes 不需要这套。

#### 5.1.2 用户结构不同

| | Hermes | ACP |
|---|---|---|
| 部署 | 单用户 CLI / 个人开发机 | 多租户 SaaS / 企业内部署 |
| Skill 作者 | 你自己 | 平台 + 50+ 插件作者 + 租户管理员 |
| Skill 审核 | "装之前 cat 一下 markdown" | 平台导入校验 + 租户审计 + 合规季度审查 |

`declared_effects` / `owner` / `tests` 在 Hermes 里冗余（你就是 owner，cat 就知道做什么），在 ACP 里生死线（管理员不可能挨个 cat 50 插件 × 30 skill）。

#### 5.1.3 Blast radius 不同

| | Hermes | ACP |
|---|---|---|
| 错误调用代价 | 浪费 token | 客户数据被改 / 错单发出 / 合规违规 |
| 可恢复性 | `Ctrl+C` 重跑 | Action 已 commit，需要 Saga 补偿 |
| 用户 awareness | 自己盯着 | 异步收到结果，事后审计 |

Hermes **不需要** Approval Gate / Action.fidelity / before/after snapshot —— 错了用户立刻看见。ACP **必须**有 —— 等发现已经晚了。

#### 5.1.4 语言到执行的距离不同

Hermes 典型请求 "fix this bug" → LLM 直接调 `read_file` / `edit_file` / `run_test`。**工具就是技术原语**，零语义距离，不需要 IR。

ACP 典型请求 "把张三的销售线索推进到 qualified" 必须经过 Object Resolver / Intent Parser / Risk Evaluator / Approval Policy 多层。**BIF 是企业语义的编译器 IR**。Hermes 没这个需求，所以没 BIF。

#### 5.1.5 优化目标部分对立

| 维度 | Hermes | ACP |
|------|--------|-----|
| 主追求 | 单 turn 能力密度 | 单 turn 可审计性 |
| Token 效率 | progressive skill disclosure | 同上 + plan-time effect pre-batch |
| 错误处理 | 模型自己重试 / Ctrl+C | Saga 补偿 + Approval timeout + INTERRUPTED |

ACP 的 Approval Gate 必然让 Hermes 风格的"快速迭代"变慢。但 ACP 把这些 gate **当成产品本身**（治理是企业版卖点），而不是 overhead。

#### 5.1.6 文化与失败容忍

| | Hermes | ACP |
|---|---|---|
| 文化 | "Move fast" — 开源社区，每用户主权 | "Move correctly" — 企业软件，租户管理员对 AI 行为负责 |
| 合规需求 | 无 | SOC2 / GDPR / 等保审计要求审计链 |

### 5.2 反过来：Hermes 有的，ACP 也学了（不是 Hermes 错了）

| Hermes 强项 | ACP 已学程度 |
|---|---|
| 统一 AIAgent loop（不分 coding/research 主循环） | ✅ 5 个内核服务收敛到一套 StepLoop |
| `execute_code` 程序化工具调用降 context 压力 | ⚠️ 部分（Sandbox），EXECUTE_CODE 在企业场景被严格限制 |
| `delegate_task` 子代理隔离 + max_spawn_depth | ✅ §6.10 Subagent Delegation 硬约束 |
| Skills 渐进式 disclosure (skills_list / skill_view) | ✅ §4.8 Skill Pack 三层 Filter |
| Interrupt Protocol (Hermes L122/L130) | ✅ §6.1.5 吸收为 v1.1.1 |

**ACP 拿走了 Hermes 的执行原语，但没拿走 Hermes 的"信任 LLM" 假设**。

### 5.3 对 3 个 patch 的意义

| Patch | 学 Hermes 吗？ | 为什么 |
|---|---|---|
| A: Pre-Grounding Triage | **不是 Hermes** | Hermes 没"轻链路"概念，本来就是单一 loop |
| B: Effect Taxonomy 三层 | **反 Hermes** | Hermes 不做 effect 治理，因为信任 LLM |
| C: RuntimeAuthorizationService | **反 Hermes** | Hermes 没 pre-flight authz，因为没多租户/合规需求 |

**恰好确认 patch 决定方向是对的**：不是"补完 Hermes 漏的"，是"为另一个问题（企业治理）增加该有的脚手架"。

如果硬把 ACP 改成 Hermes 风格 → 把企业版核心卖点（治理 / 审计 / 合规）扔了。
反之 Hermes 强加 ACP 这套 → 变臃肿，与目标用户（追求自主性的开发者）需求脱节。

### 5.4 一句话收尾

**Hermes 与 ACP 不是"谁更先进"，是"为不同问题选不同 trade-off"**。把任一方原样套到对方，都是 over-engineering 或 under-engineering。3 个 patch 是 ACP **该有但还没补上**的治理脚手架；而它们 Hermes **永远不会加**，因为 Hermes 不需要解决这个问题。

---

## 6. 待解决的开放问题（不阻塞执行，但应在 Phase 3-5 收口前回头看）

| # | 问题 | 建议 |
|---|------|------|
| 1 | Triage LLM 兜底用的"低成本模型"具体是哪个？ haiku-4.5 / 自定义 / 关闭？ | Phase 5 前定，与计费策略配套 |
| 2 | ~~`EffectLifetime.PER_SESSION` 在多 channel 同 session 生效范围？~~ | **[v1.2 已定]** scope key = `(tenant_id, user_id, profile_id, channel_session_id)` 4 元组；不允许只用 user_id 跨 channel/profile 命中。详见 Patch C 的 `EffectLifetime` 注释 + `ab_agent_authorization_decision.session_scope_key` 列 |
| 3 | `ab_agent_authorization_decision` 保留期？（合规可能要求 ≥3 年） | 与 `ab_agent_bif` retention 策略对齐 |
| 4 | Skill `declared_effects` 漂移告警的渠道（email / 站内通知 / Slack）？ | LearningLoop 完整化时定 |
| 5 | Capability Layer (P1) 进入时，`CapabilityProfile.declaredEffects` 与本方案的关系如何收敛？ | Phase 5 收口后单独评审 |

---

## 7. 决定者与时间线

- **设计 owner**：Y. Ghj (yaoyi.hz@gmail.com)
- **决定日期**：2026-04-24
- **执行触发**：owner 在对话中明确批准（"开干" 或等价表达）
- **完成判据**：§4 执行清单 step 0-12（共 13 编号 / 11 实际文件操作）全部完成，`check-docs-drift.sh` 返回 0；step 0 验证项（`ab_agent_conversation_message` 是否存在 / `ab_im_message` 三件验证）必须先 unblock 才能跑 step 6
- **后续 review 时点**：Phase 3 收口（约 2026-05 中），届时回看本方案与实际接入是否一致

---

## CHANGELOG

- 2026-04-24 v1 初始化（汇总 v1→v4 演进 + 4 个微观决定 + 3 个补丁完整内容）
- 2026-04-26 v1.1 增补 §5 Hermes 路径对比（6 个根本性差异 + 已学的 Hermes 强项 + 对 patch 决定的意义），原 §5/§6 顺移为 §6/§7
- 2026-04-26 v1.2 review fixes 合入：
  1. **依赖方向修正**：B.1（effect-taxonomy）必须先于 C（runtime-authorization）。原 v1 写反了
  2. **Patch A 拆分**：Tier 0 contract（`contracts/pre-grounding-triage.md`）+ schemas/tables.sql 真实 DDL + ACP-Ideal 叙述补丁三件，遵守 README §0 单源治理规则
  3. **授权粒度升级**：`PlanAuthorization.preApproved` 从 `Set<EffectClass>` 改为 `List<GrantScope>`（绑定 toolRefPattern / skillCodePattern / blastRadius / argDigest / planHash / policyId）
  4. **审计表加主体信息**：`ab_agent_authorization_decision` 新增 tool_ref / skill_code / arg_digest / blast_radius / plan_hash / grant_scope / policy_id / policy_version / decision_reason / session_scope_key 列
  5. **CHECK 约束落实**：新增 `valid_effect_class_array` 包装函数；ab_agent_skill / ab_agent_action / ab_agent_authorization_decision 的 effect JSONB 列加 CHECK
  6. **declared_effects 持久化**：必须落 `ab_agent_skill.declared_effects JSONB`（YAML 是源 + DB 是 runtime/audit 接口），原 v1 "不进 DB" 为错
  7. **PER_SESSION scope 定义**：4 元组 `(tenant, user, profile, channel_session)`，不再是开放问题
  8. **执行清单重排**：11 步（1 删 + 3 新 + 7 改），含 README/contracts 索引更新；原 v1 的 6 docs / 8 文件 / 1+3+4 计数三处不一致全部修正
- 2026-04-26 v1.3 二轮 review fixes 合入：
  1. **DDL 单源治理收口**：`ab_agent_authorization_decision` 完整 CREATE TABLE 从 `runtime-authorization.md` 移到 `schemas/tables.sql`（B.4），contract 文档只保留列语义说明
  2. **授权输入按 per-call 建模**：`PlanAuthorizationInput.declaredByPlan: Set<EffectClass>` 改为 `plannedCalls: List<PlannedCall>`，每个 call 携带 skillCode/toolRef/requiredEffects/blastRadius/argHashPattern/argPreview。原 v1.2 的 effect union 不足以生成 scoped grant
  3. **`ToolCallIntent` 补齐 GrantScope 匹配字段**：加 `skillCode / currentPlanHash / toolCallIndex`；原 v1.2 接口签名无法实现自己定义的匹配规则
  4. **argDigest 拆为 argHash + argPreview**：hash 用于 GrantScope 匹配 + DDL 持久化；preview 用于 ApprovalGate UI 渲染。两者语义不同不能共用字段名
  5. **`ExecutionContext` 加 channelSessionId**：PER_SESSION scope key 4 元组的最后一片，runtime-core.md 必须同步 ALTER；允许 null（webhook 等无 session 入口降级为 PER_TURN）
  6. **Triage message 表选型转 blocker**：原计划新建 `ab_agent_conversation_message` 与现有 `ab_im_message` 冲突。改为执行清单 step 0 必查项，初步倾向复用 `ab_im_message`，需先验证 3 件事
  7. **declared_effects 两阶段必填**：Phase 1 宽限期（importer 默认补 `[]`、validator 不 reject、DB 列 nullable + backfill）；Phase 2 严格期（≥30 天后 owner 决定切换到 NOT NULL + reject）。原 v1.2 直接必填破坏 additive 叙述
  8. **依赖说明全文一致**：Patch B 小节"依赖 Patch C"措辞修正为 "B.1 → C → B.4"，与 §2/§4 对齐
  9. **完成判据更新**：§7 时间线 "8 步" 改 "step 0-12 共 13 编号 / 11 实际文件操作"，并显式标注 step 0 是 step 6 的 blocker
- 2026-04-26 v1.4 step 0 验证完成 + α 决策落地：
  1. **step 0 三项验证完成**：Q1 ✅ Q2 ✅ Q3 ⚠️ partial。`ab_im_message` schema 完全支持 agent 类型 + 租户隔离，但 `AuraBotChatService.doStreamChat`（直连 SSE）当前不持久化消息
  2. **选项 A 通过**：复用 `ab_im_message` ALTER triage 字段，不再新建 ab_agent_conversation_message；γ 完全关闭
  3. **持久化策略选 α（同步）**：性能开销实测仅 2-10ms/turn（< 1% 总 turn 时间），数据一致性优于 β；β 关闭
  4. **新增 step 5.5**：执行清单加 precondition "改造 AuraBotChatService 加同步消息持久化"，必须在 step 6 ALTER ab_im_message 前完成，否则 triage 字段加了也没数据
  5. **α 硬约束**：INSERT 用独立短事务，禁止 DB 连接持有到 SSE 结束
