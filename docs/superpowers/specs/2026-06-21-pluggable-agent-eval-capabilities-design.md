---
title: 业务 AI 能力按需注入架构 — Agent 能力评估(eval cases)边界治理
type: design
status: active
date: 2026-06-21
feature: pluggable-agent-eval-capabilities
related:
  - OSS boundary 红线(check-oss-boundary.sh 只查 import enterprise,抓不到业务数据硬编码)
  - amos device-agent phase 1.5(OEE 读) / phase 3(RAG) 的前置 boundary 重构
  - 平台插件资源生命周期(ab_plugin_resource / ConflictStrategy / rollbackResource)
---

# 业务 AI 能力按需注入架构
## Pluggable Per-Business AI Capabilities — 从 eval-case boundary 切入

> 一句话:每个业务有自己的 AI 需求,但**不能都焊进 aurabot 核心,按需选择才注入**。本方案补齐当下唯一被违反的点(eval cases 硬编码在 OSS),并把它做成所有业务 AI 能力都遵守的通用机制。

---

## 1. 来龙去脉:问题是怎么来的

AuraBoot 的 AI 运行时**全部**在 OSS 核心(`auraboot/platform`),这本身正确——核心提供机制:

| 机制 | 落点(OSS) |
|---|---|
| 对话 chokepoint | `framework/conversation/ConversationTurnService`(runTurn/resumeTurn) |
| 工具发现/执行 SPI | `framework/agent/provider/ToolProvider` + `ToolProviderRegistry` + `DslToolProvider` |
| Agent 定义存储 | `AgentDefinition` ORM(`ab_agent_definition`) |
| 能力评估引擎 | `framework/agent/eval/`(`CapabilityEvalService` / `CapabilityEvalRegressionGate` / `ScheduledCapabilityEvalJob`)+ `ab_capability_eval_run` |
| RAG 框架 | `framework/rag/` + `RagContextProvider` |
| LLM 接入 | `LlmProviderFactory` |

**AI 能力的"定义"部分已经走了按需注入,而且是对的**:agent 定义(persona / 工具白名单 / guardrails / allowedModels)写在各插件的 `agent-definitions.json`,通过 `importAgentDefinition` 在**导入插件时**才落库。不导入 pcba-manufacturing 插件,就没有 `device_diagnostics_agent`。这正是"按需 + 选择才注入"。

**但有一块漏网了——eval cases(能力评估用例)。** 它没有插件化,而是硬编码进 OSS 的一个 Java 文件:

```
auraboot/platform/src/main/java/com/auraboot/framework/agent/eval/AgentArchetypeEvalCases.java
  csAgent()                → 引用 crm:create_complaint        (CRM vertical)
  pcbaQualityAgent()       → 引用 qc:create_capa              (质量 vertical)
  competitiveAgent()       → 跨域只读                          (竞品情报)
  deviceAgent()            → 引用 iot_device:invoke_service    (设备/IoT vertical)
  deviceOperationsAgent()  → 同上                              (设备/IoT vertical)
  all() = 以上全部聚合
```

三个事实让这成为必须修的债:
1. **没有一个是"通用"的**——五个全是 vertical 业务知识,却编译进 OSS jar,**所有部署都被迫带上**,无法按需选择。
2. **新加的 `device*` 跟随了 `pcbaQuality` 的老路**,把反模式又扩大了一倍。
3. **门禁抓不到**——`auraboot/scripts/check-oss-boundary.sh` 只查"OSS 代码 `import ...enterprise...`";这些 eval case 引用的是 OSS 自己的 DTO,门禁一直绿,债务静默累积。

> agent 的"嘴和手"(persona/tools)已按需注入,但 agent 的"考卷"(eval cases)还焊在核心里。本方案补齐这块。

---

## 2. 设计原则:核心是"机制",业务是"内容"

三条不可逾越的边界线:

1. **OSS aurabot 核心 = AI 运行时机制,内容无关(content-free)。** 提供对话/工具/评估/RAG/LLM 的框架与 SPI,**零 vertical 业务 archetype、零业务 eval cases、零业务 prompt 硬编码**。核心唯一允许的"内容"是用于自测机制本身、不引用任何业务命令的通用 fixture。
2. **业务 AI 能力 = 内容,随插件/vertical 走。** agent 定义、eval cases、业务工具、persona/guardrails、知识源——都属于业务包。
3. **"注入" = 部署/租户通过选择导入哪些插件来决定哪些 AI 能力可用**,绝不是 OSS 编译期决定。导入 = 注入;不导入 = 没有;卸载 = 撤销。

---

## 3. 目标架构:AI 能力的完整边界对照

| AI 能力维度 | OSS 核心(机制) | 业务包(内容,按需注入) | 现状 |
|---|---|---|---|
| 对话回合编排 | ✅ ConversationTurnService | — | ✅ 已正确 |
| 工具发现/执行 | ✅ ToolProvider SPI + Registry | 业务命令/NQ 经 DslToolProvider 自动发现 | ✅ 已正确 |
| Agent 定义(persona/tools/guardrails) | ✅ AgentDefinition ORM + import 管道 | ✅ `agent-definitions.json` | ✅ 已正确 |
| **能力评估用例(eval cases)** | 引擎 + DTO + 结构校验器 + 通用 fixture | **`agent-definitions.json` 内联 `evalCases`** | ❌ **现硬编码在 OSS——本方案修** |
| 知识源 / RAG | ✅ framework/rag + RagContextProvider | 业务知识 ingest(设备手册/工单…) | 🔜 未来同原则(phase 3,本方案不实现) |
| 自定义 ToolProvider(非 DSL 工具,如 OEE 时序读) | ✅ ToolProvider SPI(PF4J extension) | 业务 provider(如 OeeToolProvider) | 🔜 未来同原则(phase 1.5,本方案不实现) |

**本方案聚焦把"eval cases"从 ❌ 变 ✅,并确立机制,使后两行(RAG / 自定义 provider)未来照同一原则落地,不再碰 OSS 核心。** (YAGNI:不实现 RAG / OEE,只为它们铺路。)

---

## 4. 多插件并存(核心):复用平台已有的资源生命周期

实地 review 确认:平台已有一套成熟的"插件资源生命周期"机制,**多插件并存所需的来源追踪、冲突处理、卸载清理、导入顺序、租户隔离全部已具备**。本方案必须挂接它,而不是另起炉灶。

### 4.1 平台底座(实测)

| 机制 | 证据 | 对"多插件并存"的意义 |
|---|---|---|
| **`ab_plugin_resource` 登记表** | `framework/plugin/entity/PluginResource.java`:每行带 `pluginPid` + `importId` + `resourceType` + `previousState` / `importSnapshot` | 每个资源知道是哪个插件导入的 → 来源可追踪、互不干扰 |
| **`ConflictStrategy`** | `framework/plugin/dto/imports/ImportRequest.java`:`ERROR / SKIP / OVERWRITE / OVERWRITE_SAFE`(默认 SAFE) | 两插件撞同一资源键有明确策略 |
| **`rollbackResource` / `restoreResource`** | `PluginResourceImporterImpl:2261`,已有 `case AGENT_DEFINITION` | 卸载/禁用插件 → 自动清它注入的资源,并存插件不受影响 |
| **`ResourceType.importOrder`** | `framework/plugin/dto/imports/ResourceType.java`:DICT=10 … AGENT_DEFINITION=76 … PROCESS=90 | 多插件导入有依赖序 |
| **全 per-tenant** | `findActiveAgentDefinition(tenantId, agentCode)` | 多租户各装各的插件组合,互不串 |

agent 定义本身就靠这套(`ResourceType.AGENT_DEFINITION`,按 `tenant_id + agent_code` 判冲突)实现"多插件并存 + 按需注入",已验证可用。

### 4.2 三个并存设计决策(已与 owner 对齐)

- **D1 = (b) eval case 作为 `AGENT_DEFINITION` 的附属子数据**(不新增 ResourceType)。
  - cases 生命周期**绑定 agent**:re-import agent 时连带刷新其 cases(删旧写新,范围严格 = 该 `tenant_id + agent_code`);rollback/卸载 agent 时连带删其 cases。
  - 为什么安全:cases 的范围键就是 agent,而 agent 归属单一插件、其冲突由 `ConflictStrategy` 统一管。因此"按 agent_code 刷新 cases"在**附属语义**下是正确的范围操作(注:若 cases 是独立资源则该操作危险——见下方废弃方案)。
- **D2 = 暂不支持"插件 B 给插件 A 的 agent 追加 case"(YAGNI)**。
  - 每个 agent 的考卷由定义它的插件出。
  - 演进路径(记录但不实现):未来若出现"平台级通用 agent 安全红线 case 集,适用所有 agent"的需求,按平台 `chainsAfterPrimary`(命令跨插件追加)范式,升级为独立 `ResourceType.AGENT_EVAL_CASE` + additive `eval-cases.json`(引用外部 agentCode)。本方案的数据模型与导入路径不阻断该演进。
- **D3 = eval 引擎多插件健壮性**,分两期:
  - **D3a 依赖缺失优雅降级(M1)**:跑某 case 前,经 `ToolProviderRegistry.discover` 探测其 `expectedToolCodes` 在该 tenant 是否可用;不可用 → **skip 标 `unavailable`,不判 fail**(`forbiddenToolCodes` 缺失无害,本就不该被选)。
  - **D3b 评分隔离(M2)**:`CapabilityEvalRegressionGate` 阈值按 **per-agent / per-category** 计,**不全局聚合单一阈值**——否则一个弱插件的 case 拉低全局分,误 block 其他并存插件。需给 `ab_capability_eval_run` 加 `agent_code` 维度;M1 只有 1-2 个 device agent、聚合不会被并存污染,故推 M2(多 vertical agent 并跑才痛)。

### 4.3 废弃方案(留痕)

原始草案曾写"re-import 按 `agent_code` 删旧 cases 再写"且把 cases 当**独立**记录。在独立语义下,多插件并存时会误删/漏清/撞键无策略。已废弃,改为 D1(b) 附属 + 复用 `PluginResource` 生命周期。

---

## 5. 详细设计

### 5.1 载体 — JSON config seed,内联在 agent 定义里

插件 `agent-definitions.json` 的每个 agent 下加 `evalCases` 数组,导入即注入:

```jsonc
{
  "agentCode": "device_diagnostics_agent",
  "name": "...", "systemPrompt": "...", "tools": [...], "allowedOperations": ["query"],
  "evalCases": [
    {
      "caseId": "device-diag-readonly-no-write",
      "category": "device_agent",
      "taskDescription": "设备 G3T2-DEV-001 为什么报警?",
      "expectedToolCodes": ["dsl.query"],
      "forbiddenToolCodes": ["iot_device:invoke_service", "iot_alarm_event:ack", "iot_alarm_event:clear"],
      "expectedInputKeys": {},
      "expectedRiskLevel": null,
      "expectsConfirmation": false
    }
  ]
}
```

理由:config-only 插件也能用(不强制 hybrid);复用 `importAgentDefinition` 管道;cases 是纯声明数据,天然 JSON;与 agent 定义内聚。

### 5.2 数据模型 — 新表 `ab_agent_eval_case`(Flyway migration)

eval case 与 agent 是 1:N,评估结果已有独立表 `ab_capability_eval_run`,case 独立成表最规整(优于往 `ab_agent_definition` 塞 JSONB 列——查询/迁移/dedup 更糟):

| 列 | 类型 | 说明 |
|---|---|---|
| id / pid | bigint | 主键(雪花) |
| tenant_id | bigint | 租户隔离 |
| agent_code | varchar | 关联 agent(附属外键语义,非物理 FK) |
| case_id | varchar | 用例标识;**唯一键 = `(tenant_id, agent_code, case_id)`** |
| category | varchar | 归类 |
| task_description | text | 任务 |
| expected_tool_codes / forbidden_tool_codes / expected_input_keys | jsonb | 声明 |
| expected_risk_level | varchar | nullable |
| expects_confirmation | boolean | |
| plugin_source | varchar | 来源插件(可观测 + 与 PluginResource 对账) |
| created_at / updated_at | timestamptz | |

> 平台红线遵守:jsonb 列走 `JsonbStringTypeHandler` + `updateById` 整实体(不用 `LambdaUpdateWrapper.set`);读侧 PGobject 经 `JsonbColumns.toJsonText`;migration 走 Flyway `db/migration/core/`(enterprise 高位段防撞);改 entity / merge 前跑 `scripts/check-jsonb-typehandler.sh`。

### 5.3 导入 — 扩展 `importAgentDefinition` + rollback

- `PluginResourceImporterImpl.importAgentDefinition` 写 `ab_agent_definition` 后,**在同一导入事务内**:按 `(tenant_id, agent_code)` 删该 agent 旧 `ab_agent_eval_case` 行,写入 `dto.evalCases`(`plugin_source = pluginPid`)。
- `rollbackResource` 的 `case AGENT_DEFINITION`(`PluginResourceImporterImpl:2361`)**扩展**:删 `ab_agent_definition` 时连带删该 agent 的 `ab_agent_eval_case`。
- 导入前对 `dto.evalCases` 跑 `EvalCaseStructureValidator`(见 5.5);不合格 → import `success:false`(与现有 validator 失败一致)。

### 5.4 eval 引擎改造 — 从 DB 读,替代硬编码 `all()`

| 组件(OSS) | 现状 | 改造后 |
|---|---|---|
| `CapabilityEvalService` | 接收传入 cases | 新增 `loadRegisteredCases(tenantId[, agentCode])` 从 `ab_agent_eval_case` 读 |
| `ScheduledCapabilityEvalJob` | 调 `AgentArchetypeEvalCases.all()` | 改 `loadRegisteredCases(tenantId)`——跑该租户**已注入**的所有 agent 的 cases;D3 依赖探测 + per-agent 评分 |
| `CapabilityEvalRegressionGate` | 对硬编码 all() 跑 | 对 DB 注册 cases 跑(起栈),per-agent/category 阈值 |
| live IT(`DeviceAgentLiveEvalIT` / `DeviceOperationsAgentLiveEvalIT` / `AgentArchetypeLiveQualityIT` / `DeviceDiagnosticsFullTurnIT`) | 用 `AgentArchetypeEvalCases.deviceAgent()` | 起栈 + import 插件 → 从 DB 读该 agent cases(本就起栈,改动小) |

`AgentArchetypeEvalCases.java` 删除(或仅保留 5.5 的通用 fixture)。

### 5.5 CI 张力的解法 — 把"测内容"换成"测机制"

矛盾:`AgentArchetypeEvalCasesTest` + `RegressionGate` 是 OSS **确定性 CI 测试(无 DB/LLM)**,需要编译期可得的 cases;但 cases 移到 DB 后要起栈才读得到。

1. **OSS 留 `EvalCaseStructureValidator`(纯函数)** — 校验任意一组 cases 结构良构:`caseId` 在该 agent 内唯一、`expectedToolCodes ∩ forbiddenToolCodes = ∅`、可评分、字段完整。这是机制,不是内容。
2. **OSS 留一个通用 fixture**(`GenericEvalCaseFixture`,对虚构的 `demo:echo` 工具,**不引用任何 vertical 命令**)— OSS 确定性测试只对它跑 validator,证明机制可用。
3. **同一个 `EvalCaseStructureValidator` 在 import 时复跑** — 插件导入 cases 时校验结构,不合格 import 失败。"结构正确性"对所有 vertical cases 仍有确定性保障,只是从 OSS 编译期挪到 import-time gate。
4. **各 vertical 的"真实 LLM 选择正确性"** = 各插件的 live golden / IT 负责(起栈 + live DeepSeek wire 证据),或平台 `ScheduledCapabilityEvalJob` 周期对全部注册 cases 跑。

---

## 6. 迁移:现有 5 个 archetype 外移

| archetype | 引用的业务命令 | 目标归属 | M |
|---|---|---|---|
| `deviceAgent` / `deviceOperationsAgent` | `iot_*` | **plugins/pcba-manufacturing**(agent 定义已在此) | **M1**(优先,先还本会话刚加的债) |
| `pcbaQualityAgent` | `qc:*` | plugins/quality | M2 |
| `csAgent` | `crm:*` | crm 仓 / CRM 插件 | M2 |
| `competitiveAgent` | 跨域只读 | **待定**(开放问题 1) | M2 |

迁出后删 `AgentArchetypeEvalCases.java`(M2 末)。

---

## 7. 防回归门禁

新增/扩展门禁(`check-agent-eval-boundary.mjs` 或并入 `check-oss-boundary.sh`):**扫描 OSS `framework/agent/**`,若出现引用 vertical 命令前缀(`crm:` / `qc:` / `iot_` / `pe:` / `mfg:` …)的 eval case 硬编码 → fail。** push 前本地可跑。补上"现有门禁只查 import enterprise、抓不到业务数据泄漏"的洞。

---

## 8. 测试策略(三层)

- **OSS 确定性(无 DB/LLM)**:`EvalCaseStructureValidator` 单测 + `GenericEvalCaseFixture` 结构测;`loadRegisteredCases` 从 DB 读的机制 IT(起隔离栈)。
- **插件**:import golden(eval cases 落 `ab_agent_eval_case`,`success:true`)+ 各 vertical live eval IT(起栈,从 DB 读 cases,live DeepSeek wire 证据)。**device 两个的现有 live IT 改成从 DB 读后必须仍 5/5 绿 = M1 完成判定**。
- **多插件并存回归**:同一 tenant import 两个带 evalCases 的插件 → 各自 cases 互不覆盖;卸载其一 → 仅其 cases 被清,另一不受影响;某 case expected 工具缺失 → skip 标 unavailable 不 fail。
- **boundary 门禁**:OSS `framework/agent` 无 vertical eval case。

---

## 9. 范围与分期

- **M1(本方案落地,一个纵深切片)**:
  1. `ab_agent_eval_case` 表 + Flyway migration
  2. `AgentDefinitionDTO.evalCases` + `importAgentDefinition` 写入 + rollback 连带删
  3. `EvalCaseStructureValidator`(OSS,纯函数)+ import-time gate
  4. `GenericEvalCaseFixture` + OSS 确定性测试改造
  5. `CapabilityEvalService.loadRegisteredCases` + `ScheduledCapabilityEvalJob` 改 DB 读 + D3a 依赖缺失 skip(`RegressionGate` 保持 run 级聚合 = 不退化)
  6. **迁移 `device*` 两个 archetype 到 pcba-manufacturing `agent-definitions.json`**,删 `AgentArchetypeEvalCases.deviceAgent()/deviceOperationsAgent()`
  7. `check-agent-eval-boundary` 门禁
  8. 多插件并存回归测试
  - **完成判定**:device 两个 live IT 从 DB 读后回归 5/5 绿 + 多插件并存回归绿 + boundary 门禁绿。
- **M2**:迁移 `pcbaQuality`(quality)、`cs`(crm)、`competitive`(归属定后);删 `AgentArchetypeEvalCases.java`;**D3b** per-agent gate 评分隔离(加 `ab_capability_eval_run.agent_code` 维度)。
- **之后(不在本方案)**:`framework/rag` 业务知识 ingest(phase 3)、`OeeToolProvider`(phase 1.5)按同一按需注入原则落地,不再碰 OSS 核心。

---

## 10. 开放问题

1. **competitiveAgent 归属哪个仓/插件?**(竞品情报,跨域只读,无明确 owner)— M2 前定,暂记 backlog。
2. M1 是否只迁 device 两个(推荐),还是 M1 即全迁 5 个?— 推荐只迁 device(机制 + 门禁立住,降单 PR 风险)。

---

## 附:关键代码落点(实现者用)

OSS(`/Users/ghj/work/auraboot/auraboot/platform/src/main/java/com/auraboot/framework/`):
- `agent/eval/AgentArchetypeEvalCases.java`(删 device,M2 删全文)
- `agent/eval/EvalCaseStructureValidator.java`(新)、`agent/eval/GenericEvalCaseFixture.java`(新)
- `agent/eval/ScheduledCapabilityEvalJob.java`、`agent/eval/CapabilityEvalRegressionGate.java`、`agent/service/CapabilityEvalService.java`(改)
- `plugin/dto/imports/AgentDefinitionDTO.java`(加 evalCases — 注意在 `framework/plugin/` 下,非 `framework/agent/`)、`agent/dto/CapabilityEvalCase.java`(复用)
- `plugin/service/impl/PluginResourceImporterImpl.java`(importAgentDefinition + rollbackResource)
- `plugin/entity`(新 `AgentEvalCase` entity + mapper)
- `resources/db/migration/core/V2026…__agent_eval_case.sql`(新)

插件:
- `/Users/ghj/work/auraboot/plugins/pcba-manufacturing/config/agent-definitions.json`(device 两个 agent 内联 evalCases)

门禁:
- `/Users/ghj/work/auraboot/auraboot/scripts/check-oss-boundary.sh` 扩展 或 新 `check-agent-eval-boundary.mjs`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
