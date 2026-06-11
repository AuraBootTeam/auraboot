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

### Phase 2: 旧测试真实性审计

对每个设计器列：

- 当前能作为证据的 spec
- 必须退役的 spec
- 必须修的 spec
- 可暂时标 N/A 的项

任何退役都必须写“替代证据是什么”；没有替代证据则不能退役，只能标 TODO 或产品缺口。

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
