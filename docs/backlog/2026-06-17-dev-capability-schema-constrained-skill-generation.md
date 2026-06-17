---
type: backlog
status: active
created: 2026-06-17
---

# 架构 finding:开发者能力(NL→model/DSL/dashboard)应走「schema 约束 + skill 组合」,而非自由生成

> 一句话(**已按实测修正**):平台**已经有** JSON Schema 和 skill 体系,开发者生成路径
> (NlModeling)目前走"自由 JSON + 事后校验"绕过了它们。**初稿曾据此推断自由生成会"明显下降",
> 但本会话真测证伪了**:NlModeling 自由生成在 clean(5/5)和 hard 类型推断(9/9)上都接近满分、
> 0 非法类型、0 校验错。所以 **schema 约束的优势从"修复准确率"降级为"边际健壮性保证 + 多步可
> 组合性",rewrite 不再紧迫**;真正值得投入的是**没有路径的 X1 一句话生成 dashboard**(build gap),
> 不是重写一个已实测可用的 NlModeling。详见 §3 修正、§9。

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

**判定(已按 §3 实测修正)**:NlModeling 在**机制上**是 "schema 当 prompt 提示 + 自由生成 + 事后校验"
——**理论上**模型可以吐出非法 DSL、只在 validate 才暴露。但 **§3 实测显示 DeepSeek 实际没有越界**
(0 非法类型、0 校验错),所以这只是**理论弱点而非实测问题**。它确实没把 `*.schema.json` 当输出护栏、
也没走 skill 分解,但**这在当前模型/任务规模下未造成实际质量损失**——是"可以更稳",不是"现在坏了"。

## 3. 实测证据(本会话真 DeepSeek)

> **⚠️ 自我修正(2026-06-17,实测后)**:本节初稿把自由生成估为"~70% 弱",那是**未测的拍脑袋**。
> 随后用 `NlModelingLiveQualityIT` 真测 NlModeling 自由生成路径,**被证伪**:它实际很强。下表已用实测替换。

| 生成范式 | 实测来源 | 结果 |
|---|---|---|
| **native tool-use + inputSchema**(schema 当硬护栏) | `AgentFormFillLiveIT` / `AgentFormFillHardLiveIT` | 值 100% · 必填齐 5/5 · 0 幻觉字段 · 对抗 8/8 · 缺信息拒绝瞎编 |
| **自由 JSON + 事后校验**(NlModeling 现状) | `NlModelingLiveQualityIT`(真测) | **clean 5/5 · hard 类型推断 9/9 · 0 非法 dataType · 0 服务校验错**(模型自己推断 金额→decimal/附件→file/状态→enum) |

**修正后的结论**:两种范式**准确率都高,实测无显著差异**。自由生成虽**理论上**能吐非法 DSL
(白名单只是 prompt 提示),但**实测 DeepSeek 没有越界**(0 非法类型、0 校验错)。所以
**schema 约束相对自由生成的优势从"修复准确率 gap"降级为"边际的健壮性保证 + 多步可组合性"**
(见 §5 修正)。

> **修正**:schema 约束实测 100%;但自由生成(NlModeling)**也**实测接近满分(§3),不是"错误率上来"。
> schema 约束的真实价值是**结构保证**(从"实测可用"到"不可能非法")+ **多步可组合**,而非"修复一个现存的准确率 gap"。

## 4. schema 约束的真实价值(已按实测收窄)

> ⚠️ 初稿把"稳定 + 效率"写成"自由生成现在坏、schema 约束来修";**实测后收窄**为以下边际/结构性收益:

- **结构保证(边际)**:输出由 schema 强约束 → 非法 DSL **不可能生成**(从"实测 0 错"升级到"结构上 0 错")。
  当前 DeepSeek 实测没越界,所以这是"更稳的保证"而非"修复现存问题";换更弱模型 / 更大对象时价值上升。
- **多步可组合(结构性)**:skill 分解让大生成拆成可独立校验/重试/测试的小步——这条价值**独立于准确率**,
  对"一键部署编排"(§9 P1)和 dashboard 生成(§9 P0,无现成路径)有用。
- **效率**:理论上省 retry,但实测 `validationErrors=0`(无 retry 发生),所以**当前规模下效率收益≈0**;
  仅在大对象 / 弱模型导致 retry 时才显现。

## 5. 架构建议(已收窄)

> ⚠️ **不再建议"优先重写 NlModeling"**——它实测可用(§3)。建议改为:**新增能力(dashboard 生成、
> 一键部署编排)直接用 schema 约束 skill 一步到位**(避免重蹈自由生成);**已可用的 NlModeling 暂不动**,
> 大规模对抗扩量验证后再定是否升级(§9 P2-可选)。下面的"两条腿"是**新增能力的实现范式**,不是改造令。

> 范式:**把 NL→dashboard / 一键部署 的生成做成「schema 约束的 native tool-use + skill 分解组合」。
> 资产(schemas + skill 体系)都在。**

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

## 9. 落地优先级(**已按实测重排**)

- ~~P0 验证 / P1 改造 NlModeling~~ —— **§7 的验证已做(`NlModelingLiveQualityIT`),结果证伪了"自由生成弱"
  的前提**(clean 5/5 + hard 9/9 + 0 非法 + 0 校验错)。**结论:NlModeling 自由生成已实测可用,
  schema 约束 rewrite 降级为 P2-可选(边际健壮性,非紧急)。**
- **P0(真 gap)**:**X1 一句话生成 dashboard —— 当前无生产 agent 路径(build gap),不是"弱"是"没有"**。
  这是开发者能力里唯一的真空白,该优先补;补的时候直接走 schema 约束 skill(`dashboards.schema.json` 作 inputSchema),
  一步到位、不重蹈自由生成。
- **P1**:NL→一键部署编排层(generate→validate→apply→import→verify 自动串),补一键部署缺的编排。
- **P2-可选**:NlModeling 改 schema 约束 native tool-use —— 把"实测可用"升级成"结构保证可用"(边际),
  以及更难/更大对象(20+ 字段、嵌套、跨模型引用)的对抗扩量再验,确认大规模下是否仍稳。

## 来源与口径
- 取证命令本会话实跑:`grep` NlModelingService / AgentSkillService / SkillAutoGenerator;`find` schemas/*.json。
- 实测数据:native tool-use 路径 `AgentFormFillLiveIT`/`AgentFormFillHardLiveIT`(#737/#739);
  自由生成路径 `NlModelingLiveQualityIT`(本批次,真 DeepSeek,key-gated)。
- **诚实修正记录**:§1/§3/§9 的"自由生成弱(~70%)"是初稿未测推断,经 `NlModelingLiveQualityIT` 证伪后已修正
  ——这是"取证不推断"的应用:测量推翻了作者自己的假设。
- 标注:§2 全部 verified(读码/ls);"skill 作为 native tool-use 工具暴露给 agent"为 🟡 inferred(由 skill_tools +
  ToolProviderRegistry 推断,落地前应再核一遍 skill→tool 暴露链路)。
