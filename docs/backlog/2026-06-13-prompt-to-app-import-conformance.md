---
type: backlog
status: active
created: 2026-06-13
---

# Prompt-to-App 做实 — 生成 DSL 导入一致性后处理(Seam 5)

> 让 `NlModelingService` 真 LLM 生成的 DSL 可靠通过严格 import,无需外挂修复层。2 号护城河 / MD-3 in-designer AI 副驾的前置基础。

## 实测驱动(§15,纠正 stale gap 文档)
旧 gap 文档列「prompt 改 V4 + 大小写/pageKey/dicts 后处理」为待办。**真栈实测**(真 DeepSeek,`/api/agent/nl-modeling/generate`→`/apply`,DB `aura_boot_auraqr`)发现:
- ✅ **已实现**:`lowercaseStringKey`(command.type / field.dataType)、`deriveDynamicMenuPageKeys`、dicts 透传——gap 文档过时。
- 🔴 **真缺口**(import 真错):
  1. `models[0] requires at least one field binding in modelFieldBindings` —— LLM 常**不生成 modelFieldBindings**(`bindings:[]`),后处理也没从 fields 合成 → entity model 无字段绑定,import 拒。
  2. `Field 'status' references missing dictionary: equipment_status` —— LLM 生成 enum 字段引用一个**它没定义的 dict**(`dicts:null`)→ import 拒。

## 修复(`NlModelingService.buildPluginManifestJson`,确定性后处理)
- `synthesizeBindings(models, fields, bindings)`:bindings 空且**单 model** 时,为每个 field 合成 `{modelCode, fieldCode, sequence, required(来自 constraints), visible, editable}`;多 model 不瞎猜(field→model 无 hint)、保留原 bindings + log。
- `downgradeOrphanEnumFields(fields, dicts)`:enum 字段的 `dictCode` 不在 `dicts` 中 → 降级 `dataType=string` + 去 `dictCode`(安全确定性,LLM 没给 enum values 无法合成 dict)。

## 验证
- **单测** `NlModelingManifestPostProcessingTest` 8/8(原 4 + 新 4:合成 bindings / 降级 orphan enum / 保留已有 bindings / 多 model 不瞎猜 / 保留有 dict 的 enum)。
- **真 LLM golden**(真栈,真 DeepSeek key):
  - 修复前:generate(model+5 fields)→ apply **400 FAILED**(上面两个错)。
  - 修复后:同 generate → apply **200 success:true status:SUCCESS**,`resourceCounts: {MODEL:1, FIELD:5, MODEL_FIELD_BINDING:5}`(5 个 binding 自动合成落库 + status enum 降 string)。

## 残留(非本切片)
- **生成质量**:本轮弱模型(deepseek-chat→v4-flash)只生成 model+fields,`pages=0 commands=0`(D5 用 v4-pro 得全量)。pages/commands 生成质量 = prompt/model 调优,独立项。
- **permissions 合成 + grant**:生成 model 无 permissions → dynamic CRUD 403。合成 `dynamic.<model>.read/manage` + 授权是完成 generate→apply→CRUD 全链的下一步(同后处理模式)。
- **MD-3 in-designer AI 副驾**:复用本 generate/refine(现 import-ready)→ 限定到设计器当前面片段。
- ⚠️ DeepSeek key:本验证用 key(已轮换提醒);`aura_boot_auraqr` CloudConfig 仍存旧 D5 leaked key,须清。
