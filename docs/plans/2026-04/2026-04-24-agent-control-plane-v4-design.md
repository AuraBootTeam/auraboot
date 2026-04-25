# Agent Control Plane v4 设计稿

**状态**：design proposal（未实现，待评审）
**演进路径**：v1（初版分层）→ v2（renamed `platform_agent` → `agent_execution`，引入 ExecutorMode 四分）→ v3（单 capability + tool allowlist）→ **v4（effect system + 4 接口 + loop-back）**
**关联子系统**：
- `docs/system-reference/subsystems/aurabot/*`（现状：`ChatToolResolver` 单点过载）
- `docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md`（L1/L2 写回时机）
- `docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md`（ApprovalRequest.surfacedTo 通道）

---

## 1. 设计动机

### 1.1 现状问题

| 现状 | 后果 |
|------|------|
| `ChatToolResolver` 同时承担 grounding / 工具发现 / 工具授权 / prompt 注入 | 单点过载，4 件事互相耦合，任一改动牵动全部 |
| 默认平台工具无条件注入 prompt | 简单问题被迫付重链路成本，token 爆炸 |
| 双 grounding 重复探测（plan-time + call-time 各做一次同样的 schema lookup） | 浪费 + 状态不一致 |
| 无 effect system，授权按 tool 粒度白名单 | O(tools × capabilities) 复杂度，新插件每次都要重审 |
| Skill 是 prompt 片段，无 owner/test/schema | 隐形业务逻辑，无法治理 |
| `platform_agent` 名字误导团队，以为只处理平台 SQL | 重链路加 coding/PPT/research 时，命名冲突 |

### 1.2 目标

让 agent 内核在**同一套统一执行循环**下承载差异极大的任务（trivial chat / 字段释义 / 跨域 PPT / 撤销动作 / coding / research），并满足 4 个硬性约束：

1. **顶层路由不表达领域**——领域差异下沉到 capability / effect / executor / skill
2. **授权按 effect 粒度**——不按 tool 白名单
3. **企业门禁单点**——EffectPolicy 是与 RouteDecision 同级的一等接口
4. **执行循环可增量授权**——loop-back 必须 first-class，不能强制每 iter 全链路重跑

### 1.3 非目标（out of scope）

- 模型选型策略（opus vs sonnet vs haiku）
- Prompt cache 命中率优化
- AuraBot UI / 对话气泡渲染
- 计费（per-effect billing 是 effect system 的副产品，不是本设计要解决的）
- 多模态（图片/语音输入输出）

---

## 2. 五条核心原则

| # | 原则 | 反例（现状） | 正例（v4） |
|---|------|-------------|------------|
| 1 | **Route 只决定轻重 + 风险** | route 里塞领域分支（`coding_route` / `ppt_route`） | `direct_chat` / `contextual_chat` / `agent_execution` 三档 |
| 2 | **Capability 决定领域** | 领域散布在 prompt / tool selector / route | `CapabilityProfile` 编译出 {tools, skills, executors, approval} |
| 3 | **EffectPolicy 决定权限** | 权限检查散布在 50 处 if-else | 单一 `EffectPolicyService`，plan + incremental 两个方法 |
| 4 | **Executor 决定运行形态** | 所有任务都走"模型 → tool → 模型"循环 | 4 种 mode：LLM_ONLY / TOOL_LOOP / EXECUTE_CODE / DELEGATE_SUBAGENT |
| 5 | **Skill 决定工作流** | skill 是 prompt 片段，无 schema | versioned package：metadata + instructions + effects + I/O schema + tests + owner |

---

## 3. 总体架构图

```text
┌──────────────────────────────────────────────────────────────────┐
│ EDGE                                                              │
│   HTTP/WS · auth · tenantId · traceId · idempotencyKey            │
│   light short-circuit: 明确无需 context/tools 的请求, 直出 LLM     │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ ROUTE LAYER                                                       │
│   direct_chat       (light, 无 tools, 无 context)                 │
│   contextual_chat   (medium, 有 page/record context, 只读 tools≤1) │
│   agent_execution   (heavy, 完整链路)                              │
│   产出: RouteDecision (含 RiskClass/ContextNeed/ToolPolicy/...)    │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ CONTEXT LAYER                                                     │
│   conversation 装配 · user/role/perm 快照 · tenant/locale         │
│   memory 召回 (L1 + L2 + UserSoulProfile)                          │
│   产出: ConversationContext (immutable for the turn)              │
└──────────────────────────────────────────────────────────────────┘
                                │
                       (heavy path only)
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ GROUNDING SERVICE (横切, 三个时机均可调)                           │
│   scope = CAPABILITY_PLAN | TOOL_INVOCATION | SKILL_LOAD          │
│   plan-time 已 ground 的 probe 不重复, runtime 增量 ground         │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ CAPABILITY PLANNER                                                │
│   产出: primary CapabilityProfile + delegatable[] (跨域留给子代理) │
│   每个 profile 编译出: {allowedTools, candidateSkills,             │
│                          executorModes, declaredEffects}           │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ EFFECT POLICY SERVICE                                             │
│   evaluatePlan(input) → EffectPolicyDecision                      │
│     - 8 个 effect 类 (READ_CONTEXT / READ_PLATFORM_DATA / ...)    │
│     - EffectBundle (atomic) 支持原子组合                           │
│     - 5 个输入源合成 (user/tenant/capability/route/runtime)        │
│     - "最严者胜" + rejectedBy 字段必填                             │
│   authorizeIncremental(cursor, newRequest) → EffectAuthorization  │
│     - runtime loop-back 单点, 复用 plan-time 已批的 effect         │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ SKILL LOAD POLICY                                                 │
│   inject skill index (轻量, ≤200 token/skill)                     │
│   on-demand skill_view → 完整 SkillPackage                         │
│   skill 声明 requires {tools, effects} → 受 EffectPolicy 二次校验   │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ EXECUTOR SELECTOR                                                 │
│   mode ∈ {LLM_ONLY, TOOL_LOOP, EXECUTE_CODE, DELEGATE_SUBAGENT}   │
│   双闸: capability allowlist (硬约束) + 模型主动声明 (软约束)       │
│   产出: ExecutorPlan (含 LoopGuard / SpawnDepth / ExecuteCodePolicy) │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ PROMPT ASSEMBLY                                                   │
│   纯装配层, 输入 = (route, context, capability, effect, skill,     │
│                     executor, runtimeHints), 不偷做决策            │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ EXECUTOR LOOP (Hermes-style 统一循环, 不区分领域)                  │
│                                                                    │
│   while !done && budget.ok() && !cancelled:                       │
│     msg = model.complete(promptLayers, surface)                   │
│     for tc in msg.tool_calls:                                     │
│       auth = EffectPolicy.authorizeIncremental(cursor, tc.effects) │
│       if !auth.ok: emit RejectionEvent, break                     │
│       if auth.requireApproval:                                    │
│         emit ApprovalRequest{resumeToken}                         │
│         persist state, return 202                                 │
│         (later) UI confirms → resume                              │
│       result = ToolRouter.dispatch(tc)                            │
│       Trace.append(LoopEvent.toolCall)                            │
│       history.append(result)                                       │
│     if msg.terminal: done = True                                  │
│                                                                    │
│   loop 内可触发的 4 个原语:                                         │
│     - tool_call (capability allowlist + effect authz)              │
│     - load_skill(name) (progressive disclosure + effect authz)     │
│     - delegate_subagent (新 capability scope, 计入父 budget)        │
│     - request_approval (ACP gate)                                  │
└──────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│ EXECUTION TRACE / AUDIT / MEMORY WRITEBACK                         │
│   单一 traceId 贯穿, LoopEvent 是唯一时间线                          │
│   SessionEndedEvent (success / cancel / fail) 必发                 │
│   critical events 同步写 audit, 其他 async batch                   │
└──────────────────────────────────────────────────────────────────┘

═══ 横切层（不属于某一层私有） ════════════════════════════════════════
  Grounding Service       (CAPABILITY_PLAN / TOOL_INVOCATION / SKILL_LOAD)
  Approval Gate (ACP)     (irreversible blast radius 必经)
  Budget Governor         (envelope 沿调用栈传, subagent 共享父预算)
  Observability/Trace     (traceId 全链路)
  Memory Writer           (SessionEndedEvent 三种 outcome 全发)
  Audit Log               (落 ab_audit_log)
```

---

## 4. 四个一等接口

### 4.1 RouteDecisionService

```java
public interface RouteDecisionService {
    RouteDecision decide(RouteDecisionInput input);
}

record RouteDecisionInput(
    String traceId,
    String tenantId,
    String userId,
    ChatMessage incomingMessage,
    ConversationHistoryDigest historyDigest,        // 摘要, 非全量
    Optional<PageContextRef> pageContext,
    Optional<RecordContextRef> recordContext,
    Map<String, Object> runtimeHints                // 灰度 / 调试开关
) {}

record RouteDecision(
    String routeId,                                  // direct_chat | contextual_chat | agent_execution
    double confidence,                               // [0,1], 用于灰度策略
    List<String> reasonCodes,                        // 可解释性
    RiskClass riskClass,                             // LOW | MEDIUM | HIGH
    ContextNeed contextNeed,                         // NONE | PAGE | RECORD | FULL
    ToolPolicy toolPolicy,                           // FORBIDDEN | READONLY_WHITELIST | CAPABILITY_GATED
    WritePolicy writePolicy,                         // FORBIDDEN | DRAFT_ONLY | STATE_WITH_APPROVAL
    GroundingNeed groundingNeed,                     // NONE | LIGHT | FULL
    BudgetEnvelope budget,
    List<String> hintedCapabilities                  // hint, planner 可推翻
) {}

enum RiskClass { LOW, MEDIUM, HIGH }
enum ContextNeed { NONE, PAGE, RECORD, FULL }
enum ToolPolicy { FORBIDDEN, READONLY_WHITELIST, CAPABILITY_GATED }
enum WritePolicy { FORBIDDEN, DRAFT_ONLY, STATE_WITH_APPROVAL }
enum GroundingNeed { NONE, LIGHT, FULL }
```

**不变式**：
- `stateless + idempotent`（同一 input 必出同一 output）
- 不调 LLM（决策可选用 LLM 辅助，但封装为 strategy，默认规则引擎）
- 失败必须 fallback 到 `direct_chat + RiskClass.HIGH`，永不静默升级

### 4.2 CapabilityPlanner

```java
public interface CapabilityPlanner {
    CapabilityPlanResult plan(CapabilityPlanInput input);
}

record CapabilityPlanInput(
    RouteDecision route,
    ConversationContext context,
    Optional<GroundingVerdict> planTimeGrounding,    // D1
    Map<String, Object> runtimeHints
) {}

record CapabilityPlanResult(
    CapabilityProfile primary,                       // 当前 turn 的主 capability
    List<CapabilityProfile> delegatable,             // 父 agent 可委派给子 agent 的
    List<String> rejectedCapabilities,
    Map<String, String> rejectionReasons,
    PromptFragment systemPromptFragment
) {}

record CapabilityProfile(
    String capabilityCode,                           // platform_data | platform_write | coding | presentation | research | document_workflow | ...
    Set<String> allowedToolNames,                    // 可见的工具名
    List<EffectClass> declaredEffects,               // 此 capability 声明会用的 effects (供 EffectPolicy 预判)
    Set<ExecutorMode> allowedExecutorModes,          // 硬约束
    ExecutorMode defaultMode,                        // 模型未声明时回落
    List<String> candidateSkillNames,                // 命名空间限定: "platform:dsl-query"
    ApprovalPolicy approvalPolicy,
    AuditPolicy auditPolicy,
    boolean canBeDelegated,                          // 是否允许作为子 agent 的 capability
    int loopGuardMaxIterations
) {}
```

**不变式**：
- `primary` 必须非空；`delegatable` 可为空
- `primary.canBeDelegated` 不强制为 false（父 agent 也可以是 delegatable capability，例如 research 父 agent 自己也是 research）
- 失败必须返回 reject + reason，不允许返回"默认 capability"兜底

### 4.3 EffectPolicyService（双方法）

```java
public interface EffectPolicyService {
    /** 计划期：评估整个 turn 预批的 effect 集合 */
    EffectPolicyDecision evaluatePlan(EffectPolicyInput input);

    /** 运行期：增量授权，复用 plan-time 已批的部分 */
    EffectAuthorization authorizeIncremental(
        ExecutionTraceCursor cursor,
        EffectRequest request
    );
}

record EffectPolicyInput(
    RouteDecision route,
    CapabilityProfile capability,
    PermissionSet userPermissions,
    TenantPolicy tenantPolicy,
    Optional<RuntimeContextDowngrade> runtimeDowngrade,   // 例如 "本页只读" 临时降权
    List<EffectBundle> proposedBundles                     // capability 声明的 atomic 组合
) {}

record EffectPolicyDecision(
    Set<EffectClass> preApproved,                          // turn 内自由使用, 不需再次询问
    Set<EffectClass> requiresApproval,                     // 用前必须 ApprovalGate
    Set<EffectClass> forbidden,
    Map<EffectClass, String> rejectedBy,                   // ★ 必填: 哪个输入源否决的
    List<EffectBundleAuthorization> bundleAuthorizations,  // atomic 组合的授权状态
    EffectLifetime defaultLifetime                         // 默认 PER_INVOCATION
) {}

record EffectRequest(
    String callId,
    EffectClass effect,
    Optional<String> bundleId,                             // 属于哪个 bundle (若有)
    BlastRadius blastRadius,                               // 用于 ApprovalGate 决策
    Map<String, Object> contextHints                       // 例如目标 record_id
) {}

record EffectAuthorization(
    boolean granted,
    boolean requireApproval,
    Optional<String> approvalRequestId,
    Optional<String> rejectedReason,
    Optional<String> rejectedBy
) {}

enum EffectClass {
    READ_CONTEXT,           // 读 page / record / conversation 上下文
    READ_PLATFORM_DATA,     // 读 model 数据 (DynamicController query)
    WRITE_DRAFT,            // 写草稿 / 暂存 (用户可见但未提交)
    WRITE_PLATFORM_STATE,   // 写 model 数据 (Command exec)
    EXTERNAL_NETWORK,       // 调外部 API (LLM 之外的)
    FILE_WRITE,             // 写文件系统 (artifact)
    TERMINAL_EXEC,          // 执行 shell / code
    SECRET_ACCESS           // 读密钥 / token
}

record EffectBundle(
    String bundleId,
    List<EffectClass> requiredEffects,
    boolean atomic,                                        // 全过 or 全拒
    Optional<RollbackSpec> rollback                        // atomic=true 时强制
) {}

enum EffectLifetime {
    PER_INVOCATION,         // 默认: 每次调用都要重新授权
    PER_BUNDLE,             // bundle 完成前持续有效
    PER_TURN,               // 单 turn 内有效 (慎用)
    PER_SESSION             // 整个 session (仅 LOW risk + 无写)
}

enum BlastRadius { REVERSIBLE, SHARED_STATE, IRREVERSIBLE }
```

**不变式**：
- `evaluatePlan` 与 `authorizeIncremental` 必须共享同一策略引擎（不允许两个方法走不同规则）
- `EffectPolicyDecision.rejectedBy` 字段任何被拒 effect 都必须填，否则整个决策无效
- 合成规则：**最严者胜（principle of least privilege）**，任一输入源拒绝即拒绝
- `EffectBundle(atomic=true)` 任一 effect 被拒，整个 bundle 必须拒，并触发 rollback 机制（若 bundle 已部分执行）
- `EffectLifetime.PER_SESSION` 仅允许 risk=LOW 且不含 WRITE_* / TERMINAL_EXEC / SECRET_ACCESS

### 4.4 ExecutorSelector

```java
public interface ExecutorSelector {
    ExecutorPlan select(ExecutorSelectInput input);
}

record ExecutorSelectInput(
    CapabilityProfile capability,
    EffectPolicyDecision effectPolicy,
    SkillLoadPlan skillLoadPlan,
    ToolEligibilityResult toolEligibility,
    BudgetEnvelope remainingBudget,
    Optional<ExecutorMode> modelDeclaredMode               // 模型主动声明
) {}

record ExecutorPlan(
    ExecutorMode mode,
    String selectionReason,
    int maxIterations,
    boolean allowParallelChildren,
    List<ChildCapabilitySpec> delegatableChildren,
    int maxSpawnDepth,                                      // 默认 3
    LoopGuardConfig loopGuard,
    Optional<ExecuteCodePolicy> executeCodePolicy           // 仅 mode=EXECUTE_CODE 时填
) {}

enum ExecutorMode {
    LLM_ONLY,                  // 纯回答 / 总结 / 解释, 无工具
    TOOL_LOOP,                 // 1~2 次工具调用, 每步模型看结果再判断
    EXECUTE_CODE,              // 3+ 次工具调用 / 循环 / 批处理 (脚本聚合)
    DELEGATE_SUBAGENT          // 上下文隔离 / 并行子任务 / 专职子角色
}

record ExecuteCodePolicy(
    Set<String> allowedToolsInScript,                       // 脚本只能调这些
    boolean approvalRequiredPerToolCall,                    // 默认 true, 不允许"打包审批"
    int maxScriptToolCalls,
    boolean networkEgressAllowed,                           // 默认 false
    boolean filesystemWriteAllowed,                         // 默认 false
    Duration scriptWallclockBudget
) {}
```

**ExecutorMode 选择规则（双闸）**：

1. **硬约束**：必须在 `capability.allowedExecutorModes` 内
2. **软选择**：模型主动声明 → 落入硬约束集合 → 接受；否则使用 `capability.defaultMode`
3. **运行时校验**：模型选 TOOL_LOOP 但实际跑到第 4 iter 还在调工具 → emit `executor_mode_violation`，强制 break，不允许自动升级到 EXECUTE_CODE（因 EXECUTE_CODE 有副作用风险）

**EXECUTE_CODE 第一版限制**：
- 仅在 `platform_data`（只读）+ `research`（只读）capability 下开启
- 强制 `networkEgressAllowed=false`、`filesystemWriteAllowed=false`、`approvalRequiredPerToolCall=true`
- 脚本本身存为 artifact，可重放可审计
- `platform_write` capability 永不允许 EXECUTE_CODE（写副作用必须经 ApprovalGate per-call）

---

## 5. EffectPolicy 详细规则

### 5.1 8 个 effect 类的语义边界

| Effect | 含义 | 默认风险 | 典型工具 |
|--------|------|----------|----------|
| `READ_CONTEXT` | 读当前 page / record / 对话上下文 | LOW | `page.describe`, `record.view` |
| `READ_PLATFORM_DATA` | 读 model 数据（DynamicController query） | LOW | `data.query`, `data.aggregate` |
| `WRITE_DRAFT` | 写草稿，用户可见但未提交（生成 artifact） | LOW | `draft.create`, `artifact.save` |
| `WRITE_PLATFORM_STATE` | 写 model 数据（Command exec, 持久化） | HIGH | `data.create`, `data.update`, `data.delete` |
| `EXTERNAL_NETWORK` | 调外部 API（LLM 调用之外） | MEDIUM | `web.fetch`, `mcp.call` |
| `FILE_WRITE` | 写文件系统（artifact 落盘） | MEDIUM | `file.write`, `ppt.render` |
| `TERMINAL_EXEC` | 执行 shell / code | HIGH | `shell.run`, `python.exec` |
| `SECRET_ACCESS` | 读密钥 / token | HIGH | `secret.read`, `oauth.refresh` |

### 5.2 5 个输入源 + 合成规则

| 输入源 | 来源 | 优先级 |
|--------|------|--------|
| 用户权限 | `permissions.json` + 角色 binding | 否决权 |
| 租户策略 | tenant-level override（譬如租户级关停 EXTERNAL_NETWORK） | 否决权 |
| Capability 默认策略 | `capability.declaredEffects` | 提议 |
| Route 默认策略 | `route.toolPolicy` + `route.writePolicy` | 提议 + 否决权 |
| Runtime context 临时降权 | 例如"本页只读模式" | 否决权 |

**合成算法**：

```
forEach effect e in proposedEffects:
    decisions = []
    for source in [user, tenant, capability, route, runtime]:
        d = source.evaluate(e)
        decisions.add(d)
    if any(d == REJECT for d in decisions):
        result[e] = REJECTED, rejectedBy = first(d.source where d == REJECT)
    elif any(d == REQUIRE_APPROVAL for d in decisions):
        result[e] = REQUIRES_APPROVAL
    else:
        result[e] = PRE_APPROVED
```

**rejectedBy 字段是治理强制**：
- 任一被拒 effect 必须给可审计的 rejectedBy
- UI 必须能展示为人话（"此操作被租户策略禁止"，而不是 `REJECT_CODE_42`）

### 5.3 EffectBundle 原子组合

**为什么需要**：真实操作经常需要原子组合
- "查数据后做草稿" = `[READ_PLATFORM_DATA, WRITE_DRAFT]`
- "记录审计后再写状态" = `[WRITE_AUDIT, WRITE_PLATFORM_STATE]`
- "撤销刚才的草稿" = `[READ_DRAFT_HISTORY, WRITE_PLATFORM_STATE]` + rollback

**Bundle 行为**：
- `atomic=true` 时：任一 effect 被拒 → 整个 bundle 被拒；若已部分执行 → 触发 rollback
- `rollback` 必填（atomic=true 时）：声明补偿动作（compensating action 或 transaction abort）
- bundle 在 `EffectPolicyDecision.bundleAuthorizations` 中追踪状态：`PROPOSED | AUTHORIZED | EXECUTING | COMPLETED | ROLLED_BACK`

### 5.4 Effect 生命周期

| Lifetime | 适用场景 | 续约规则 |
|----------|----------|----------|
| `PER_INVOCATION`（默认） | 所有 HIGH risk effect / 所有 WRITE_* / TERMINAL_EXEC / SECRET_ACCESS | 每次调用重新评估 + 必要时重新 ApprovalGate |
| `PER_BUNDLE` | bundle 内的 effect | bundle COMPLETED / ROLLED_BACK 时失效 |
| `PER_TURN` | 单 turn 多次调用同 effect 且 risk ≤ MEDIUM | turn 结束失效 |
| `PER_SESSION` | LOW risk + 只读 effect（READ_CONTEXT / READ_PLATFORM_DATA） | session 结束失效；session 内 capability 切换会触发重评估 |

**审计要求**：每次"复用已授权 effect"必须写一条 `effect_reuse` audit 记录，包含 `originalAuthorizationId`。否则审计无法追"用户实际产生了几次 WRITE_PLATFORM_STATE"。

---

## 6. SkillPackage 规范

### 6.1 结构

```yaml
# skills/platform/dsl-query/skill.yaml
metadata:
  name: "platform:dsl-query"
  version: "1.2.0"
  owner: "platform-team@example.com"
  description: "查询任意 model 数据, 支持 filter/sort/aggregate"
  triggerHints:
    - "查 / 统计 / 列出 / 多少 / 哪些"
    - "上个月 / 本季度 / 今年"

requires:
  tools:
    - "data.query"
    - "schema.lookup"
  effects:
    - READ_PLATFORM_DATA
    - READ_CONTEXT
  capabilities:
    - "platform_data"

input:
  schema:
    type: object
    properties:
      modelCode: { type: string, required: true }
      filters:   { type: array }
      groupBy:   { type: array }

output:
  schema:
    type: object
    properties:
      records:     { type: array }
      aggregate:   { type: object }
      totalCount:  { type: integer }

instructions: |
  1. schema.lookup({modelCode}) 确认字段存在
  2. data.query(...) 拉数据
  3. 若用户问"最近 N 天" → 自动加 createdAt 过滤
  4. 失败 → 返回结构化 error, 不重试

tests:
  - name: "查最近 7 天的订单"
    input: { modelCode: "order", filters: [{field: "createdAt", op: ">", value: "now-7d"}] }
    expectedShape:
      records: { minLength: 0 }
      totalCount: { type: integer }
```

### 6.2 progressive disclosure 流程

```
1. CapabilityPlanner 决定 candidateSkillNames (按 capability 限定)
2. SkillLoadPolicy 注入 skill index (仅 metadata, ≤200 token/skill)
3. 模型决定: skill 命中 → 调用 skills.view(name)
4. SkillLoadPolicy 二次校验: skill.requires.effects ⊆ EffectPolicy.preApproved
   - 不满足 → reject, 不静默 fallback
5. 注入完整 SkillPackage 到 prompt (本 turn 不再重复 view)
6. SkillLoadPlan.viewedSkills 强制去重: 同 turn 不允许重复 view 同一 skill
```

### 6.3 治理强制

- **owner 必填**：无 owner 的 skill 不允许导入
- **tests 必填**：skill 也是交付物，对应 AGENTS.md "测试即交付件"红线
- **版本号语义**：MAJOR 变更（schema 不兼容）必须新版本，旧版本保留兼容期 ≥ 30 天
- **命名空间**：`{plugin}:{skill}` 形式，多租户多插件无冲突

---

## 7. 四个 dry-run 场景

四种场景跑通 = 接口签名收敛。任何一种填不出 input/output → 接口未完成。

### 7.1 Scenario A: trivial chat（"今天天气如何"）

| 阶段 | 输入 | 输出 | 备注 |
|------|------|------|------|
| Edge | HTTP POST | classify → light | 无需 context/tools |
| Route | RouteDecisionInput{message: "今天天气如何"} | RouteDecision{routeId: direct_chat, riskClass: LOW, contextNeed: NONE, toolPolicy: FORBIDDEN, writePolicy: FORBIDDEN, groundingNeed: NONE} | short-circuit |
| Context | — | — | 跳过 |
| Capability | — | — | 跳过 |
| EffectPolicy | — | — | 跳过 |
| Executor | — | — | 跳过, 直走 LLM_ONLY |
| LLM | system: minimal | "我无法访问实时天气..." | |
| Trace | — | minimal trace, 无 audit | 不写 memory |

**验证**：light path 在 5 个组件内完成；不进 capability / effect / skill / executor。

### 7.2 Scenario B: 字段含义（"这个表单有哪些字段"）

| 阶段 | 输出 |
|------|------|
| Route | RouteDecision{routeId: contextual_chat, riskClass: LOW, contextNeed: PAGE, toolPolicy: READONLY_WHITELIST, writePolicy: FORBIDDEN, groundingNeed: LIGHT} |
| Context | ConversationContext{pageContext: {pageKey: "order_form", schema: {...}}, ...} |
| Grounding | scope=CAPABILITY_PLAN, probe=[{kind: schema, ref: "order_form"}], verdict=ok |
| Capability | primary=`context_explainer`, allowedTools=[schema.lookup, page.describe], declaredEffects=[READ_CONTEXT], allowedExecutorModes=[TOOL_LOOP, LLM_ONLY], maxIterations=2 |
| EffectPolicy | preApproved=[READ_CONTEXT], rejectedBy={} |
| SkillLoad | inject skill index 但本场景无命中 |
| Executor | mode=TOOL_LOOP, maxIterations=1 (硬上限, contextual_chat 限制) |
| Loop iter 1 | model.complete → tool_call: schema.lookup(order_form) → result → done |
| Trace | 1 LoopEvent.toolCall, 1 LoopEvent.modelResponse |

**验证**：contextual_chat 的只读 whitelist 工具能用；不被强制升级到 agent_execution。

### 7.3 Scenario C: 跨域 PPT（"查 Q3 销售数据做 PPT"）

| 阶段 | 输出 |
|------|------|
| Route | RouteDecision{routeId: agent_execution, riskClass: MEDIUM, contextNeed: FULL, toolPolicy: CAPABILITY_GATED, writePolicy: DRAFT_ONLY, groundingNeed: FULL, hintedCapabilities: [platform_data, presentation]} |
| Context | full context + memory L1+L2 |
| Grounding | probe=[{schema: sales}, {tool: ppt.render presence}], verdict=ok |
| Capability | primary=`platform_data` (查数), delegatable=[`presentation`] (做 PPT); 父 agent 跨 capability 编排 |
| EffectPolicy | preApproved=[READ_PLATFORM_DATA, READ_CONTEXT, WRITE_DRAFT, FILE_WRITE], requiresApproval=[], EffectBundle=[{id: "query-then-draft", required: [READ_PLATFORM_DATA, WRITE_DRAFT], atomic: false}] |
| SkillLoad | inject index, hint skill: "presentation:deck-from-data" |
| Executor | mode=TOOL_LOOP for parent, delegatableChildren=[{capability: presentation, mode: TOOL_LOOP}], maxSpawnDepth=2 |
| Loop iter 1 | parent: tool_call data.query(model: sales, filter: Q3) → 200 records |
| Loop iter 2 | parent: 决定 delegate → spawn subagent{capability: presentation, brief: "用这 200 条数据生成 Q3 销售 PPT", surface: [chart.render, ppt.template, file.write], budget: 父预算的 60%} |
| Sub iter 1 | subagent: skills.view("presentation:deck-from-data") → SkillPackage 注入 |
| Sub iter 2 | subagent: tool_call ppt.outline(...) → outline draft |
| Sub iter 3 | subagent: tool_call chart.render(...) ×N |
| Sub iter 4 | subagent: tool_call file.write(deck.pptx) → authorizeIncremental(FILE_WRITE) → granted (PER_BUNDLE 内) |
| Sub end | subagent: ResultContract{schema: {artifactId, deckUrl, slideCount}, citations: [...], confidence: 0.85} |
| Loop iter 3 | parent: 收到 subagent summary, 写 ChatMessage 反馈用户 |
| Trace | 父 trace + 子 trace 双向索引, child trace 不丢 |

**验证**：
- 跨域通过 subagent orchestration（不是 union surface）
- subagent budget 从父预算切出
- skill load 触发 effect 二次校验
- ResultContract 有 confidence 给父 agent retry 决策依据

### 7.4 Scenario D: 撤销动作（"撤销刚才的草稿"）—— 测 EffectBundle.rollback + authorizeIncremental

| 阶段 | 输出 |
|------|------|
| Route | RouteDecision{routeId: agent_execution, riskClass: HIGH, contextNeed: FULL, toolPolicy: CAPABILITY_GATED, writePolicy: STATE_WITH_APPROVAL, groundingNeed: FULL} |
| Context | full + memory + previous turn's ExecutionTrace cursor (定位"刚才的草稿" = traceId xyz) |
| Grounding | probe=[{kind: data_existence, ref: "draft#xyz"}, {kind: permission, ref: "draft.delete"}], verdict=ok |
| Capability | primary=`platform_write`, delegatable=[] |
| EffectPolicy | preApproved=[READ_PLATFORM_DATA], requiresApproval=[WRITE_PLATFORM_STATE], forbidden=[], EffectBundle=[{id: "rollback-draft-xyz", required: [READ_PLATFORM_DATA, WRITE_PLATFORM_STATE], atomic: true, rollback: {type: COMPENSATING_ACTION, refersTo: "trace://xyz"}}] |
| Executor | mode=TOOL_LOOP, allowedExecutorModes=[TOOL_LOOP] (platform_write 永不允许 EXECUTE_CODE) |
| Loop iter 1 | model 决定: tool_call draft.delete(id: xyz) |
| Loop iter 1 | EffectPolicy.authorizeIncremental(cursor, EffectRequest{effect: WRITE_PLATFORM_STATE, bundleId: "rollback-draft-xyz", blastRadius: SHARED_STATE}) |
| Loop iter 1 | → EffectAuthorization{granted: true, requireApproval: true, approvalRequestId: "ar-001"} |
| Loop iter 1 | emit ApprovalRequest{resumeToken, surfacedTo: USER, reason: "撤销草稿 #xyz 不可逆"} |
| Loop iter 1 | persist state, return 202 |
| (later) | UI 用户确认 → 携带 resumeToken 继续 |
| Loop iter 1 resume | execute draft.delete → success → bundle 状态: AUTHORIZED → EXECUTING → COMPLETED |
| Loop iter 2 | model.terminal → done |
| End | SessionEndedEvent(success), audit log: {bundleId, rollbackSpec, originalTraceId, deletedDraftId} |

**关键验证**：
- EffectBundle.atomic + rollback 真有用（若 draft.delete 失败 → bundle 状态 ROLLED_BACK，不留中间态）
- authorizeIncremental 与 evaluatePlan 共享策略引擎（不会出现 plan-time 允许、runtime 拒绝的不一致）
- async approval 通过 resumeToken 不阻塞连接
- audit 包含 originalTraceId，可双向溯源（撤销动作 ↔ 被撤销动作）

---

## 8. 横切层

### 8.1 Approval Gate (ACP)

- 触发条件：`EffectAuthorization.requireApproval=true`
- 模式：**async resume**（不 block 连接），通过 `resumeToken` + persisted loop state 恢复
- surfacedTo：`USER` / `PLATFORM_ADMIN`（接 AdminRoleInterceptor）/ `WORKFLOW`（BPM 流程审批）
- 超时策略：默认 24h 未确认 → 视为拒绝 + emit `approval_timeout`，触发 SessionEndedEvent(fail)

### 8.2 Budget Governor

- `BudgetEnvelope` 沿调用栈强制传递
- subagent 创建时**必须从父预算切出**（不允许 fresh allocation）
- soft breach: 继续执行 + 警告日志
- hard breach: break 当前 loop + 返回 partial outcome
- 维度：tokens / toolCalls / wallclockMs / costCents / spawnDepth

### 8.3 Observability / Trace

- 单一 traceId 贯穿 Edge → Tool / Subagent
- LoopEvent 是唯一时间线，所有层只往同一 trace 追加
- subagent trace 必须有 parentTraceId 索引（双向可查）
- critical events 同步写 audit；非 critical async batch flush

### 8.4 Memory Writeback

- SessionEndedEvent 三种 outcome 必发：`success / cancel / fail`
- `cancel`（用户主动取消）与 `fail`（系统错误）路径漏发是历史 bug 源（参见 memory: project_memory_l1l2_design）
- `cancel` 也写 L1（保留对话上下文）但不晋升 L2
- `fail` 仅写诊断信息到 audit，不污染 memory

### 8.5 Audit Log

- 所有 EffectAuthorization 决策写 audit（包括拒绝）
- bundle 全生命周期事件写 audit（PROPOSED / AUTHORIZED / EXECUTING / COMPLETED / ROLLED_BACK）
- skill load + skill execution 写 audit（含 SkillPackage version + owner）
- audit schema 复用 `ab_audit_log`，新增 `effect_class` / `bundle_id` / `subagent_trace_id` 三个索引列

---

## 9. Open Questions（待评审决策）

| # | 问题 | 选项 | 默认倾向 |
|---|------|------|---------|
| Q1 | EXECUTE_CODE 第二版是否开 platform_write capability？ | 否 / 仅在 atomic bundle 内 / 全开 | **否**（直到有强需求） |
| Q2 | Skill 版本兼容期具体多长？ | 30 天 / 90 天 / 一个大版本 | 90 天 |
| Q3 | `PER_SESSION` lifetime 是否允许 READ_PLATFORM_DATA？ | 是 / 否 | **是**（只读 + LOW risk） |
| Q4 | 父 agent 能否同时 delegate 多个 subagent 并发？ | 是 / 否 / 仅 research capability 允许 | 仅 research（先窄开） |
| Q5 | EffectBundle 跨 turn 是否允许？（譬如"先查数据，下个 turn 再写"） | 是 / 否 | **否**（bundle 必须 turn 内闭合） |
| Q6 | `executor_mode_violation` 时是否允许模型重新声明？ | 是（一次） / 否（直接 break） | 是（最多一次） |
| Q7 | 子 agent 是否允许写 memory？ | 是 / 否 / 只能写 session L1 | **否**（只有父 agent 写 L1/L2） |

---

## 10. 不在范围内（out of scope）

- **模型选型**：opus vs sonnet vs haiku 的路由是 `ExecutorPlan.modelHint` 之后的事，本设计不涉及
- **Prompt cache 命中率**：PromptPlan 的 `cacheable` 标记是输出，但具体 cache key 设计不在此处
- **多模态**：图片/语音/视频输入输出
- **AuraBot 前端 UI**：对话气泡、流式渲染、ApprovalRequest UI
- **计费**：per-effect billing 是 effect system 的副产品，但不是本设计要解决的
- **跨租户协作**：当前所有 capability/skill 假设单租户，跨租户在 OSS 不支持

---

## 11. 与现有 OSS / Enterprise 资产的对接

| 现有资产 | 对接方式 |
|----------|----------|
| `ChatToolResolver` | 拆为 4 个独立 service（ToolDiscovery / ToolEligibility / GroundingService / PromptAssembly），分别映射到 v4 各层 |
| `ApprovalRequest`（ACP Phase A+B 已落） | 直接复用，仅扩展 `surfacedTo` 与 `effectClass` 字段 |
| `SessionEndedEvent`（Memory L1→L2 已落） | 直接复用，cancel/fail 路径已修，本设计延续 |
| `AdminRoleInterceptor`（Plan C 已落） | EffectPolicy 的 `surfacedTo=PLATFORM_ADMIN` 走该拦截器 |
| `Page Schema V2`（kind/blocks/layout 已落） | presentation capability 输出契约直接走 V2 |
| `DSL Command catalog` | platform_write capability 的 tool surface 起点 |
| `ab_audit_log` | 新增 3 个索引列：`effect_class`, `bundle_id`, `subagent_trace_id` |

---

## 12. 评审通过的判据

本设计稿评审通过的硬条件：

1. **4 个 dry-run 场景全部能在接口签名内填出完整 input/output**（任何一个填不出 → 接口未收敛 → 改设计）
2. **每个被拒 effect 都能给出 rejectedBy 字段的可解释来源**（无法解释 → 输入源合成规则有漏洞）
3. **EffectBundle.atomic=true 的 rollback 路径可手工演示**（口头描述不算 → 必须能跑通 Scenario D）
4. **subagent trace 与父 trace 双向可索引**（仅父→子或仅子→父都不算 → audit 形同虚设）
5. **Open Questions 至少 Q1/Q5/Q7 三个治理性问题给出明确决策**（其余可延后）

满足全部 5 项 → 进入 Phase 1 实现（接口骨架 + RouteDecisionService 单接口落地）。
