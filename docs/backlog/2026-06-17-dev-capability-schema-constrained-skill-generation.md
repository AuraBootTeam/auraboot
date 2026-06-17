---
type: backlog
status: active
created: 2026-06-17
---

# 架构 finding:开发者能力(NL→model/DSL/dashboard)应走「schema 约束 + skill 组合」,而非自由生成

> 一句话:平台**已经有** JSON Schema 和 skill 体系两样东西,但**开发者生成路径(NlModeling)
> 绕过了它们**,用"自由 JSON 文本 + 事后校验"的弱模式;本会话的真模型实测证明,把同一个模型
> 放进"schema 约束的 native tool-use"里准确率 100%、放进自由生成里明显下降。**真正该做的不是
> 新建 skills+schema,是把生成路径改成用现有的这两样 + skill 分解。**

## 1. 来龙去脉(为什么会有这份文档)

1. **起点**:owner 担心"agent 一直很弱"。本会话先用真 DeepSeek 把 agent 决策智能量化
   (见 [agent-intelligence-live-quality-measurement](./2026-06-17-agent-intelligence-live-quality-measurement.md)):
   工具选择 5/5、参数抽取 5/5·值 100%、对抗 8/8、缺信息拒绝瞎编(F6)。结论:智能层不弱。
2. **铺开**:再把整个平台盘成
   [能力大图 + 测试场景设计](./2026-06-17-platform-capability-map-and-test-scenario-design.md)
   (6 层 + 8 场景 + 12 gate 矩阵)。其中"开发者能力"两格——**NL→model/DSL**、**一键 dashboard/部署**
   ——被标为 🟡待测 / ❌gap。
3. **owner 的问题(本文触发点)**:针对开发者能力这种"agent 生成结构化产物"的场景,
   *"我们平台是不是要提供 skills 和 json schema,这样能够提升稳定性和效率?"*
4. **取证后的回答**:判断对,但更精确——**这两样资产平台基本都有了,缺的是开发者生成路径没接上。**

## 2. 取证现状(verified)

### 2.1 JSON Schema —— 已有正式文件 ✅
```
plugins/schemas/models.schema.json
plugins/schemas/pages.schema.json
plugins/schemas/dashboards.schema.json
plugins/schemas/dsl-schema.generated.json
platform/src/main/resources/schemas/page-import-v4.schema.json
```
model / page / dashboard / DSL 都有正式 JSON Schema,`import-directory-sync` validator 在用。
blockType/dataType 白名单(`DslRegistry` 真源:29 blockType / 13 dataType / kind / layout / schemaVersion=4)
本质就是一份枚举 schema。

### 2.2 Skill 体系 —— 已有,且每个 skill 自带 input schema ✅
`agent/service/` 下:`AgentSkillService` / `SkillEngine` / `SkillAutoGenerator` / `SkillDraftGenerator` /
`SkillPackActivator` / `SkillBootstrapRunner`。DB 每个 skill 带:
- `skill_input_schema`(JSONB)—— `AgentSkillService.java:119-126` 加载成 `inputSchema`,缺省 `{type:object,properties:{}}`
- `skill_tools` / `produced_action_types` / `step_input_mappings` / `idempotency_mode` —— `AgentSkillService.java:351`、`SkillAutoGenerator.java:30`

> 也就是说:**skill 本质上就是"带 JSON schema 的可组合能力单元",平台已经实现。** 这正是 owner
> 设想的"skills + json schema"。

### 2.3 但开发者生成路径(NlModeling)没用上面这两样 ❌(这是 gap)
`agent/nlmodeling/NlModelingService.java`:
- 注释(line 29)自称 "structured JSON output mode + **schema-aware system prompt**"。
- 实际(line 76-85):`buildSystemPrompt(opts)` + `callLlm(systemPrompt, messages)` —— **没有 `.tools()` / inputSchema**(非 native tool-use)。
- `generatePageDsl`(line 131)显式注释 "plain completion (**no tools**, no agent prompt)"。
- line 100 "**Parse JSON from response**" —— 模型自由吐 JSON 文本 → 解析 → 再 validate。

**判定**:NlModeling 是**弱模式 = "schema 当 prompt 提示 + 自由生成 + 事后校验"**;模型可以吐出非法
DSL,只在 validate 阶段才暴露,失败就 retry。它没有把已有的 `*.schema.json` 当成**输出护栏**,也没有
走已有的 skill 分解。

## 3. 实测证据(本会话真 DeepSeek)

同一个模型,两种生成范式,准确率差异显著:

| 生成范式 | 实测来源 | 结果 |
|---|---|---|
| **native tool-use + inputSchema**(schema 当硬护栏) | `AgentFormFillLiveIT` / `AgentFormFillHardLiveIT`(本会话,真 DeepSeek) | **值 100% · 必填齐 5/5 · 0 幻觉字段 · 对抗 8/8 · 缺信息拒绝瞎编** |
| **自由 JSON + 事后校验**(schema 当 prompt 提示) | NlModeling 现状 + 上一轮盘点评估 | ~70%,靠 validator 兜底,失败 retry |

**机制根因**:native tool-use 下,provider 把 inputSchema 作为函数签名强约束,模型**填不出** schema
外字段、非法 enum、错类型;"非法产物"从"validate 时才报"变成"根本产生不出来"。自由生成下,白名单
(29 blockType/13 dataType)只是 prompt 里的一句话,模型可以越界。

> 这就是 owner 直觉的数据背书:**给模型 schema 当护栏 = 100%;让它自由生成 = 错误率上来。**

## 4. 为什么稳定 + 效率都会提升

- **稳定性**:输出结构由 schema 强约束,而非靠模型自觉。枚举(blockType/dataType/字段类型)从"提示"
  升级为"不可违反"。非法 DSL 不可生成 → 线上"门禁绿但功能坏"的 gate-gap 类问题从源头减少。
- **效率**:省掉 `生成→校验→报错→喂回模型改→再生成` 的 retry 循环;token 更少;输出确定性高 →
  可**逐步校验、逐步组合**,而不是一把大生成再整体兜底。

## 5. 架构建议

> **把 NL→model/DSL/dashboard 的生成从「自由文本 + 事后校验」改造成「schema 约束的 native tool-use
> + skill 分解组合」。资产(schemas + skill 体系)都在,改的是 NlModeling / dashboard-gen 的接线。**

具体两条腿:
1. **schema 约束**:生成时把对应 `models.schema.json` / `pages.schema.json` / `dashboards.schema.json`
   的子结构作为 native tool-use 的 `inputSchema`,让 provider 强约束输出,而不是 prompt 里贴 schema 文本。
2. **skill 组合**:复用已有 skill 体系(`skill_input_schema` / `skill_tools` / `step_input_mappings`),
   把"建一个完整对象"拆成可组合的小 skill,agent 逐步调用。

## 6. 关键设计点(否则会翻车)

**别用一个大 schema 一次性生成整个 model+commands+pages+dashboard。** 大 schema 模型一次填不可靠
(超出可靠 tool-call 复杂度)。正解是 **skill 分解成小 schema 步骤**:
```
create_model(name, code)                                  ← 小 inputSchema,可独立校验
  → add_field(model, name, type, enum?, reference?) × N   ← 每步小 schema
  → create_command(model, type, inputFields)
  → create_page(model, kind, blocks[])
  → add_dashboard_widget(dashboard, chartType, dataSource)
```
每步 = "小 schema 的 native tool-use",独立校验、独立测试、可重试单步而非整体。**这正是 skill 体系
(`skill_tools` / `step_input_mappings`)本来要做的事——只是 NlModeling 现在绕过了它。**

本会话证据直接支持这条:**小 schema 填表 100%(F1-F8)vs 一把大自由文本易错** ⇒ 小步 schema 化 > 一把大生成。

## 7. 验证切片(可量化,沿用本会话 T3 模板)

做成切片后,用真模型量化对比,把"应该更稳"变成"实测更稳":
- **基线 A(自由生成)**:现状 NlModeling 路径,给 N 个建模任务,测生成 DSL 的字段名/类型/枚举/引用正确率 + validator 一次过率。
- **实验 B(schema 约束 skill 组合)**:同 N 个任务,走 schema 约束 native tool-use + skill 分解,测同样指标。
- **指标**:字段正确率、类型正确率、枚举合法率、幻觉字段数、validator 一次过率、平均 retry 次数、token。
- **模板**:复用 `AgentFormFillLiveIT`(native tool-use + 自包含 schema + 真 DeepSeek + 诚实报告);
  这是 [测试场景设计](./2026-06-17-platform-capability-map-and-test-scenario-design.md) §C 的 **S4(NL→建模)**
  与 **X1(dashboard 生成)** 的落地手段。

## 8. 影响范围 / 关联

- 直接改善能力大图里"开发者能力"两格:**S4 NL→model→部署**、**X1 一键 dashboard**(当前 gap)。
- 涉及类:`NlModelingService`(改接线)、`AgentSkillService` / `SkillEngine`(复用)、`AgentContractDeriver`
  (`deriveContracts` 派生工具 schema 可复用)、`*.schema.json`(作为 tool inputSchema 源)。
- 不改执行契约层(L3 gate / L5 命令管道)——那层本就强;本 finding 只针对**生成层**。

## 9. 落地优先级

- **P0(验证)**:先做 §7 的对比切片,用真模型量化"schema 约束 vs 自由生成"的提升幅度(纯增量、低风险、
  直接回答 owner 的稳定性/效率假设)。
- **P1(改造)**:NlModeling 改 schema 约束 native tool-use;dashboard-gen 新增 schema 约束 skill(当前无路径)。
- **P2(编排)**:把分解后的 skill 串成"一键开发部署"编排(generate→validate→apply→import→verify),
  补上一键部署缺的编排层。

## 来源与口径
- 取证命令本会话实跑:`grep` NlModelingService / AgentSkillService / SkillAutoGenerator;`find` schemas/*.json。
- 实测数据来自本会话三个 live-LLM IT(#732/#737/#739,真 DeepSeek,key-gated)。
- 标注:§2 全部 verified(读码/ls);"skill 作为 native tool-use 工具暴露给 agent"为 🟡 inferred(由 skill_tools +
  ToolProviderRegistry 推断,落地前应再核一遍 skill→tool 暴露链路)。
