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
| trigger | record-create / record-update / field-change / state-change / scheduled / webhook / bpm-event | modelCode、event、condition、ruleBinding | 拖入、配置、保存、enable、fire | automation DTO + execution log | TODO |
| action | update-record / create-record / send-notification / execute-command / call-api / send-webhook / start-process / llm-call | target model、field mapping、payload、receiver、fallback | 保存、test run、debug step | side effect + node statuses | TODO |
| control | condition / loop / delay | expression、branch condition、collection mapping、timer | true/false branch、非法表达式、循环边界 | SmartEngine process + logs | TODO |
| edge | true/false/default branch | conditionExpression、sourceHandle | 连线、改条件、删除 | flowConfig edges + runtime branch | TODO |
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
| export | Excel / PDF / JSON if supported | file naming、content | download、parse、round-trip where supported | xlsx/pdf/json artifact | PARTIAL: Excel/PDF done |
| publish/share | global/personal/dashboard visibility | permission、publish state | publish/unpublish、reload | API state + permission | TODO |

### 现有证据起点

- `web-admin/tests/e2e/dashboard/dashboard-export.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-designer-deep.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-widget-types.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-charts.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-interactions.spec.ts`
- `web-admin/tests/e2e/dashboard/dashboard-management.spec.ts`

已知已关闭：

- Excel export artifact：文件名、ZIP header、workbook sheet/rows。
- PDF export artifact：文件名、PDF header、page marker/content marker。

仍需审查：

- `dashboard-management.spec.ts` 中的 fixme 是否是产品缺口、环境债还是已有 spec 覆盖。
- widget types spec 的 `>=1` 是否只是存在性语义，还是应该改为精确 widget type/payload 断言。
- tab reorder 的 preference API / persistence 是否仍为真实产品 gap。

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
| filters | form | list | P1: filter-form 输入、保存、运行页过滤 | TODO |
| form-section | form | form/list | P0: 字段绑定、required、readonly、visibleWhen | TODO |
| form-buttons | form | form | P0: create/update 命令真实触发 | TODO |
| table | display | list | P0: columns/dataSource/rowActions/search/bulk | TODO |
| detail-section | display | form/list | P1: 详情字段回显、raw code 防泄漏 | TODO |
| text | display | list/form | P2: 静态内容保存回显 | TODO |
| toolbar | layout | list | P0: action.command 与命令管道 | TODO |
| selection-info | layout | list | P1: 多选状态与批量工具条 | TODO |
| stat-card | chart | list/form | P1: NQ/API 非空与数值断言 | TODO |
| chart-card | chart | list/form | P1: 图表数据源与渲染 | TODO |
| metric-strip | workbench | list/form | P1: 指标点击筛选联动 | TODO |
| record-inspector | workbench | list/form | P1: 选中行联动详情 | TODO |
| candidate-list | workbench | list/form | P1: 候选选择写回状态 | TODO |
| workbench-action-bar | workbench | list/form | P0: 导出/下载/命令行动点 | TODO |
| evidence-panel | workbench | list/form | P1: raw payload/证据渲染 | TODO |
| artifact-timeline | workbench | list/form | P1: 附件/导出产物历史 | TODO |
| review-drawer | workbench | list/form | P1: 浮层复核、候选确认 | TODO |

### Automation Designer Inventory

| 类型 | 分类 | 属性字段 | P0/P1 风险 | 本轮证据状态 |
|---|---|---|---|---|
| trigger-record-create | trigger | modelCode | P0: create event fire | TODO |
| trigger-record-update | trigger | modelCode, watchFields | P0: update event fire / create 不误触发 | TODO |
| trigger-field-change | trigger | modelCode, fieldCode, fromValue, toValue | P0: watched field 与 non-watched field | TODO |
| trigger-state-change | trigger | modelCode, stateField, fromStates, toStates | P0: dict-backed state filter | TODO |
| trigger-scheduled | trigger | cron, timezone, maxExecutionTime | P1: scheduler fire / no fixed sleep fake pass | TODO |
| trigger-webhook | trigger | secret, validationMode, expectedHeaders | P0: inbound webhook real POST | TODO |
| trigger-bpm-event | trigger | modelCode, eventTypes | P1: BPM event consumer seam | TODO |
| trigger-inactivity | trigger | modelCode, inactivityHours, inactivityField, stateField, inactivityStates | P1: scheduled inactivity sweep | TODO |
| action-update-record | action | modelCode, recordId, fields | P0: side effect + sad invalid field | TODO |
| action-create-record | action | modelCode, fields | P0: child record created + invalid field sad | TODO |
| action-send-notification | action | notificationType, title, content, recipients | P1: notification runtime completion | TODO |
| action-execute-command | action | commandCode, params | P0: command-select picker + restricted principal sad | TODO |
| action-call-api | action | url, method, headers, body | P0: outbound success/failure | TODO |
| action-send-webhook | action | url, payload | P0: outbound receiver payload + 500 sad | TODO |
| action-start-process | action | processKey, businessKey, variables | P1: BPM runtime side effect | TODO |
| action-llm-call | action | model, prompt fields, outputVariableName, imageVariableNames | P1: built-in stub provider + persisted output; external key not required | TODO |
| control-condition | control | expression | P0: true/false/edge boundary | TODO |
| control-delay | control | duration, unit | P1: delayed runtime semantics | DONE: 2026-06-12 `N-DELAY` browser/backend runtime seam, compiler maps to `delay`, executor consumes `duration/unit` |
| control-loop | control | collection, itemVariable | P1: non-empty and empty collection | TODO |

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
| dashboard | smart-number-card | title, icon, suffix, showTrend | P0: add/save/reload + data source | TODO |
| dashboard | smart-bar-chart | title, horizontal, stacked | P0: add/save/reload + data source | TODO |
| dashboard | smart-line-chart | title, smooth, showArea | P0: add/save/reload + data source | TODO |
| dashboard | smart-pie-chart | title, donut, showLabels | P0: add/save/reload + data source | TODO |
| dashboard | smart-area-chart | title, smooth, fillOpacity | P1: property panel + render | TODO |
| dashboard | smart-funnel-chart | title, sort | P1: property panel + render | TODO |
| dashboard | smart-scatter-chart | title, bubbleMode | P1: property panel + render | TODO |
| dashboard | smart-radar-chart | title, shape, showArea | P1: property panel + render | TODO |
| dashboard | smart-table-chart | title, pageSize, striped | P0: table rows + data source | TODO |
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
| dashboard | smart-wordcloud-chart | title, shape, colorTheme, gridSize | P1: property panel + render | TODO |
| dashboard | smart-combo-chart | title, smooth, stack, dataZoom, axes | P1: property panel + render | TODO |
| dashboard | smart-nps-chart | title, scoreField, legend, ringWidth | P1: property panel + render | TODO |
| dashboard | smart-gallery | title, columns, image/title/description fields | P1: property panel + render | TODO |
| dashboard | smart-kanban | title, group/title/description fields | P1: property panel + render | TODO |
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
| report | table | dataSource, columns | P0: save/API/export artifact | TODO |
| report | grouped-table | dataSource, groupByField, columns | P1: save/API/render | TODO |
| report | stat-card | dataSource, valueField, aggregation | P1: save/API/render | TODO |
| report | rich-text | content, align | P1: save/API/render | TODO |
| report | cross-tab | rowField, columnField, valueField | P1: save/API/render | TODO |
| report | chart | chartType, categoryField, valueField | P1: save/API/render | TODO |
| report | barcode | format, staticValue | P1: save/API/render | TODO |
| report | watermark | text, rotation, opacity | P1: save/API/render | TODO |
| report | page-header | header band | P1: save/API/render | TODO |
| report | page-footer | footer band | P1: save/API/render | TODO |

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
| Automation Designer | `automation-designer-golden.spec.ts` 已清掉本轮引用路径里的 fixed wait,并用真实 browser/backend runtime fresh run 通过。2026-06-12 新增 `control-delay` 闭环:先用 `AutomationFlowCompilerTest` / `ControlNodeExecutorTest` 红绿验证编译与执行契约,再用 `N-DELAY` 真浏览器拖拽、属性面板、保存回显、webhook 触发、节点状态、下游副作用证明 runtime seam。`automation-golden.spec.ts` 仍有 loop / llm / start-process skip/fixme。 | 只引用 `automation-designer-golden.spec.ts`;旧 `automation-golden.spec.ts` skip/fixme 不计完成证据。 |
| Page Designer | `designer-deep-operations.spec.ts` 有拖拽、属性编辑、保存发布、撤销重做、预览、删除、导入导出; `unified-designer-kind-and-binding.spec.ts` 与 toolbar permissions 有 seed/环境前置 skip。 | 本轮引用 deep/lifecycle/field/smart/list/load-existing fresh run; seed 前置 skip 不计完成证据。 |
| Dashboard Designer | `dashboard-widget-types.spec.ts` 多处 `>=1` 是“添加后画布至少出现该类 widget”的存在性断言; `dashboard-management.spec.ts` / `dashboard-tab-reorder.spec.ts` 有 PUT setup 与 fixme; `dashboard-export.spec.ts` 已覆盖 Excel/PDF artifact。 | 本轮引用 widget/chart/deep/interactions/export fresh run; management/tab persistence 仍为 P1 gap,不计 DONE。 |
| BPMN Designer | `web-admin/tests/e2e/bpm-designer/*` 覆盖 userTask/serviceTask/gateway/callActivity/SLA 等 L1/L2/L3;旧 `designer-lifecycle.spec.ts` BPMN 段仍受 permission skip 影响。 | 新 bpm-designer 目录为主要证据;旧 lifecycle skip 不计完成证据。 |
| Report Designer | `report-designer-deep.spec.ts` 的 fixed wait 已替换为 UI/API active wait,并与 `report-designer-smoke.spec.ts` fresh run 通过。 | Report 10 类 block + 操作 smoke/deep 作为本轮证据;旧 lifecycle 不是主证据。 |

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
| Automation Designer | record-create/update/field-change/state-change/scheduled/webhook triggers; update/create/notification/execute-command/call-api/send-webhook/llm-call actions; condition/loop/delay controls; true/false/edge/lifecycle/concurrency/i18n sad/edge/corner paths. Evidence: `automation-designer-golden.spec.ts` 31 pass with real side effects/logs/outbound receiver, including `N-DELAY` for `control-delay` UI save + SmartEngine runtime + downstream side effect. Backend seam evidence: `AutomationFlowCompilerTest` + `ControlNodeExecutorTest` targeted pass. | `trigger-bpm-event`, `trigger-inactivity`, `action-start-process` remain matrix gaps unless a future spec adds real runtime evidence. Legacy `automation-golden.spec.ts` skip/fixme is not evidence. |
| BPMN Designer | Base palette 9 types are covered through L1 designerJson, L2 BPMN XML, and selected L3 runtime: start/end, userTask assignee/form/MI/SLA, serviceTask command/http/rule/notification, receiveTask, exclusive/parallel/inclusive gateway, callActivity. Evidence: `tests/e2e/bpm-designer` 21 pass. | Old `designer-lifecycle.spec.ts` BPMN permission skip and old gateway lifecycle skip are not evidence. |
| Page Designer | Form-page designer operations: drag/sort, property edit, save/publish, undo/redo, preview, field drag, delete, outline, multi-type mix, PageSchema V2 import/export, lifecycle, field/smart component panels, list layout/load-existing/error state. Evidence: targeted Page Designer 34 pass. | Full per-block 17-type cartesian coverage is not complete. Workbench-specific blocks such as `metric-strip`, `record-inspector`, `candidate-list`, `evidence-panel`, `artifact-timeline`, `review-drawer` still need typed runtime assertions before marking their inventory rows DONE. |
| Dashboard Designer | Dashboard designer load, chart palette/config, 14 named widget types add/property-panel checks, data source binding, resize/settings/validation/publish/unpublish/layout, interactions, Excel XLSX artifact parse, PDF header/content marker. Evidence: Dashboard 64 pass. | `dashboard-management.spec.ts` and `dashboard-tab-reorder.spec.ts` still contain API PUT setup/fixme; tab-order preference persistence and row-management flows remain P1 gaps. Registry workbench widgets outside the 14 named widget suite are not individually DONE. |
| Report Designer | 10 report block types (`data-table`, `grouped-table`, `stat-card`, `rich-text`, `cross-tab`, `chart`, `barcode`, `watermark`, `page-header`, `page-footer`) plus save/export operation smoke/deep. Evidence: Report 98 pass after fixed-wait removal. | Report export artifact is only smoke/API-prompt level here; byte-level Excel/PDF parsing is covered in Dashboard export, not Report. Add report-specific artifact parsing before claiming Report export artifact DONE. |

### Truth Review Notes

- 本轮声明只能说“上述 fresh-run evidence specs 通过且 hard redline clean”。不能说“全量设计器笛卡尔积 100% DONE”。
- Full redline 仍列出旧套件的 `test.skip` / `test.fixme` / API PUT setup / lower-bound assertions;这些文件不作为本轮 DONE 证据。
- `toBeGreaterThanOrEqual` 在本轮引用 spec 中只作为业务下限或添加后存在性断言使用,不能用于计算覆盖率或证明某一完整组件族已全部覆盖。
- 2026-06-12 `control-delay` 的 DONE 只覆盖设计器配置 `duration/unit` → 后端编译/执行 → SmartEngine 节点状态 → 下游副作用闭环;它不代表 `trigger-bpm-event`、`trigger-inactivity`、`action-start-process` 已完成。
- 前一次 Dashboard/Report 合批失败的根因是后端 `bootRun` 被 SIGTERM 停止后前端重定向到登录页;该失败已通过重启后端、重跑 setup、分批 fresh run 排除,不计产品缺陷。

### Phase 3: P0/P1 补测

优先顺序建议：

1. Automation Designer：因为和规则、SLA、BPM、权限联动最多。
2. Page Designer：因为 DSL 页面和 typed custom block 依赖它。
3. Dashboard Designer：补 widget property / management / tab persistence。
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
