---
type: backlog
status: active
created: 2026-06-11
relates_to:
  - docs/backlog/2026-06-11-rule-center-integration-coverage-gap.md
  - docs/superpowers/specs/2026-06-05-automation-designer-golden-e2e-design.md
---

# 全量设计器笛卡尔积覆盖矩阵方案

## 目标

本 scope 不是新增业务功能，而是对 AuraBoot 现有拖拽 / 画布 / 设计器类页面做一轮独立的测试真实性治理与补测。目标是把 Page Designer、BPMN Designer、Automation Designer、Dashboard / Report Designer 的“组件类型 × 属性面板 × 结构操作 × 按钮 × 保存回显 × 导入导出 × 后端消费”矩阵列清楚，并对 P0/P1 缺口补真实浏览器和后端证据。

完成后只能声明“矩阵中标记为 DONE 的行已通过证据闭环”。禁止把一个代表性 happy path、旧 deep suite pass count、或者 visible-only 按钮断言包装成“全量设计器已完整覆盖”。

## 为什么要单独开新 scope

规则中心收口文档已经关闭了规则 / SLA / BPM / Automation / Permission / EventPolicy 的 targeted slice，但全量设计器矩阵属于另一类工作：

- 设计器组件和属性组合数量大，和规则中心不是同一个验收边界。
- Page / BPMN / Automation / Dashboard 复用部分 shared kernel，但各自消费方的 palette、属性 schema、保存 payload、导出 artifact 和 runtime 语义不同，不能只测 shared kernel demo。
- 旧 E2E 中仍有历史债：`test.skip`、`test.fixme`、`waitForTimeout()`、visible-only、API PUT 绕 UI、宽松阈值等。需要逐个判断是产品缺口、环境债、已被新 spec 覆盖，还是应该直接退役。
- 笛卡尔积不能机械爆炸式穷举。正确做法是先 inventory，再分层抽样和风险分级，把高风险组合补到真实闭环。

## 范围

### In Scope

- Page Designer / Unified Page Designer
- BPMN Designer / BPM Flow Designer
- Automation Designer
- Dashboard Designer / Report Designer
- 共享拖拽内核相关 helper、test id、E2E harness，但只在消费方矩阵需要时修改
- 设计器导入 / 导出 artifact：文件名、扩展名、header、内容解析、round-trip
- 保存 / 发布 / 部署 / 启用 / 预览 / 运行类按钮的真实 UI 触发与后端反查
- 负向路径：必填缺失、非法嵌套、非法连线、非法 schema、无权限、后端校验失败、导入非法文件

### Out of Scope

- 新写整页 React 设计器。除非已有平台能力无法表达且用户确认，否则默认复用平台 DSL / custom block / 既有设计器内核。
- 重做拖拽内核。优先复用 page-designer、BPMN designer、automation designer、dashboard designer 的 palette/store/canvas/selection/undo-redo 能力。
- 无业务价值的数学式全组合穷举。低风险样式属性可按组件族抽样，高风险属性必须逐项闭环。
- 生产部署。此 scope 只负责本地真栈 / CI 可复现的测试与代码收口。

## 完成判定

完成声明前必须同时满足：

1. 每个设计器都有 feature/action inventory。
2. 每个设计器都有 designer coverage matrix，所有 P0/P1 行状态为 `DONE / N/A / WONT_DO`，不能有 `unknown / draft / ❌ / ⚠️ 语义浅`。
3. 每个 DONE 行有 browser evidence 和 backend evidence；导出 / 下载行还必须有 artifact evidence。
4. 每个保存类动作必须证明 UI 操作触发真实 API，保存后 API/DB 反查一致，reload 后画布与属性回显一致。
5. 每个发布 / 部署 / 启用 / 运行类动作必须证明后端 runtime 或下游消费语义，不只看 toast。
6. `/e2e-feature-coverage` 思路矩阵已更新，`/e2e-truth` 红线 grep 已跑，新增或本轮引用证据中不含 skip/fixme/fixed wait/retry/PUT 绕 UI/visible-only 假覆盖。
7. 文档回填每轮测试命令、结果、剩余风险。

## 统一矩阵模板

每个设计器至少维护一张矩阵：

| 设计器 | 组件族 / 节点族 | 具体类型 | 添加方式 | 属性面板字段 | 结构操作 | 保存/发布/运行 | Reload 回显 | 导入/导出 | Browser evidence | Backend evidence | Artifact evidence | 状态 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| page-designer | table | data-table | palette drag | columns / dataSource / rowActions | drag / move / delete | save / publish / preview | schema reload | page.json export/import | spec + screenshot | `/api/pages/{pid}` | `{pageKey}.page.json` parsed | TODO |

状态枚举：

- `TODO`: 未开工。
- `IN_PROGRESS`: 正在补测或修产品问题。
- `DONE`: browser + backend + artifact/runtime 证据齐备。
- `N/A`: 当前设计器不支持该动作，且有代码或产品文档证据。
- `WONT_DO`: 明确不做，必须写原因和替代证据。

## Page Designer 矩阵

### 组件族

| 组件族 | 代表类型 | 必测属性 | 必测动作 | 后端 / artifact 证据 | 状态 |
|---|---|---|---|---|---|
| layout/container | section / columns / tabs | title、layout、children | 拖入、嵌套、移动、删除、撤销/重做 | page schema blocks 顺序 | TODO |
| form/input | form-section / text / number / reference / select / date | field binding、required、readonly、visibleWhen | 添加字段、编辑属性、required 空提交 | `/api/pages/{pid}` + 表单运行页校验 | TODO |
| table/list | data-table / smart-table | columns、dataSource、rowActions、filters | 列配置、搜索、行操作、批量操作 | schema + 运行页行数据 | TODO |
| sub-table | foreignKey / resolveVia / dataSource | childModel、foreignKey、inlineEdit | 新增子行、编辑、保存、取消 | API/DB 子表反查 | TODO |
| chart/stat | stat-card / chart | query/dataSource、metric field | 配置、预览、刷新 | NQ/API records 非空 | TODO |
| custom block | typed custom block | props schema、valueField | 配置、保存、运行页渲染 | block props + runtime output | TODO |
| upload/attachment | upload block | accept、maxSize、field binding | 上传、下载、删除 | 文件 API + artifact header | TODO |

### 现有证据起点

- `web-admin/tests/e2e/page-designer/designer-deep-operations.spec.ts`
- `web-admin/tests/e2e/page-designer/page-designer-full-lifecycle.spec.ts`
- `web-admin/tests/e2e/page-designer/field-properties.spec.ts`
- `web-admin/tests/e2e/page-designer/smart-components.spec.ts`

第一轮需要先审这些 spec 是否仍有 skip、fixed wait、宽松阈值或只测 visible。

## BPMN Designer 矩阵

### 组件族

| 组件族 | 具体类型 | 必测属性 | 必测动作 | 后端 / runtime 证据 | 状态 |
|---|---|---|---|---|---|
| event | startEvent / endEvent | initiator、formKey、terminateAll | 添加、选择、属性编辑 | designerJson + BPMN XML | TODO |
| task | userTask | assigneeType/value、approvalMode、priority-like task config、skipable、formBinding | 添加、属性编辑、保存、deploy、runtime task | DTO + XML + task runtime | TODO |
| service task | command / http / java / notification / rule | serviceType、commandCode、http config、rule config | 保存、deploy、runtime执行 | XML delegate + runtime side effect | TODO |
| receiveTask | receiveTask | name / message config if supported | 保存、deploy | XML receiveTask | TODO |
| gateway | exclusive / parallel / inclusive | defaultFlow、condition builder | 连线、条件、非法缺条件 | XML conditionExpression + runtime branch | TODO |
| callActivity | callActivity | calledProcessKey、input/output mapping | parent/child deploy、runtime | XML calledElement + child task | TODO |
| edge | sequenceFlow | label、condition、sourceHandle | 连线、改条件、删除 | designerJson edge + XML | TODO |

### 现有证据起点

- `web-admin/tests/e2e/designer/designers.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-node-property-matrix.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-receivetask.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-gateway-condition.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-servicetask-command.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-servicetask-http.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-callactivity.spec.ts`
- `web-admin/tests/e2e/bpm-designer/designer-usertask-assignee-matrix.spec.ts`

已退役的旧套件不能作为证据：

- `web-admin/tests/e2e/designer/bpmn-node-properties.spec.ts`
- `web-admin/tests/e2e/designer/bpmn-designer-deep.spec.ts`

## Automation Designer 矩阵

### 组件族

| 组件族 | 具体类型 | 必测属性 | 必测动作 | 后端 / runtime 证据 | 状态 |
|---|---|---|---|---|---|
| trigger | record-create / record-update / field-change / state-change / scheduled / webhook / bpm-event / inactivity | modelCode、event、condition、ruleBinding、inactivity filter | 拖入、配置、保存、enable、fire / scheduler sweep | automation DTO + execution log | DONE: trigger family covered in `automation-designer-golden.spec.ts` including 2026-06-12 `trigger-bpm-event` and `trigger-inactivity` |
| action | update-record / create-record / send-notification / execute-command / call-api / send-webhook / start-process / llm-call | target model、field mapping、payload、receiver、fallback | 保存、test run、debug step | side effect + node statuses | DONE: action family covered in `automation-designer-golden.spec.ts` including 2026-06-12 `action-start-process` |
| control | condition / loop / delay | expression、branch condition、collection mapping、timer | true/false branch、非法表达式、循环边界 | SmartEngine process + logs | DONE: condition/loop/delay runtime semantics covered; delay seam added 2026-06-12 |
| edge | true/false/default branch | conditionExpression、sourceHandle | 连线、改条件、删除 | flowConfig edges + runtime branch | DONE: true/false/default branch routing covered by condition/loop/action golden flows |
| rule binding | decision ref block | decisionCode、versionPolicy、input mapping、fallback | 配置、保存、impact graph | usage-index + impact | DONE in rule-center slice |

### 现有证据起点

- `web-admin/tests/e2e/automation/automation-designer-golden.spec.ts`
- `web-admin/tests/e2e/automation/automation-golden.spec.ts`
- `web-admin/tests/e2e/automation/rule-binding-designer-host.spec.ts`
- `web-admin/tests/e2e/designer/automation-designer-deep.spec.ts`
- `web-admin/tests/e2e/automation/automation-validation-gate.spec.ts`
- `web-admin/tests/e2e/automation/llm-call-node.spec.ts`

第一轮建议先审 `automation-designer-golden.spec.ts` 的 fixed wait 和阈值断言，区分：

- 合理业务下限
- runtime polling 可替代的 sleep
- 真实产品缺口
- 已由后端集成覆盖但 E2E 不应宣称 UI 覆盖的项

## Dashboard / Report Designer 矩阵

### 组件族

| 组件族 | 具体类型 | 必测属性 | 必测动作 | 后端 / artifact 证据 | 状态 |
|---|---|---|---|---|---|
| widget | KPI / table / chart / text / markdown / iframe if supported | title、dataSource、refresh、style | 添加、编辑、删除、复制、排序 | dashboard config | TODO |
| dataSource | static / API / namedQuery / model | query、params、field mapping | 预览、保存、刷新 | API response / records | TODO |
| layout | grid / tabs / responsive layout | size、position、breakpoints | drag resize、reorder、reload | config layout | TODO |
| export | Excel / PDF / JSON if supported | file naming、content | download、parse、round-trip where supported | xlsx/pdf/json artifact | PARTIAL: Dashboard Excel/PDF done; Report table + non-table static Excel/PDF artifacts done; Report model/namedQuery/API dataSource export semantics remain TODO |
| publish/share | global/personal/dashboard visibility | permission、publish state | publish/unpublish、reload | API state + permission | TODO |
| management list | row actions / list tabs | row action visibleWhen、onRowClick detailUrl、scope tabs | filter、publish/unpublish/delete、row navigation、tab filter | dashboard API state + browser reload | DONE: 2026-06-12 `dashboard-management.spec.ts` fresh run covers row action visibility, publish/unpublish/delete lifecycle, row/edit/create navigation, and All/Personal/Global tabs |

### 现有证据起点

- `web-admin/tests/e2e/dashboard/dashboard-export.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-designer-deep.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-widget-types.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-charts.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-interactions.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-management.spec.ts`
- `web-admin/tests/e2e/designer/report-designer-deep.spec.ts`

已知已关闭：

- Excel export artifact：文件名、ZIP header、workbook sheet/rows。
- PDF export artifact：文件名、PDF header、page marker/content marker。
- Report table Excel artifact：`extension.reportDsl` 保存、BFF binary proxy、下载文件名、ZIP header、workbook sheet/header/rows。
- Report table PDF artifact：`extension.reportDsl` 保存、后端 PDFBox 渲染、下载文件名、PDF header、page marker、PDFTextStripper 内容解析。
- Report non-table static Excel/PDF artifact：`grouped-table` 分组行、`cross-tab` pivot 矩阵、`stat-card` 聚合值、`rich-text` 段落、`chart` 聚合数据表、`barcode`/`watermark`/`page-header`/`page-footer` 文本 artifact。

仍需审查：

- widget types spec 的 `>=1` 假覆盖已关闭:2026-06-12 `dashboard-widget-types.spec.ts` 改为精确 palette 清单、add 后 widget 数量 `+1`,并在画布上用 `data-widget-type` 断言实际新增 widget 类型;新增 `DW-037` 保存读回 `smart-area-chart` type/componentType/default size/default visualization/static dataSource。
- registry workbench widgets 是否需要逐类型 runtime 语义断言;当前 14 个 named widget suite 和 tab persistence 不能外推到全部 37 个 registry 类型。
- 14 个 named widget 的 per-type saved-payload / property value matrix 仍未全部展开;当前只有 `smart-area-chart` 具备 UI 添加 -> 保存 -> `/api/dashboards/{pid}` 读回的精确 payload 证据。
- Report model/namedQuery/API dataSource 的导出语义仍未完成,不能外推为 Report export 全量 DONE;非 table static artifact DONE 不等于 PDF 视觉等价渲染。

## 红线审查命令

新窗口启动后先跑这些命令，不要先写代码：

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/decisionops-console-completion

rg -n 'test\.skip|test\.fixme|waitForTimeout\(|toBeGreaterThanOrEqual\(|toBeLessThanOrEqual\(|retries:|waitForEvent\(["'\'']download["'\'']\)\.catch|page\.request\.put|__reactProps' \
  web-admin/tests/e2e/page-designer \
  web-admin/tests/e2e/designer \
  web-admin/tests/e2e/bpm-designer \
  web-admin/tests/e2e/automation \
  web-admin/tests/e2e/dashboard \
  -g '*.spec.ts'
```

然后按文件分类：

| 分类 | 处理 |
|---|---|
| 新证据链要引用的 spec | 必须修掉 skip/fixme/fixed wait/PUT 绕 UI/visible-only |
| 已被更强 spec 覆盖的旧 spec | 退役删除或降级为 smoke，文档写替代证据 |
| 环境前置型 skip | 优先修 seed / import / permission fixture；不能修则不计入完成证据 |
| 产品真实缺口 | 新建矩阵行，补产品能力 + browser/backend 测试 |
| 合理业务下限 | 保留，但加注释说明语义，不能用于核心完成比例 |

## 实施顺序

### Phase 0: 新 goal 与环境校准

建议新窗口第一条 goal：

```text
/goal 完成全量设计器笛卡尔积覆盖矩阵第一轮: 产出 inventory + coverage matrix, 清理本轮引用证据中的假覆盖, 对 P0/P1 缺口补真实 browser/backend/artifact 测试, 提交并 push
```

启动前校准：

- `git status --short`
- `git branch --show-current`
- `git pull --rebase`
- `curl --noproxy '*' http://127.0.0.1:6443/actuator/health`
- `curl --noproxy '*' -I http://127.0.0.1:5174/home`
- 确认 backend、Vite/BFF、admin storage state 可用。

### Phase 1: Inventory

提取每个设计器的：

- palette/node/widget 类型
- 属性面板字段
- toolbar/context menu 按钮
- save/publish/deploy/enable/test-run/preview/export/import API
- artifact 类型
- runtime 消费方

输出到本文档新增章节“Inventory Snapshot”，不要只写口头结论。

## Inventory Snapshot

> 2026-06-11 live source extraction. Source of truth is implementation code, not old pass counts.

### Extraction Evidence

| 范围 | 真源 | live 计数 |
|---|---|---:|
| Page Designer block palette | `web-admin/app/plugins/core-designer/components/studio/workbench/designers/areas/BlockLibrary.tsx` | 17 |
| BPMN Designer palette | `web-admin/app/plugins/core-designer/components/bpmn-designer/constants/index.ts` | 9 |
| Automation Designer palette | `web-admin/app/framework/smart/automation/nodes/{triggers,actions,controls}.ts` | 19 |
| Dashboard Designer widget registry | `web-admin/app/plugins/core-dashboard/widgets/widgetRegistry.ts` | 37 |
| Report Designer block palette | `web-admin/app/plugins/core-designer/components/report-designer/components/BlockPalette.tsx` | 10 |

### Page Designer Inventory

| 类型 | 分类 | 可用页面 | P0/P1 风险 | 本轮证据状态 |
|---|---|---|---|---|
| filters | form | list | P1: filter-form 输入、保存、运行页过滤 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 发布真实 custom list 页,浏览器打开 `/p/c/{pageKey}`,点击 `filters-toggle`,断言 `search-area`、`field-name`、search/reset 按钮 |
| form-section | form | form/list | P0: 字段绑定、required、readonly、visibleWhen | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 发布真实 custom form 页,断言 `dynamic-form`、section title、`form-field-name/page_key` 与 runtime field renderer;字段校验深度仍归 form/input 族矩阵 |
| form-buttons | form | form | P0: create/update 命令真实触发 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 断言 `form-btn-submit/cancel`,并点击 configured `navigateTo` cancel 证明 form-buttons action dispatch;create/update command side effect 不在本行外推 |
| table | display | list | P0: columns/dataSource/rowActions/search/bulk | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 断言 published list 页中的 `ab:list:page_schema:table` 与 `table-cell-0-name/status`,证明 table block 被运行页消费并加载真实 list data |
| detail-section | display | form/list | P1: 详情字段回显、raw code 防泄漏 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 断言 `.block-detail-section` title 与 field label;同时产品修复 `DetailPageContent` 允许 `detail-section` 走 direct form block 渲染 |
| text | display | list/form | P2: 静态内容保存回显 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 用 `props.content` 发布 `text`,运行页 `.block-text` 渲染 sanitized HTML 内容 |
| toolbar | layout | list | P0: action.command 与命令管道 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 断言 list header `toolbar-btn-runtime_ping` 来自 published toolbar block;命令副作用深度仍归 action.command 矩阵 |
| selection-info | layout | list | P1: 多选状态与批量工具条 | DONE: 2026-06-12 新增 `SelectionInfoBlockRenderer`;runtime E2E 先断言 count `0`,点击 helper action 写 `selectedRows`,再断言 count `1` 与 selected row label |
| stat-card | chart | list/form | P1: NQ/API 非空与数值断言 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 用 schema static `ds_stats`,断言 `stat-card-value=42`、suffix `items`、trend `+12%` |
| chart-card | chart | list/form | P1: 图表数据源与渲染 | DONE: 2026-06-12 `standard-blocks-runtime.spec.ts` 用 `props.chartType/xField/yField` alias + static chart dataSource,断言 `.block-chart-card` 渲染 canvas/svg 且无 unknown/unsupported block 文案 |
| metric-strip | workbench | list/form | P1: 指标点击筛选联动 | DONE: 2026-06-12 `workbench-blocks-runtime.spec.ts` 在真实 `/p/c/{pageKey}` custom list 页发布 schema, readback `dataSources`, 渲染静态 metric 数据, 点击 `pending` 写 `state.reviewMode` 并驱动 action-bar 可见性 |
| record-inspector | workbench | list/form | P1: 选中行联动详情 | DONE: 2026-06-12 runtime E2E 先断言空态,再通过 `candidate-list` 选中 `C-100` 写入 runtime state, `record-inspector` 渲染 title/status/evidence source |
| candidate-list | workbench | list/form | P1: 候选选择写回状态 | DONE: 2026-06-12 runtime E2E 用 schema-level static `ds_candidates`,断言候选行与 detail fields,点击候选写 `selectedWorkbenchRecord` 并驱动 inspector/evidence/drawer |
| workbench-action-bar | workbench | list/form | P0: 导出/下载/命令行动点 | DONE: 2026-06-12 runtime E2E 覆盖 `visibleWhen` 初始隐藏、metric click 后显示 `pending_only`,点击 `mark_reviewed` 后显示 `reviewed_only`;同时补 `/p/c/:pageKey` route manifest 单测避免 custom page 落入菜单 catch-all |
| evidence-panel | workbench | list/form | P1: raw payload/证据渲染 | DONE: 2026-06-12 runtime E2E 先断言空态,选中候选后渲染 conflict 与 JSON raw payload (`supplier-audit.xlsx`) |
| artifact-timeline | workbench | list/form | P1: 附件/导出产物历史 | DONE: 2026-06-12 runtime E2E 用 schema-level static `ds_artifacts`,断言 filename/revision/hash 行与 `/api/file/download/file-workbench-100` href |
| review-drawer | workbench | list/form | P1: 浮层复核、候选确认 | DONE: 2026-06-12 runtime E2E 先断言空态,选中候选后渲染 floating drawer title、score badge、compare tab、candidates tab;覆盖 state/context/dataSource 三路联动 |

### Automation Designer Inventory

| 类型 | 分类 | 属性字段 | P0/P1 风险 | 本轮证据状态 |
|---|---|---|---|---|
| trigger-record-create | trigger | modelCode | P0: create event fire | DONE: golden flow fires real create event and verifies log + downstream side effect |
| trigger-record-update | trigger | modelCode, watchFields | P0: update event fire / create 不误触发 | DONE: golden flow fires real update event and verifies update-specific log + side effect |
| trigger-field-change | trigger | modelCode, fieldCode, fromValue, toValue | P0: watched field 与 non-watched field | DONE: golden flow covers watched field-change and non-watched no-fire behavior |
| trigger-state-change | trigger | modelCode, stateField, fromStates, toStates | P0: dict-backed state filter | DONE: golden flow covers dict-backed state transition fire |
| trigger-scheduled | trigger | cron, timezone, maxExecutionTime | P1: scheduler fire / no fixed sleep fake pass | DONE: golden flow covers scheduler fire without fixed sleep evidence |
| trigger-webhook | trigger | secret, validationMode, expectedHeaders | P0: inbound webhook real POST | DONE: golden flows fire real inbound webhook POSTs and verify execution logs |
| trigger-bpm-event | trigger | modelCode, eventTypes | P1: BPM event consumer seam | DONE: 2026-06-12 `N-TRIGGER-BPM-EVENT` browser/backend runtime seam, static multiselect saves `eventTypes` as an array, bridge subscribes to EventBusService internal BPM events, SmartEngine versioned process key matches bare automation processKey, BPM `task_assigned` creates downstream order item |
| trigger-inactivity | trigger | modelCode, inactivityHours, inactivityField, stateField, inactivityStates | P1: scheduled inactivity sweep | DONE: 2026-06-12 `N-TRIGGER-INACTIVITY` browser/backend runtime seam, advanced property group is expandable via stable selector, `inactivityField/stateField/inactivityStates` persist as backend field/dict codes, configurable scheduler sweep finds stale cancelled record and creates downstream order item |
| action-update-record | action | modelCode, recordId, fields | P0: side effect + sad invalid field | DONE: golden flow verifies persisted field update and sad invalid field failure |
| action-create-record | action | modelCode, fields | P0: child record created + invalid field sad | DONE: golden flow verifies child record creation and invalid field failure |
| action-send-notification | action | notificationType, title, content, recipients | P1: notification runtime completion | DONE: golden flow verifies notification node completion |
| action-execute-command | action | commandCode, params | P0: command-select picker + restricted principal sad | DONE: golden flow verifies command-select save and restricted-principal sad path |
| action-call-api | action | url, method, headers, body | P0: outbound success/failure | DONE: golden flow verifies outbound success and 404 sad node failure |
| action-send-webhook | action | url, payload | P0: outbound receiver payload + 500 sad | DONE: golden flow verifies receiver payload and upstream 500 sad behavior |
| action-start-process | action | processKey, businessKey, variables | P1: BPM runtime side effect | DONE: 2026-06-12 `N-START-PROCESS` browser/backend runtime seam, process picker uses deployed BPM definitions, Automation `CUSTOM` storage switches to BPM `DATABASE`, businessKey status proves BPM instance |
| action-llm-call | action | model, prompt fields, outputVariableName, imageVariableNames | P1: built-in stub provider + persisted output; external key not required | DONE: golden flow verifies built-in stub provider output without external key |
| control-condition | control | expression | P0: true/false/edge boundary | DONE: golden flows cover true/false branch routing and invalid-expression sad path |
| control-delay | control | duration, unit | P1: delayed runtime semantics | DONE: 2026-06-12 `N-DELAY` browser/backend runtime seam, compiler maps to `delay`, executor consumes `duration/unit` |
| control-loop | control | collection, itemVariable | P1: non-empty and empty collection | DONE: golden flows cover non-empty loop fan-out and empty collection behavior |

### Automation Runtime Architecture Notes

> 2026-06-12 收口 `trigger-bpm-event` 时补充。以下内容只解释运行时边界,不带浏览器 / 后端验证行时不能单独计入 DONE 证据。

#### Spring BPM event 与 SmartEngine 的关系

`Spring BPM event` 不是 SmartEngine 的独立模块,而是本次调试里对 `BpmEvent` 经 Spring `ApplicationEventPublisher` 发布的简称。AuraBoot BPM 层接收或创建运行时事件后,会把它包装成 `BpmEvent`,再通过本地事件分发层交给通知、SLA、Automation 等消费方。

运行时链路是:

```text
SmartEngine BPM execution/task event
  -> AuraBoot BpmEvent / EventBusService
  -> Spring ApplicationEvent + internal BPM subscribers
  -> BpmEventAutomationBridge
  -> AutomationTriggerService.onBpmEvent
  -> matched automation execution
```

这个边界会直接影响验证方式:

- SmartEngine 是 BPM 执行引擎,负责启动流程、创建任务,并通过 AuraBoot 集成点发出任务生命周期事件。
- `EventBusService` 是 AuraBoot 的 BPM 事件分发层,负责按需持久化任务事件、派发 internal subscriber,并发布 Spring event。
- `BpmEventAutomationBridge` 是 BPM event 进入 Automation 的消费 seam。单独证明 SmartEngine 能启动流程不等于证明这个 bridge 可用;E2E 必须证明某个 BPM event 能产生 automation log 和下游副作用。

当前源码锚点:

- SmartEngine process start: `platform/src/main/java/com/auraboot/framework/bpm/service/ProcessEngineService.java`
- BPM event dispatch: `platform/src/main/java/com/auraboot/framework/bpm/event/EventBusService.java`
- Automation bridge: `platform/src/main/java/com/auraboot/framework/bpm/listener/BpmEventAutomationBridge.java`

#### AutomationTriggerService 与 SmartEngine 的关系

`AutomationTriggerService` 负责触发匹配,不负责图编排。它接收 record / BPM / webhook / scheduled 等事件,加载启用的 automation,按 trigger config 过滤,评估 trigger condition 和 decision binding,然后把命中的 automation 派发给运行时执行。

当前执行设计:

```text
AutomationTriggerService
  -> AutomationProcessRuntime.run()
  -> AutomationFlowCompiler.compile()
  -> deploy/start auto_<automationPid> SmartEngine process
  -> SmartEngine orchestrates sequence/gateway/loop
  -> AutomationActionServiceTaskDelegate
  -> CompositeActionExecutor
  -> concrete action executor
```

准确表述是:Automation 的触发匹配在 AuraBoot application service 中完成;automation graph 的执行会编译并交给 SmartEngine 编排;具体业务动作仍由 AuraBoot 的 `ActionExecutor` 实现执行。

当前源码锚点:

- Trigger matching and log lifecycle: `platform/src/main/java/com/auraboot/framework/automation/trigger/impl/AutomationTriggerServiceImpl.java`
- Automation flow compile/deploy/run: `platform/src/main/java/com/auraboot/framework/automation/bpm/AutomationProcessRuntime.java`
- Designer graph to SmartEngine shape: `platform/src/main/java/com/auraboot/framework/automation/bpm/AutomationFlowCompiler.java`
- SmartEngine serviceTask to action executor bridge: `platform/src/main/java/com/auraboot/framework/automation/bpm/AutomationActionServiceTaskDelegate.java`

### BPMN Designer Inventory

| 类型 | 分类 | P0/P1 风险 | 本轮证据状态 |
|---|---|---|---|
| START_EVENT | event | initiator / XML start event | TODO |
| END_EVENT | event | terminateAll / XML end event | TODO |
| USER_TASK | task | assignee matrix / form binding / MI / runtime task | TODO |
| SERVICE_TASK | task | command / http / runtime side effect | TODO |
| RECEIVE_TASK | task | designerJson + BPMN XML | TODO |
| EXCLUSIVE_GATEWAY | gateway | condition + default branch + runtime route | TODO |
| PARALLEL_GATEWAY | gateway | fork/join no conditionExpression | TODO |
| INCLUSIVE_GATEWAY | gateway | multi-condition branch runtime | TODO |
| CALL_ACTIVITY | task | parent/child deploy + variable mapping runtime | TODO |

### Dashboard / Report Designer Inventory

| 范围 | 类型 | 属性字段摘要 | P0/P1 风险 | 本轮证据状态 |
|---|---|---|---|---|
| dashboard | smart-number-card | title, icon, suffix, showTrend | P0: add/save/reload + data source | PARTIAL: 2026-06-12 `dashboard-widget-types.spec.ts` now asserts exact palette presence and click-add count `+1`; property panel section exists. Per-type saved payload still TODO. |
| dashboard | smart-bar-chart | title, horizontal, stacked | P0: add/save/reload + data source | PARTIAL: exact palette presence, click-add count `+1`, and property panel header. Per-type saved payload still TODO. |
| dashboard | smart-line-chart | title, smooth, showArea | P0: add/save/reload + data source | PARTIAL: exact palette presence and click-add count `+1`; per-type property value and saved payload still TODO. |
| dashboard | smart-pie-chart | title, donut, showLabels | P0: add/save/reload + data source | PARTIAL: exact palette presence and click-add count `+1`; per-type property value and saved payload still TODO. |
| dashboard | smart-area-chart | title, smooth, fillOpacity | P1: property panel + render | DONE: 2026-06-12 `DW-037` proves UI click-add, static dataSource edit, manual save API, `/api/dashboards/{pid}` readback, exact `type/componentType`, default size, and registry `visualization.smooth/fillOpacity` persistence. |
| dashboard | smart-funnel-chart | title, sort | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and sort property panel label. Per-type saved payload still TODO. |
| dashboard | smart-scatter-chart | title, bubbleMode | P1: property panel + render | PARTIAL: exact palette presence and chart add/property header evidence from widget/charts specs. Per-type saved payload still TODO. |
| dashboard | smart-radar-chart | title, shape, showArea | P1: property panel + render | PARTIAL: exact palette presence and chart add/property header evidence from widget/charts specs. Per-type saved payload still TODO. |
| dashboard | smart-table-chart | title, pageSize, striped | P0: table rows + data source | PARTIAL: exact palette presence and chart add evidence; table export artifact is covered elsewhere. Per-type saved payload still TODO. |
| dashboard | smart-gauge-chart | title, min, max, splitNumber | P1: property panel + render | TODO |
| dashboard | smart-progress | title, target, format, shape | P1: property panel + render | TODO |
| dashboard | smart-heatmap-chart | title, xField, yField | P1: property panel + render | TODO |
| dashboard | smart-treemap-chart | title, nameField, valueField | P1: property panel + render | TODO |
| dashboard | smart-map-chart | title, mapRegion | P1: property panel + render | TODO |
| dashboard | smart-leaderboard | title, maxItems, rankField, valueField | P1: property panel + render | TODO |
| dashboard | smart-rich-text | title, content, format | P1: property panel + render | TODO |
| dashboard | smart-image | title, src, alt, objectFit | P1: property panel + render | TODO |
| dashboard | smart-iframe | title, src | P1: property panel + render | TODO |
| dashboard | smart-countdown | title, targetDate, format | P1: property panel + render | TODO |
| dashboard | smart-wordcloud-chart | title, shape, colorTheme, gridSize | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and shape property panel label. Per-type saved payload still TODO. |
| dashboard | smart-combo-chart | title, smooth, stack, dataZoom, axes | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and Y-axis property panel label. Per-type saved payload still TODO. |
| dashboard | smart-nps-chart | title, scoreField, legend, ringWidth | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and score field property panel label. Per-type saved payload still TODO. |
| dashboard | smart-gallery | title, columns, image/title/description fields | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and columns property panel label. Per-type saved payload still TODO. |
| dashboard | smart-kanban | title, group/title/description fields | P1: property panel + render | PARTIAL: exact palette presence, click-add count `+1`, and group field property panel label. Per-type saved payload still TODO. |
| dashboard | smart-stats-row | title | P1: workbench widget render | TODO |
| dashboard | smart-stats-card | title, statKey | P1: workbench widget render | TODO |
| dashboard | smart-inbox | title, maxItems, itemTypes | P1: workbench widget render | TODO |
| dashboard | smart-calendar | title | P1: workbench widget render | TODO |
| dashboard | smart-pipeline | title | P1: workbench widget render | TODO |
| dashboard | smart-leads | title | P1: workbench widget render | TODO |
| dashboard | smart-activities | title | P1: workbench widget render | TODO |
| dashboard | smart-my-process | title, maxItems | P1: BPM workbench seam | TODO |
| dashboard | smart-process-stats | title | P1: BPM workbench seam | TODO |
| dashboard | smart-shortcuts | title, columns | P1: shortcut interaction | TODO |
| dashboard | smart-recent | title, maxItems | P1: recent visits data | TODO |
| dashboard | smart-announcement | title | P1: announcement render | TODO |
| dashboard | smart-quick-note | title | P1: note persistence | TODO |
| report | table | dataSource, columns | P0: save/API/export artifact | DONE: 2026-06-12 `RPT-OP-07` browser click downloads parsable XLSX and `RPT-OP-08` browser click downloads non-empty PDF from saved `extension.reportDsl`; backend `ReportExportServiceTest` parses workbook title/header/rows and PDF text; BFF binary proxy regression covered |
| report | grouped-table | dataSource, groupByField, columns | P1: save/API/render | DONE: 2026-06-12 static dataSource Excel/PDF semantic artifact; `RPT-OP-09/10` validates group rows and backend unit parses workbook/PDF text |
| report | stat-card | dataSource, valueField, aggregation | P1: save/API/render | DONE: 2026-06-12 static dataSource Excel/PDF semantic artifact; workbook/PDF asserts `Total Cases=24` |
| report | rich-text | content, align | P1: save/API/render | DONE: 2026-06-12 static content Excel/PDF semantic artifact; workbook/PDF asserts paragraph text |
| report | cross-tab | rowField, columnField, valueField | P1: save/API/render | DONE: 2026-06-12 static dataSource Excel/PDF semantic artifact; workbook/PDF asserts pivot columns, row totals, grand total |
| report | chart | chartType, categoryField, valueField | P1: save/API/render | DONE: 2026-06-12 static dataSource Excel/PDF semantic artifact; chart exported as aggregated category/value table, not visual chart image |
| report | barcode | format, staticValue | P1: save/API/render | DONE: 2026-06-12 static text artifact in Excel `Report Text` sheet and PDF content stream |
| report | watermark | text, rotation, opacity | P1: save/API/render | DONE: 2026-06-12 static text artifact in Excel `Report Text` sheet and PDF content stream; visual opacity/rotation not claimed |
| report | page-header | header band | P1: save/API/render | DONE: 2026-06-12 static text artifact in Excel `Report Text` sheet and PDF content stream |
| report | page-footer | footer band | P1: save/API/render | DONE: 2026-06-12 static text artifact in Excel `Report Text` sheet and PDF content stream |

### Phase 2: 旧测试真实性审计

对每个设计器列：

- 当前能作为证据的 spec
- 必须退役的 spec
- 必须修的 spec
- 可暂时标 N/A 的项

任何退役都必须写“替代证据是什么”；没有替代证据则不能退役，只能标 TODO 或产品缺口。

### First Audit Result

Live command:

```bash
rg -n -e 'test\.skip' -e 'test\.fixme' -e 'waitForTimeout\(' -e 'toBeGreaterThanOrEqual\(' -e 'toBeLessThanOrEqual\(' -e 'retries:' -e "waitForEvent\(['\"]download['\"]\)\.catch" -e 'page\.request\.put' -e '__reactProps' \
  web-admin/tests/e2e/page-designer \
  web-admin/tests/e2e/designer \
  web-admin/tests/e2e/bpm-designer \
  web-admin/tests/e2e/automation \
  web-admin/tests/e2e/dashboard \
  -g '*.spec.ts'
```

| 范围 | 审计结论 | 处理 |
|---|---|---|
| Automation Designer | `automation-designer-golden.spec.ts` 已清掉本轮引用路径里的 fixed wait,并用真实 browser/backend runtime fresh run 通过。2026-06-12 新增 `control-delay` 闭环:先用 `AutomationFlowCompilerTest` / `ControlNodeExecutorTest` 红绿验证编译与执行契约,再用 `N-DELAY` 真浏览器拖拽、属性面板、保存回显、webhook 触发、节点状态、下游副作用证明 runtime seam。2026-06-12 新增 `action-start-process` 闭环:先用 direct BPM API 证明 `e2et_payment_approval` 可启动并停在 `manager_review`,再用 `StartProcessActionExecutorTest` 红绿锁定 Automation `CUSTOM` storage 到 BPM `DATABASE` 的 seam,最后用 `N-START-PROCESS` 真浏览器拖拽、process-select、保存回显、enable、webhook fire、execution log、BPM businessKey status 证明 runtime side effect。2026-06-12 新增 `trigger-bpm-event` 闭环:先用 `PropertyFieldRenderer` vitest 红绿锁定静态 multiselect 数组语义,再用 `BpmEventAutomationBridgeTest` 红绿证明 internal EventBusService subscription 和 tenant binding,用 `AutomationTriggerServiceImplTest` 红绿证明 SmartEngine `processKey:version` 匹配裸 processKey,最后用 `N-TRIGGER-BPM-EVENT` 真浏览器拖拽、process-select、eventTypes、保存回显、enable、BPM start、automation log、下游 order item 证明 BPM event consumer seam。2026-06-12 新增 `trigger-inactivity` 闭环:先用 `AutomationSchedulerTest` 红绿锁定调度 delay 可由环境参数覆盖,再用 `AutomationFlowTriggerDeriverTest` / `AutomationFlowConfigDerivationIntegrationTest` 证明 designer flowConfig 派生 inactivity trigger config,用 `FlowPropertyPanel.extensions.test.tsx` 锁定 advanced 分组展开 test seam,最后用 `N-TRIGGER-INACTIVITY` 真浏览器拖拽、advanced 属性组、field/dict picker、保存回显、enable、scheduler sweep、automation log、下游 order item 证明 inactivity consumer seam。`automation-golden.spec.ts` 仍有 loop / llm / start-process skip/fixme。 | 只引用 `automation-designer-golden.spec.ts`;旧 `automation-golden.spec.ts` skip/fixme 不计完成证据。 |
| Page Designer | `designer-deep-operations.spec.ts` 有拖拽、属性编辑、保存发布、撤销重做、预览、删除、导入导出; `unified-designer-kind-and-binding.spec.ts` 与 toolbar permissions 有 seed/环境前置 skip。2026-06-12 新增 `workbench-blocks-runtime.spec.ts`: API 创建/更新/发布 custom list 页后,真实浏览器打开 `/p/c/{pageKey}`,断言 7 个 workbench block mounted、schema readback、static dataSource 渲染、state 联动、附件 href 与 review drawer 浮层。2026-06-12 新增 `standard-blocks-runtime.spec.ts`: API 创建/更新/发布 custom list/form 页后,真实浏览器打开 `/p/c/{pageKey}`,断言剩余 10 个非 workbench block 的 runtime 消费、filter toggle、table data、chart canvas、stat dataSource、selection state、form button navigation。 | 本轮引用 deep/lifecycle/field/smart/list/load-existing fresh run + workbench/standard runtime targeted; seed 前置 skip 不计完成证据。 |
| Dashboard Designer | `dashboard-widget-types.spec.ts` 多处 `>=1` 已于 2026-06-12 收紧:palette count 变为精确 5/14 清单,add-to-canvas 变为精确 count `+1` + `data-widget-type` 画布类型断言,并新增 `DW-037` 通过 UI 添加 `smart-area-chart`、编辑 static dataSource、保存、GET readback 断言 `type/componentType/defaultSize/default visualization/staticData`。产品侧新增 shared `createWidgetDraft` 工厂,保证 click-to-add 与 drag-to-drop 都保留 registry `defaultConfig`。2026-06-12 `dashboard-tab-reorder.spec.ts` 解除 DTR-002/DTR-003 fixme 后 fresh run 通过,覆盖 tab drag、preference write、reload persistence 与 first-time drag hint。2026-06-12 `dashboard-management.spec.ts` 解除 DM-E03/E04/E07/E08/E10/E11/E12 fixme 后 fresh run 通过,覆盖 row action visibleWhen、publish/unpublish/delete lifecycle、row/edit/create navigation、All/Personal/Global tab filter;旧 spec 内 page schema PUT patch 已移除,`onRowClick/detailUrl` 固化到 setup fixture 源头。`dashboard-export.spec.ts` 已覆盖 Excel/PDF artifact。 | 本轮引用 widget/chart/deep/interactions/export fresh run + tab reorder fresh run + management lifecycle fresh run; registry workbench widgets outside the 14 named widget suite remain TODO,14 named widget 的 saved-payload matrix 目前只有 `smart-area-chart` 精确闭环,不外推 DONE。 |
| BPMN Designer | `web-admin/tests/e2e/bpm-designer/*` 覆盖 userTask/serviceTask/gateway/callActivity/SLA 等 L1/L2/L3;旧 `designer-lifecycle.spec.ts` BPMN 段仍受 permission skip 影响。 | 新 bpm-designer 目录为主要证据;旧 lifecycle skip 不计完成证据。 |
| Report Designer | `report-designer-deep.spec.ts` 的 fixed wait 已替换为 UI/API active wait,并与 `report-designer-smoke.spec.ts` fresh run 通过。2026-06-12 `RPT-OP-07` 从旧的“等待 export API 或 alert”升级为真实 browser download + xlsx parse;产品侧新增 OSS `/api/reports/export/excel`,并修复 PageSchema create extension 丢失与 BFF binary proxy 对 `POST /api/reports/export/excel` 的二进制转发。2026-06-12 `RPT-OP-08` 新增真实 browser download + PDF header/page marker 断言,后端用 saved `extension.reportDsl` 通过 PDFBox 生成 PDF,并由 `PDFTextStripper` 单测解析标题/表头/行。2026-06-12 `RPT-OP-09/10` 新增非 table static artifact: grouped-table 分组、cross-tab pivot、stat 聚合、rich-text 段落、chart 聚合表、barcode/watermark/header/footer 文本 artifact;PDF E2E 解压 Flate content stream 后断言关键文本。 | Report 10 类 block + 操作 smoke/deep 作为本轮证据;Report table + non-table static Excel/PDF artifact 可计入 DONE;Report model/namedQuery/API dataSource export 仍是 TODO。 |

### Live Verification Log

> 2026-06-11 rerun on local real stack. Backend was restarted with `AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1`, `AGENT_LLM_STUB_MODE=true`, `SPRING_PROFILES_ACTIVE=dev`. Local proxy variables were cleared for localhost tests.

| 验证项 | 命令摘要 | 结果 |
|---|---|---|
| Backend health | `curl http://127.0.0.1:6443/actuator/health` with `NO_PROXY=localhost,127.0.0.1` | `{"status":"UP"}` |
| Setup/auth/fixtures | `pnpm exec playwright test -c playwright.config.ts --project=setup --reporter=line` with `IMPORT_TEST_FIXTURES=true` and host plugin root | `16 passed (1.9s)` |
| Typecheck | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| Automation Designer | `tests/e2e/automation/automation-designer-golden.spec.ts` via `playwright.quick.config.ts`, `PW_QUICK_WORKERS=1`, local outbound URLs | `30 passed (2.5m)` |
| 2026-06-12 Backend delay seam | `./gradlew :test --tests com.auraboot.framework.automation.bpm.AutomationFlowCompilerTest --tests com.auraboot.framework.automation.executor.impl.ControlNodeExecutorTest` in `platform` | first run red: `control-delay` unsupported + `duration/unit` ignored; after fix `33 tests completed, 0 failed` |
| 2026-06-12 Setup/auth/fixtures | `pnpm exec playwright test -c playwright.config.ts --project=setup --reporter=line` on host stack `:6443/:5174/:3501` | `16 passed (2.0s)` |
| 2026-06-12 Automation Designer delay targeted | `automation-designer-golden.spec.ts -g "N-DELAY"` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501` | `1 passed (6.4s)` |
| 2026-06-12 Automation Designer full | `tests/e2e/automation/automation-designer-golden.spec.ts` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501` | `31 passed (2.9m)` |
| 2026-06-12 Direct BPM start-process proof | admin login + `POST /api/bpm/process-instances` + `GET /api/bpm/process-instances/by-business-key/status` for `e2et_payment_approval` | process instance created, `manager_review` active, `start_1` completed, variables echoed |
| 2026-06-12 Frontend process picker unit | `pnpm exec vitest run app/shared/services/__tests__/resourceSelectService.test.ts` | `15 tests` passed; new test maps `/api/bpm/process-definitions/deployed` DTO to process-select options |
| 2026-06-12 Backend start-process storage seam | `./gradlew :test --tests com.auraboot.framework.automation.executor.impl.StartProcessActionExecutorTest` in `platform` | first run red: assertion saw outer `CUSTOM` during BPM start; after fix `BUILD SUCCESSFUL`, `7 tests` passed |
| 2026-06-12 Automation Designer start-process targeted | `automation-designer-golden.spec.ts -g "N-START-PROCESS"` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501` | first red: process-select no result due paginated endpoint; second red: webhook 500 from `CustomTaskInstanceStorage.insert()`; third red: list badge stale after toggle; after fixes `1 passed (3.5s)` |
| 2026-06-12 Automation Designer full host-mode | `tests/e2e/automation/automation-designer-golden.spec.ts` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501`, `E2E_OUTBOUND_HOST=127.0.0.1`, `E2E_CALLAPI_OK_URL=http://127.0.0.1:3501/health` | `32 passed (2.0m)` |
| 2026-06-12 Frontend static multiselect unit | `pnpm exec vitest run app/framework/smart/automation/nodes/__tests__/property-panel-render.test.tsx app/framework/smart/automation/nodes/__tests__/triggers.bpm-event.test.ts` | first red: static `multiselect` saved scalar string and backend returned 422 for `eventTypes`; after fix `2 files / 25 tests passed` |
| 2026-06-12 Backend BPM event bridge seam | `./gradlew :test --tests com.auraboot.framework.bpm.listener.BpmEventAutomationBridgeTest` | first red: constructor / `subscribeToBpmEvents()` missing; after fix `6 tests` passed, including EventBusService subscription, tenant context binding, and dispatch failure isolation |
| 2026-06-12 Backend BPM event processKey matching | `./gradlew :test --tests com.auraboot.framework.automation.trigger.impl.AutomationTriggerServiceImplTest --tests com.auraboot.framework.bpm.listener.BpmEventAutomationBridgeTest` | first red: mapper queried `e2et_payment_approval:1`; after fix `34 tests completed, 0 failed` |
| 2026-06-12 Backend restart proof for BPM event bridge | `AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1 AGENT_LLM_STUB_MODE=true SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun --no-daemon`, log `/tmp/aura-decisionops-console-backend-bpmevent-final.log` | health `{"status":"UP"}`; startup log contains subscriptions for `process_started`, `process_ended`, `task_created`, `task_completed`, `task_assigned` |
| 2026-06-12 Setup/auth/fixtures before BPM event E2E | `pnpm exec playwright test -c playwright.config.ts --project=setup --reporter=line` on host stack `:6443/:5174/:3501` | `15 passed, 1 skipped (2.2s)` |
| 2026-06-12 Automation Designer BPM event targeted | `automation-designer-golden.spec.ts -g "N-TRIGGER-BPM-EVENT"` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501` | first red: static multiselect saved scalar string -> HTTP 422; second red: Spring event bridge not invoked; after EventBusService subscription + processKey normalization `1 passed (3.7s)` |
| 2026-06-12 Automation Designer full host-mode after BPM event | `tests/e2e/automation/automation-designer-golden.spec.ts` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501`, `E2E_OUTBOUND_HOST=127.0.0.1`, `E2E_CALLAPI_OK_URL=http://127.0.0.1:3501/health` | `33 passed (2.2m)` |
| 2026-06-12 Backend inactivity scheduler + derivation seam | `./gradlew :test --tests com.auraboot.framework.automation.scheduler.AutomationSchedulerTest --tests com.auraboot.framework.automation.service.AutomationFlowTriggerDeriverTest --tests com.auraboot.framework.automation.AutomationFlowConfigDerivationIntegrationTest` in `platform` | first red: scheduler fixed delay/initial delay hard-coded; after configurable schedule + flowConfig derivation tests `30 tests` passed |
| 2026-06-12 Frontend advanced group + Automation renderer unit | `pnpm exec vitest run app/plugins/core-designer/components/flow-designer-sdk/__tests__/FlowPropertyPanel.extensions.test.tsx app/framework/smart/automation/nodes/__tests__/property-panel-render.test.tsx app/framework/smart/automation/nodes/__tests__/triggers.bpm-event.test.ts` | first red: no stable `prop-group-toggle-advanced`; after `data-testid` + `aria-expanded` + helper expansion `3 files / 29 tests` passed |
| 2026-06-12 Automation Designer inactivity targeted | `automation-designer-golden.spec.ts -g "N-TRIGGER-INACTIVITY"` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501`, `AUTOMATION_INACTIVITY_FIXED_DELAY_MS=5000`, `AUTOMATION_INACTIVITY_E2E_TIMEOUT_MS=90000` | first red: `inactivityField` hidden under collapsed advanced group; second red: test used stale label `订单日期` while fixture metadata label is `下单日期`; after fixes `1 passed (4.6s)` |
| 2026-06-12 Automation Designer full host-mode after inactivity | `tests/e2e/automation/automation-designer-golden.spec.ts` via quick config, `PW_QUICK_WORKERS=1`, host stack `:6443/:5174/:3501`, `E2E_OUTBOUND_HOST=127.0.0.1`, `E2E_CALLAPI_OK_URL=http://127.0.0.1:3501/health`, inactivity scheduler env override | `34 passed (2.4m)` |
| 2026-06-12 Typecheck after inactivity | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Hard redline on Automation referenced spec after inactivity | grep for `test.skip`, `test.fixme`, `waitForTimeout(`, `retries:`, `waitForEvent(download).catch`, `page.request.put`, `__reactProps` on `automation-designer-golden.spec.ts` | no output |
| 2026-06-12 Lower-bound classification after inactivity | grep for `toBeGreaterThanOrEqual` / `toBeLessThanOrEqual` on `automation-designer-golden.spec.ts` | existing `>=1` / `>=2` are business existence lower bounds for side effects/log rows/receiver hits; not used as coverage-completion percentages |
| 2026-06-12 Backend log classification after inactivity | grep backend log for hard failures; generic `ERROR` lines reviewed | no startup failure / unexpected platform hard error; `ERROR` entries are expected sad-path evidence from dangerous expression, upstream 404/500, unknown field, validation, and permission-denied tests |
| 2026-06-12 Diff hygiene after inactivity | `git diff --check` | passed |
| 2026-06-12 Typecheck after BPM event | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Hard redline on Automation referenced spec after BPM event | grep for `test.skip`, `test.fixme`, `waitForTimeout(`, `retries:`, `waitForEvent('download').catch`, `page.request.put`, `__reactProps` on `automation-designer-golden.spec.ts` | no output |
| 2026-06-12 Lower-bound classification after BPM event | grep for `toBeGreaterThanOrEqual` / `toBeLessThanOrEqual` on `automation-designer-golden.spec.ts` | existing `>=1` / `>=2` are business existence lower bounds for side effects/log rows/receiver hits; not used as coverage-completion percentages |
| 2026-06-12 Backend hard-error scan after BPM event | grep backend log for `Application run failed`, `Unexpected system exception`, `BadSqlGrammar`, `DataIntegrityViolation`, `PSQLException`, `Completed 500`, `NullPointerException` | no output |
| 2026-06-12 Diff hygiene after BPM event | `git diff --check` | passed |
| 2026-06-12 Page Designer workbench runtime E2E | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 pnpm exec playwright test tests/e2e/page-designer/workbench-blocks-runtime.spec.ts --project=chromium` | `20 passed, 1 skipped` (setup gated import skip only); real `/p/c/{pageKey}` browser runtime proof for 7 workbench block types |
| 2026-06-12 Page Designer workbench runtime unit suite | `pnpm exec vitest run packages/core/__tests__/route-manifest.test.ts app/framework/meta/runtime/__tests__/SchemaRuntime.test.ts app/framework/meta/hooks/__tests__/usePageDataSources.test.ts app/framework/meta/rendering/blocks/__tests__/workbench-blocks.test.tsx app/framework/meta/validation/__tests__/block-schema-workbench.test.ts app/shared/services/__tests__/dslRegistryService.test.ts app/plugins/core-designer/components/studio/workbench/designers/areas/__tests__/BlockLibrary.workbench.test.tsx app/plugins/core-designer/components/studio/workbench/designers/areas/__tests__/BlockPreview.workbench.test.tsx` | `8 files / 47 tests passed`; includes `/p/c/:pageKey` route ordering, StrictMode static dataSource rerender, runtime skip-registration boundary |
| 2026-06-12 Page Designer workbench typecheck | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Page Designer workbench e2e-truth redline | grep new spec for `test.skip`, `test.fixme`, `waitForTimeout`, `retries:`, download catch, `__reactProps`, threshold assertions | no output; `page.request.post/put/get` appears only in fixture create/update/publish/readback, not as a replacement for runtime UI assertions |
| 2026-06-12 Backend hard-error scan after Page Designer workbench runtime | grep backend log for `Application run failed`, `Unexpected system exception`, `BadSqlGrammar`, `DataIntegrityViolation`, `PSQLException`, `Completed 500`, `NullPointerException` | no output |
| 2026-06-12 Page Designer standard block runtime unit suite | `pnpm exec vitest run app/ui/schema-renderer/__tests__/BlockRegistry.bootstrap.test.ts app/framework/meta/validation/__tests__/block-schema-workbench.test.ts app/shared/services/__tests__/dslRegistryService.test.ts app/framework/meta/rendering/blocks/__tests__/block-renderer-actions.test.tsx app/framework/meta/rendering/pages/__tests__/DetailPageContent.test.ts` | `5 files / 37 tests passed`; covers 29-type registry bootstrap, fallback validator/registry, `SelectionInfoBlockRenderer`, and `detail-section` page renderer seam |
| 2026-06-12 Page Designer standard block typecheck | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Page Designer standard block runtime E2E | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/page-designer/standard-blocks-runtime.spec.ts --project=chromium` | `21 passed, 1 skipped` (setup gated import skip only); real `/p/c/{pageKey}` custom list/form runtime proof for the remaining 10 non-workbench block types |
| 2026-06-12 Page Designer runtime combined E2E | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/page-designer/workbench-blocks-runtime.spec.ts tests/e2e/page-designer/standard-blocks-runtime.spec.ts --project=chromium` | `22 passed, 1 skipped`; proves Page Designer 17 block-type runtime inventory in one targeted browser run |
| 2026-06-12 Page Designer standard block e2e-truth redline | grep workbench + standard runtime specs for `test.skip`, `test.fixme`, `waitForTimeout`, `retries:`, download catch, `__reactProps`, threshold assertions, debug probes, direct `/p` goto classification | no skip/fixme/fixed wait/retry/threshold/debug output; `page.request` only fixture create/update/publish/readback; direct `/p/c/{pageKey}` is the generated custom page runtime entry, not an API bypass |
| 2026-06-12 Page Designer runtime health precheck | `curl http://127.0.0.1:6443/actuator/health`; `curl http://127.0.0.1:3501/health`; `curl -I http://127.0.0.1:5174/automation/new` | backend `UP`; BFF healthy and backend `UP`; Vite returns expected login `302` |
| 2026-06-12 Dashboard tab reorder fresh run | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/dashboard/dashboard-tab-reorder.spec.ts --project=chromium` | after removing DTR-002/DTR-003 fixme: `23 passed, 1 skipped` (setup gated import skip only); covers page load, drag reorder, persisted user preference after reload, and drag hint |
| 2026-06-12 Dashboard tab reorder e2e-truth redline | grep `dashboard-tab-reorder.spec.ts` for `test.skip`, `test.fixme`, `waitForTimeout`, `retries:`, threshold, debug probes, and `page.request.put` classification | no skip/fixme/fixed wait/retry/debug; three `toBeGreaterThanOrEqual(2)` assertions are permanent business preconditions for a draggable tab bar, not coverage thresholds; `page.request.put` only seeds/clears `dashboard_tab_order` preference for deterministic setup, while DTR-003 waits for the browser drag-triggered PUT and verifies reload persistence |
| 2026-06-12 Dashboard management lifecycle targeted | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/dashboard/dashboard-management.spec.ts --project=chromium -g "DM-E11"` | after enabling lifecycle test and adding per-test cleanup/timeout: `20 passed, 1 skipped` (only setup gated import skip); verifies create fixture -> UI publish -> API status -> UI unpublish -> API status -> UI delete -> row gone |
| 2026-06-12 Dashboard management full fresh run | `PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 BE_PORT=6443 BFF_PORT=3501 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/dashboard/dashboard-management.spec.ts --project=chromium` | after removing DM-E03/E04/E07/E08/E10/E11/DM-E12 fixme and deleting the spec-level page schema PUT patch: `32 passed, 1 skipped`; covers list load/filter, row action visibleWhen, publish/unpublish/delete API responses, row/edit/create navigation to designer, and All/Personal/Global list tabs |
| 2026-06-12 Dashboard management typecheck + e2e-truth redline | `pnpm typecheck`; grep `dashboard-management.spec.ts` for `test.skip`, `test.fixme`, `waitForTimeout`, `retries:`, threshold/debug probes, and `page.request.*` classification | typecheck passed with only existing Vite tsconfig-paths warning; no spec skip/fixme/fixed wait/retry/threshold/debug output; `page.request.post/delete/get/put` is confined to fixture create/cleanup/readback/add-widget and state setup helpers, while lifecycle assertions wait for browser-triggered publish/unpublish/delete responses and UI reload/state changes |
| 2026-06-12 Dashboard widget payload typecheck + redline | `pnpm typecheck`; grep `dashboard-widget-types.spec.ts` for `test.skip`, `test.fixme`, `waitForTimeout`, threshold/retry, `page.request.put`, `__reactProps`, debug output | typecheck passed with only existing Vite tsconfig-paths warning; target spec grep has no output. New `createWidgetDraft` product helper compiles and is used by both click-to-add and drag-to-drop; `WidgetRenderer` exposes `data-widget-type` for exact UI type evidence. |
| 2026-06-12 Dashboard widget payload isolated stack preflight | backend `:6452`, BFF `:3512`, Vite `:5182`; `curl /actuator/health`, `curl /health`, `curl -I /dashboard-designer` | backend `{"status":"UP"}`; BFF healthy with backend `UP`; Vite returned expected auth redirect `302`. Shared `:6443/:5174` was unavailable, so this run used an isolated stack rather than treating shared-stack failure as product failure. |
| 2026-06-12 Dashboard widget payload targeted E2E | `PW_PROFILE=fast PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5182 BACKEND_URL=http://127.0.0.1:6452 BE_PORT=6452 BFF_PORT=3512 BFF_URL=http://127.0.0.1:3512 VITE_PORT=5182 PW_QUICK_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test -c playwright.quick.config.ts dashboard/dashboard-widget-types.spec.ts --project=chromium -g "DW-037"` | `1 passed (1.5s)`; UI click-adds `smart-area-chart`, edits static dataSource, clicks Save, waits for real dashboard save response, reads `/api/dashboards/{pid}`, and asserts exact type/componentType/default size/default visualization/staticData. |
| 2026-06-12 Dashboard widget types full fresh run | same isolated stack, `pnpm exec playwright test -c playwright.quick.config.ts dashboard/dashboard-widget-types.spec.ts --project=chromium` | `37 passed (23.2s)`; palette exact 5/14 label lists, add-to-canvas exact count `+1`, `data-widget-type` exact UI type, property panel labels, and `DW-037` saved payload evidence all pass. |
| 2026-06-12 Report Excel/PDF backend unit | `./gradlew :test --tests com.auraboot.framework.bi.ReportExportServiceTest` in `platform` | `5 tests` passed; first red locked non-table gaps, after fix parses generated XLSX and asserts table sheets plus grouped-table/cross-tab/stat/rich-text/chart/report-text sheets; parses generated PDF with `PDFTextStripper` and asserts table + non-table semantic text; missing `extension.reportDsl` throws validation error |
| 2026-06-12 Report Excel BFF binary proxy unit | `pnpm exec vitest run app/server/services/__tests__/BffProxyService.test.ts` | `6 tests` passed; new case covers `/api/reports/export/excel` and `/api/reports/export/pdf` as binary artifact endpoints; proxy now preserves POST method/body for binary routes |
| 2026-06-12 Report Excel typecheck | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Report Excel isolated stack preflight | backend `:6451`, BFF `:3511`, Vite `:5181`; `curl /actuator/health`, `curl /health`, `curl -I /report-designer` | backend `{"status":"UP"}`; BFF healthy with backend `UP`; Vite returned expected auth redirect `302` |
| 2026-06-12 Report Excel targeted E2E | `PW_PROFILE=fast PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5181 BACKEND_URL=http://127.0.0.1:6451 BE_PORT=6451 BFF_PORT=3511 BFF_URL=http://127.0.0.1:3511 VITE_PORT=5181 PW_WORKERS=1 NO_PROXY=localhost,127.0.0.1 pnpm exec playwright test tests/e2e/designer/report-designer-deep.spec.ts --project=chromium-deep --no-deps -g "RPT-OP-07"` | first run red: BFF JSON proxy re-serialized XLSX bytes as `"PK...`; after binary route fix `1 passed (16.7s)` with UI click -> export response -> download filename -> ZIP header -> workbook sheet/header/rows |
| 2026-06-12 Report PDF targeted E2E | same isolated stack, `report-designer-deep.spec.ts --project=chromium-deep --no-deps -g "RPT-OP-08"` | `1 passed (18.1s)` with UI click -> export response -> download filename -> PDF header/page marker |
| 2026-06-12 Report non-table targeted E2E | isolated stack `:6451/:3511/:5181` with Vite `SPRING_BOOT_URL/BFF_INTERNAL_URL=http://127.0.0.1:6451`, `report-designer-deep.spec.ts --project=chromium-deep --no-deps -g "RPT-OP-09\|RPT-OP-10"` | first run environment-invalid: Vite SSR defaulted to backend `:6443` and redirected to login; after restarting Vite with correct backend env `2 passed (31.6s)`. `RPT-OP-09` parses grouped/cross-tab/stat/rich/chart/report-text XLSX sheets; `RPT-OP-10` downloads PDF, verifies header/page marker, and inflates Flate content stream to assert barcode/watermark text |
| 2026-06-12 Report Operations E2E | same isolated stack, `report-designer-deep.spec.ts --project=chromium-deep --no-deps -g "Report Operations"` | `10 passed (2.6m)`; covers operation buttons, save API trigger, export button visibility, table Excel/PDF artifacts, and non-table static Excel/PDF artifacts |
| 2026-06-12 Report Excel/PDF e2e-truth redline | grep `report-designer-deep.spec.ts` for `test.skip`, `test.fixme`, `waitForTimeout`, threshold/retry, direct `/p/`, and `page.request.*` classification | no skip/fixme/fixed wait/threshold/retry/direct `/p/` output; `page.request.post('/api/pages')` creates deterministic report fixtures and `page.request.delete` cleans them; core artifact evidence is browser export click + download + XLSX parse/PDF header and inflated content-stream assertions |
| 2026-06-12 Automation full first run classification | same spec without host-mode outbound override | environment-invalid for local host stack: default outbound URL targeted `host.docker.internal:6444`; rerun above is the product evidence |
| 2026-06-12 Typecheck | `pnpm typecheck` in `web-admin` | passed; only existing Vite tsconfig-paths warning |
| 2026-06-12 Hard redline on Automation referenced spec | grep for `test.skip`, `test.fixme`, `waitForTimeout(`, `retries:`, `waitForEvent('download').catch`, `page.request.put`, `__reactProps` on `automation-designer-golden.spec.ts` | no output |
| 2026-06-12 Lower-bound classification | grep for `toBeGreaterThanOrEqual` / `toBeLessThanOrEqual` on `automation-designer-golden.spec.ts` | existing `>=1` / `>=2` are business existence lower bounds for side effects/log rows/receiver hits; not used as coverage-completion percentages |
| 2026-06-12 Diff hygiene | `git diff --check` | passed |
| BPMN Designer | `tests/e2e/bpm-designer` via quick config | `21 passed (48.8s)` |
| Page Designer | `designer-deep-operations`, `page-designer-full-lifecycle`, `field-properties`, `smart-components`, `list-config-layout`, `load-existing-page` via quick config | `34 passed (1.1m)` |
| Dashboard Designer | `dashboard-export`, `dashboard-designer-deep`, `dashboard-widget-types`, `dashboard-charts`, `dashboard-interactions` via quick config | `64 passed (56.2s)` |
| Report Designer | `report-designer-smoke`, `designer/report-designer-deep` via quick config | `98 passed (19.9m)` |
| Hard redline on referenced specs | grep for `test.skip`, `test.fixme`, `waitForTimeout(`, `retries:`, `waitForEvent('download').catch`, `page.request.put`, `__reactProps` on only the fresh-run evidence specs above | no output |
| Full redline inventory | same grep across all designer-related specs | still lists old/unreferenced suites and lower-bound assertions; see classification below |
| Diff hygiene | `git diff --check` | passed |

### Evidence Status Snapshot

| 范围 | 本轮可计入 DONE 的证据 | 明确不能计入 DONE 的剩余项 |
|---|---|---|
| Automation Designer | record-create/update/field-change/state-change/scheduled/webhook/bpm-event/inactivity triggers; update/create/notification/execute-command/call-api/send-webhook/start-process/llm-call actions; condition/loop/delay controls; true/false/edge/lifecycle/concurrency/i18n sad/edge/corner paths. Evidence: `automation-designer-golden.spec.ts` 34 pass with real side effects/logs/outbound receiver/scheduler sweep, including `N-DELAY` for `control-delay` UI save + SmartEngine runtime + downstream side effect, `N-START-PROCESS` for process-select + BPM business process start + `manager_review` wait-state correlation, `N-TRIGGER-BPM-EVENT` for BPM `task_assigned` event -> automation log -> downstream order item, and `N-TRIGGER-INACTIVITY` for stale-date/state filters -> scheduler sweep -> automation log -> downstream order item. Backend seam evidence: `AutomationFlowCompilerTest` + `ControlNodeExecutorTest` targeted pass for delay, `StartProcessActionExecutorTest` targeted pass for storage-mode boundary, `BpmEventAutomationBridgeTest` targeted pass for internal subscriber + tenant context, `AutomationTriggerServiceImplTest` targeted pass for versioned processKey matching, `AutomationSchedulerTest` + `AutomationFlowTriggerDeriverTest` + `AutomationFlowConfigDerivationIntegrationTest` targeted pass for inactivity schedule/config derivation. | Legacy `automation-golden.spec.ts` skip/fixme is not evidence. |
| BPMN Designer | Base palette 9 types are covered through L1 designerJson, L2 BPMN XML, and selected L3 runtime: start/end, userTask assignee/form/MI/SLA, serviceTask command/http/rule/notification, receiveTask, exclusive/parallel/inclusive gateway, callActivity. Evidence: `tests/e2e/bpm-designer` 21 pass. | Old `designer-lifecycle.spec.ts` BPMN permission skip and old gateway lifecycle skip are not evidence. |
| Page Designer | Form-page designer operations: drag/sort, property edit, save/publish, undo/redo, preview, field drag, delete, outline, multi-type mix, PageSchema V2 import/export, lifecycle, field/smart component panels, list layout/load-existing/error state. Evidence: targeted Page Designer 34 pass. 2026-06-12 added `workbench-blocks-runtime.spec.ts` for the 7 workbench block types: published custom list page, `/p/c/{pageKey}` runtime load, schema readback, static dataSource rendering, state/context propagation, action-bar visibility transitions, attachment href, and review drawer floating UI. 2026-06-12 added `standard-blocks-runtime.spec.ts` for the remaining 10 non-workbench block types: published custom list/form pages, filter toggle, toolbar, table data, text, stat dataSource, chart canvas, detail-section, selection state, form fields, and form button navigation. | Page Designer 17 block-type runtime inventory rows are DONE. Full Page Designer cartesian coverage is still not complete: component-family depth such as layout/container nesting, sub-table CRUD, upload/download artifact, rowActions/bulk operations, create/update command side effects, and all property-panel combinations remain tracked by the higher-level Page Designer matrix rows. |
| Dashboard Designer | Dashboard designer load, chart palette/config, 14 named widget types add/property-panel checks, data source binding, resize/settings/validation/publish/unpublish/layout, interactions, Excel XLSX artifact parse, PDF header/content marker. Evidence: Dashboard 64 pass. 2026-06-12 added `dashboard-tab-reorder.spec.ts` fresh run with DTR-002/DTR-003 enabled, covering drag reorder and user-preference reload persistence. 2026-06-12 added `dashboard-management.spec.ts` fresh run with DM-E03/E04/E07/E08/E10/E11/DM-E12 enabled, covering row action visibleWhen, publish/unpublish/delete lifecycle, row/edit/create navigation, and All/Personal/Global tab filtering; spec-level page schema PUT patch removed and `onRowClick/detailUrl` lives in setup fixture source. 2026-06-12 added widget payload precision: `dashboard-widget-types.spec.ts` no longer uses `>=1` for add/count, `37 passed`; every add assertion checks exact count `+1` and exact `data-widget-type`, while `DW-037` proves `smart-area-chart` UI add -> save -> backend readback preserves exact registry type/componentType/default size/default visualization/static dataSource. Product fix: click-to-add and drag-to-drop now share `createWidgetDraft`, preserving registry `defaultConfig`. | Registry workbench widgets outside the 14 named widget suite are not individually DONE. 14 named widget add/property presence is stronger, but per-type saved-payload matrix is only closed for `smart-area-chart`; do not claim all 37 widget payload/property combinations DONE. |
| Report Designer | 10 report block types (`data-table`, `grouped-table`, `stat-card`, `rich-text`, `cross-tab`, `chart`, `barcode`, `watermark`, `page-header`, `page-footer`) plus save/export operation smoke/deep. Evidence: Report 98 pass after fixed-wait removal. 2026-06-12 added Report table Excel artifact closure: `ReportExportServiceTest` backend workbook parse, `BffProxyService.test.ts` binary proxy regression, `RPT-OP-07` browser export click + download filename + ZIP header + `xlsx` sheet/header/rows parse. 2026-06-12 added Report table PDF artifact closure: OSS `/api/reports/export/pdf`, backend PDFBox renderer + `PDFTextStripper` parse, `RPT-OP-08` browser export click + download filename + PDF header/page marker. 2026-06-12 added Report non-table static artifact closure: backend workbook/PDF semantic parse for grouped-table/cross-tab/stat/rich-text/chart/barcode/watermark/header/footer, `RPT-OP-09` browser export click + multi-sheet XLSX parse, `RPT-OP-10` browser export click + PDF header/page marker + inflated content-stream text assertions, and Report Operations 10-pass run. Product fixes: saved full Report DSL in `extension.reportDsl`, preserved PageSchema create extension, binary-proxied `POST /api/reports/export/excel|pdf`, replaced the dangling `/api/print/render-html` frontend PDF call with saved-report export, and added semantic export projections for non-table static blocks. | Report table + non-table static Excel/PDF artifacts are DONE, but model/namedQuery/API dataSource rows and PDF visual-fidelity semantics are still TODO. Do not claim Report export all-formats/all-dataSources completion. |

### Truth Review Notes

- 本轮声明只能说“上述 fresh-run evidence specs 通过且 hard redline clean”。不能说“全量设计器笛卡尔积 100% DONE”。
- Full redline 仍列出旧套件的 `test.skip` / `test.fixme` / API PUT setup / lower-bound assertions;这些文件不作为本轮 DONE 证据。
- `toBeGreaterThanOrEqual` 在本轮引用 spec 中只作为业务下限使用,不能用于计算覆盖率或证明某一完整组件族已全部覆盖。`dashboard-widget-types.spec.ts` 的 add/count 阈值已移除,由精确 `+1` / 精确 `data-widget-type` / 精确 5/14 清单和 `DW-037` saved-payload 证据替代。
- 2026-06-12 `control-delay` 的 DONE 只覆盖设计器配置 `duration/unit` → 后端编译/执行 → SmartEngine 节点状态 → 下游副作用闭环。
- 2026-06-12 `action-start-process` 的 DONE 只覆盖设计器配置 `processKey/businessKey/variables` → 保存回显 → Automation webhook runtime → BPM durable process start → by-businessKey status 闭环。
- 2026-06-12 `trigger-bpm-event` 的 DONE 只覆盖设计器配置 `processKey/eventTypes` → 保存回显 → EventBusService internal subscriber → AutomationTriggerService BPM matching → SmartEngine automation runtime → 下游副作用闭环。
- 2026-06-12 `trigger-inactivity` 的 DONE 只覆盖设计器配置 `modelCode/inactivityHours/inactivityField/stateField/inactivityStates` → 保存回显 → configurable scheduler sweep → AutomationTriggerService inactivity matching → SmartEngine automation runtime → 下游副作用闭环。
- 2026-06-12 Page Designer workbench DONE 单独只覆盖 schema-level static dataSource + `/p/c/{pageKey}` runtime for 7 workbench block types,以及 state/context/dataSource 三路联动;它需要和后续 `standard-blocks-runtime.spec.ts` 合并后,才构成 17 个 block-type runtime inventory DONE。
- `workbench-blocks-runtime.spec.ts` 中的 `page.request.post/put/get` 只用于 fixture create/update/publish/readback,因为本轮验证对象是 consumer runtime,不是设计器 UI 建页路径;运行页断言仍由真实浏览器打开 `/p/c/{pageKey}` 完成,不作为 PUT API 绕过 runtime 证据。
- 本轮 Page Designer 产品 seam 修复包括:新增 `/p/c/:pageKey` route manifest 顺序保护,避免 custom page 落入菜单 catch-all;修复 `usePageDataSources` 在 React StrictMode cleanup 后的重新注册与 static dataSource rerender;新增 `SchemaRuntime.skipDataSourceRegistration` 边界,由 page-level manager 统一注册 schema dataSources。
- 2026-06-12 Page Designer standard DONE 只覆盖剩余 10 个非 workbench block 的 published-schema → runtime consumer seam: `filters/form-section/form-buttons/table/detail-section/text/toolbar/selection-info/stat-card/chart-card`。它不等于 Page Designer form/list 族所有属性组合完成。
- `standard-blocks-runtime.spec.ts` 中的 `page.request.post/put/get` 同样只用于 fixture create/update/publish/readback;真实证据是浏览器打开 `/p/c/{pageKey}` 后的 filter toggle、table data、chart canvas、stat value、state mutation、form field 和 form button navigation 断言。
- `standard-blocks-runtime.spec.ts` 的 direct `page.goto('/p/c/{pageKey}')` 是动态发布临时 custom page 的唯一 runtime entry,不是 smoke spec 直达已有菜单页,也不是 API 替代 UI 消费路径。
- 2026-06-12 Dashboard tab reorder DONE 只覆盖 dashboard viewer tab bar: page load, drag reorder, preference persistence after reload, and drag hint. `page.request.put('/api/user-preferences/dashboard_tab_order')` 只用于 deterministic setup/cleanup;核心持久化证据来自浏览器 drag 后等待真实 PUT response 并 reload 回显。
- 2026-06-12 Dashboard management DONE 只覆盖管理列表 lifecycle: filter/list load, row action visibleWhen, publish/unpublish/delete, row/edit/create navigation, and All/Personal/Global tabs. `page.request.post/delete/get/put` 在该 spec 中用于 fixture create/cleanup/readback/add-widget and API state setup;核心完成证据来自浏览器 row action 点击后等待真实 publish/unpublish/delete response、API status readback、UI reload 回显与 row gone 断言。旧 spec-level `PUT /api/pages/{pid}` schema patch 已移除;`dashboard_management_list` 的 `onRowClick/detailUrl` 已固化到 `test-fixtures.setup.ts`。
- 2026-06-12 Dashboard widget payload DONE 只覆盖 14 named widgets 的 palette/add/property presence 收紧,以及 `smart-area-chart` 的 saved payload 精确闭环。`page.request.get('/api/dashboards/{pid}')` 只用于保存后的后端读回证据;核心配置动作是浏览器 UI click-add、static dataSource edit 和 Save click。它不覆盖所有 37 registry widget,也不覆盖每个 widget 的全部 property value matrix。
- 2026-06-12 Report table Excel artifact DONE 只覆盖 saved `extension.reportDsl` 中 table block + static dataSource → `POST /api/reports/export/excel` → BFF binary proxy → browser download → XLSX parse 的链路。`page.request.post('/api/pages')` 只用于创建 deterministic report fixture,`page.request.delete` 只用于清理;核心完成证据来自真实 Report Designer 页面点击 Export Excel 后的 download 和 workbook 解析。
- Report Excel 第一次 E2E 红灯不是测试问题:旧 BFF JSON proxy 将 OOXML bytes 重序列化成 JSON string(`"PK...`),导致下载文件损坏。本轮产品修复为 binary route detection 覆盖 `/api/reports/export/excel` 并保留 POST method/body;该 seam 已由 BFF unit + E2E 双证据覆盖。
- 2026-06-12 Report table PDF artifact DONE 只覆盖 saved `extension.reportDsl` 中 table block + static dataSource → `POST /api/reports/export/pdf` → BFF binary proxy → browser download → PDF header/page marker 的链路;后端单测额外用 `PDFTextStripper` 解析标题、表头和数据行。`page.request.post('/api/pages')` 只用于创建 deterministic report fixture,`page.request.delete` 只用于清理;核心完成证据来自真实 Report Designer 页面点击 Export PDF 后的 download。
- 2026-06-12 Report non-table static artifact DONE 只覆盖 saved `extension.reportDsl` 中 static dataSource / static text 的语义投影:grouped-table 分组、cross-tab pivot、stat 聚合、rich-text 段落、chart category/value 聚合表、barcode/watermark/header/footer 文本 artifact。Excel 证据来自多 sheet workbook parse;PDF 证据来自后端 `PDFTextStripper` 与 E2E 解压 Flate content stream 后的关键文本断言。它不声明 chart/barcode/watermark 的视觉等价渲染,也不覆盖 model/namedQuery/API dataSource rows。
- Report export 仍不能声明全量完成:model/namedQuery/API dataSource rows 尚未补 byte-level artifact 证据;PDF 视觉保真也尚未进入 DONE 范围。
- 前一次 Dashboard/Report 合批失败的根因是后端 `bootRun` 被 SIGTERM 停止后前端重定向到登录页;该失败已通过重启后端、重跑 setup、分批 fresh run 排除,不计产品缺陷。本轮 Report non-table 首次 E2E 红灯根因是 Vite SSR 进程未设置 `SPRING_BOOT_URL/BFF_INTERNAL_URL` 而默认访问旧 `:6443`,修正隔离栈 env 后 targeted 与 Report Operations fresh run 通过。

### Phase 3: P0/P1 补测

优先顺序建议：

1. Automation Designer：因为和规则、SLA、BPM、权限联动最多。
2. Page Designer：17 个 block-type runtime inventory 已完成;下一轮不要继续补同一层,应转向更深的 Page Designer component-family gaps: layout/container nesting, sub-table CRUD, upload/download artifact, table rowActions/bulk, create/update command side effects, and property-panel combinations。
3. Dashboard / Report Designer：Dashboard tab persistence、management row action/list tab lifecycle、Dashboard Excel/PDF artifact、14 named widget add/property presence + `smart-area-chart` saved payload、Report table + non-table static Excel/PDF artifact 已完成;下一轮集中补 registry workbench widgets、剩余 13 个 named widget saved-payload/property value matrix、Report model/namedQuery/API dataSource artifact 证据。
4. BPMN Designer：当前证据较强，优先补 gateway/callActivity/runtime 细节缺口和旧 suite 治理。

### Phase 4: 验证与收口

每个 slice 至少跑：

- `pnpm typecheck`
- targeted Playwright spec
- 对应后端 targeted test，如涉及 runtime / deploy / evaluator
- `/e2e-truth` grep
- `git diff --check`
- 文档回填命令和结果

## 验证命令模板

```bash
cd /Users/ghj/work/auraboot/auraboot/.worktrees/decisionops-console-completion/web-admin

pnpm typecheck

NO_PROXY=localhost,127.0.0.1 \
PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 \
BACKEND_URL=http://127.0.0.1:6443 \
pnpm exec playwright test -c playwright.quick.config.ts \
  tests/e2e/<target-spec>.spec.ts \
  --project=chromium
```

后端 runtime 涉及 BPM / Automation / Decision 时，补对应 Gradle targeted test，不能只跑前端。

## 不可接受的完成声明

以下都不能算完成：

- “全部 pass”但还有 `test.skip` / `test.fixme` 包装产品缺口。
- 只验证按钮 visible，不点击。
- 点击保存只看 toast，不等 API，不反查 DTO/schema。
- 导出只看按钮，不验证 download、文件名、header、解析内容。
- 用 API PUT 改 schema 代替 UI 属性面板。
- shared kernel 测过，就说 Page/BPMN/Automation/Dashboard 都测过。
- 用 `>=1` 证明具体组件类型已覆盖。
- 用 fixed sleep 证明异步 runtime 没发生。
- 只跑 UI，不验证后端 runtime 或 artifact。

## 新窗口交接摘要

当前已完成的前置事实：

- 规则中心收口文档：`docs/backlog/2026-06-11-rule-center-integration-coverage-gap.md`
- 已关闭规则中心 targeted slice：EventPolicy、Automation rule binding、BPM rule binding、Permission ABAC、SLA rule binding、DMN editor deepening、FEEL built-ins、Rollout long-window、Dashboard Excel/PDF artifact。
- 已退役旧 BPMN 假 deep 证据：
  - `designer/bpmn-node-properties.spec.ts`
  - `designer/bpmn-designer-deep.spec.ts`
- 已收紧 Automation legacy deep：
  - 删除 `AT-005` 条件 builder skip 假覆盖。
  - 删除不存在产品契约的 `AT-014 priority` 假覆盖。
  - 收紧 `AT-011/AT-012` API 反查。
  - 替换 validation gate fixed wait。

新窗口不要重复做这些已完成项；应从本文档 Phase 0 开始，先生成完整 inventory 和矩阵，再按 P0/P1 风险补测。
