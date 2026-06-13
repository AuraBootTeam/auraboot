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

## 续做切片 — 默认 pages/menus 合成(生成的应用有 UI,本切片)
取证发现:弱模型只生成 model+fields(`pages=0 menus=0`)→ 生成的应用**只有 dynamic API、没有可点的页面/导航**。本切片让后处理合成默认 list+form 页 + 导航菜单,生成的应用开箱即用、侧边栏可达。

- 🔴 **真缺口 + 真错**(`/apply` 真栈逐个暴露,非推断):
  1. `pages[*]: layout is required. Page JSON must use the latest V2 flat format with top-level kind/layout/blocks` —— 最初按 prompt few-shot(**嵌套 areas 格式**)合成页面被 validator 拒。**few-shot 本身是过时格式**(LLM 历来 `pages=0` 从没暴露)。对照真生产 golden 页 `crm-quick-start/config/pages/tcrm_lead_{list,form}.json` 才知真 V2 flat:顶层 `kind/schemaVersion:4/modelCode/title/layout:{type:stack}/blocks[]`,`blocks` 扁平、每块带 `blockType`(toolbar/table/form-section/form-buttons)+ `area`。
  2. `Model '<code>' has missing modelType` —— 弱模型可能漏 `modelType`。
  3. `[S-PAGE-LABEL] ... missing a business label at columns[*].label` —— 列标签由字段 `displayName` 解析;字段无 business displayName(或 displayName==code/含 `_`)→ 列头无标签被拒。
- **修复**(`buildPluginManifestJson` 续加确定性后处理):
  - `synthesizePages(plugin, models, fields, pages)`:pages 空且**单 model** 时合成 list+form 页(真 V2 flat,镜像 tcrm 生产 golden 结构);多 model 不瞎猜 + log。`listPage` = toolbar(create 按钮)+ table(每字段一列 + actions 列 edit/delete);`formPage` = form-section(required 字段标 `required:true` 满足 S-PAGE-FORM-REQUIRED)+ form-buttons(submit→`<plugin>:create_<model>` / cancel)。
  - `synthesizeMenus(models, menus)`:menus 空且单 model 时合成 `{path:/dynamic/<kebab>}` → `deriveDynamicMenuPageKeys` 派生 `pageKey=<model>_list`。
  - `conformModels`:漏 `modelType` 默认 `entity`。
  - `conformFieldLabels` + `humanize`:字段无 business label → 合成 `displayName:en/zh-CN = humanize(code)`(`unit_price`→`Unit Price`),解析列头/表单标签。
  - few-shot 的 pages 同步改成真 V2 flat(避免强模型跟着生成被拒的嵌套格式)。
- **验证**(本切片):
  - **单测** `NlModelingManifestPostProcessingTest` 15/15(+ list/form 页结构 + menu pageKey + conformModels + conformFieldLabels + humanize)。
  - **真栈 apply golden**(零 docker host-first,backend :6600 / DB `aura_boot_auraqr`):
    - hand-crafted resources(model+2 fields,无 pages)→ apply **200** `{MODEL:1, FIELD:2, MODEL_FIELD_BINDING:2, COMMAND:3, PAGE:2, MENU:1}`。
    - **真 LLM**(真 DeepSeek):"customer visit log" → generate(model+4 fields,`pages=0 menus=0 commands=0`)→ 后处理 → apply **200** `{PAGE:2, MENU:1, COMMAND:3, ...}` = **NL_TO_APP_WITH_UI_OK**。
    - **后端联动 golden**:合成命令 `customer_visit_log:create_visit_log` 执行 **200** → 行落 `mt_visit_log`(字段值正确)→ dynamic `/api/dynamic/visit_log/list` **200 total=1**(list 页数据源)。
    - **可达 / 可渲染**:`/api/pages/key/visit_log_list` 返回正确装配的页面(toolbar+create / table 列 `[visit_code, customer_name, visit_date, summary, actions]`);`/api/menu/user` 含 `Visit Log → /dynamic/visit-log → visit_log_list`。合成 DSL 通过平台 S-PAGE-* 可渲染性 validator,且与生产 golden 页 `tcrm_lead_{list,form}` 结构同构。

## 残留(非本切片)
- ✅ **像素级浏览器 golden 已补(2026-06-13)**:标准 host-first 栈 `Vite:5274 → BFF:3601 → Backend:6543`(后端 `AGENT_LLM_STUB_MODE=true`,用于替代外部 LLM key)已补 `web-admin/tests/e2e/ai/prompt-to-app-dynamic-form-submit-golden.spec.ts`。覆盖两条真实浏览器路径:
  1. 生产随附 legacy 动态表单 `/p/tasset_category/new`:按钮同时含 `action:"save"` + `commandCode:"tasset:create_category"` 时,浏览器点击提交必须打到 `/api/meta/commands/execute/tasset:create_category`,并在列表看到新建分类行。
  2. Prompt-to-App 合成应用:通过 `/api/agent/nl-modeling/apply` 只给 model+fields,平台合成 list/form/menu/command;浏览器从侧边栏进入 `/p/<generated-model>`,点击 Create → 填表 → Submit,必须打到 `<plugin>:create_<model>` 并在列表看到新行。实测生成路由示例:`/p/p2a_lead_mqbq44m0`。
  3. 证据:focused unit `canonicalizePageDsl.test.ts` 16 passed;`pnpm typecheck` passed;`nl-modeling-smoke.spec.ts` 25 passed / 2 skipped;`prompt-to-app-dynamic-form-submit-golden.spec.ts` 21 passed / 1 skipped。
- **生成质量**:弱模型(deepseek-chat→v4-flash)只生成 model+fields。生成质量 = prompt/model 调优,独立项。
- **MD-3 in-designer AI 副驾**:复用本 generate/refine(现 import-ready)→ 限定到设计器当前面片段。
- ✅ DeepSeek key:本验证用后**已清** `aura_boot_auraqr` CloudConfig(`DELETE ab_cloud_config ... provider_code=deepseek`,验 0 行);chat 暴露的 key 仍须 owner 端轮换。
