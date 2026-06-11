---
type: backlog
status: completed
created: 2026-06-11
relates_to:
  - docs/architecture/decision-runtime-and-expression-strategy.md
---

# 规则中心跨模块联动补测与收口方案

## 背景

Automation、BPM、SLA、Permission、EventPolicy 都在消费规则中心能力。正确方向不是每个模块各写一套条件 UI，而是沉淀为平台级 `ConditionSpec + DecisionBinding + usage-index + trace/test-runner` 能力，再由 DSL 页面或 typed custom block 复用。

这份文档覆盖当前“补齐真实测试和必要平台能力”的收口范围。DMN 决策表编辑器深化已在 Round 8 关闭 P1 闭环；灰度发布长窗口指标容量设计已在 Round 9 关闭真实栈闭环；FEEL 内置函数 parity 已在 Round 10 关闭浏览器 + 后端闭环。全量设计器极限矩阵仍属于后续独立 scope，不能用这份补测文档宣称已经完成。

## 痛点

1. 浏览器 E2E 已覆盖 Automation/BPM/Permission/EventPolicy 的 targeted slice，但 SLA 作为规则中心消费方缺少同等级 UI 配置闭环。
2. 条件构造器已经支持复杂 `AND / OR / NOT`，但完成声明必须继续区分：已测 slice 与全应用矩阵不能混为一谈。
3. 字段 picker 不能只来自静态 props。规则中心的字段目录需要从后端 catalog 进入可选字段，并被保存为 `RuleValueSource.field(...)`。
4. 导出类动作不能只验证按钮可见，必须验证 download 事件、建议文件名、扩展名、MIME/内容结构。
5. SLA、规则、设计器这类配置型能力必须同时有浏览器证据和后端证据；只测 UI 保存或只测 API 都是 partial。

## 架构方案

### 平台契约

- `ConditionSpec`: 表达条件树，支持 `group(AND/OR)`、`not`、`compare(field op value)`。
- `DecisionBinding`: 表达决策引用、版本策略、输入映射、输出映射、fallback、trace mode。
- `RuleConsumerBinding`: 消费方统一封装，`consumerType` 包含 `AUTOMATION / BPM / SLA / PERMISSION / EVENT_POLICY`。
- `DecisionRuleBindingBlock`: DSL-hosted typed custom block。普通页面继续 DSL，block 负责条件/决策引用/impact/test-runner 逃逸。
- `usage-index`: 扫描消费方引用，输出 `decision -> consumers` 和 `field -> references`，用于 blast-radius。

### 字段目录

`/api/decision/model/fields` 当前从已校验/发布的决策版本 `fieldRefsJson` 推导字段目录。规则中心 block 需要支持按需合并该 catalog，避免各模块只能使用硬编码字段 props。

实施策略：

- EventPolicy designer 已默认加载 model fields。
- `DecisionRuleBindingBlock` 新增 `fieldCatalogMode`：
  - `disabled`: 不请求 catalog。
  - `fallback`: 无显式 fields 时使用 catalog + 默认字段。
  - `merge`: 显式 fields 与 catalog 合并。
- SLA DSL 表单设置 `fieldCatalogMode: "merge"`，证明 SLA mapping picker 能吃到后端 catalog 字段。

### 导出 artifact

DMN table 的“导出 DMN XML”需要触发真实浏览器 download，而不只是把 XML 写进 textarea。导出后仍保留 textarea 预览，并下载 `{decisionCode}.dmn.xml`。

## 覆盖矩阵

| 模块 | 行动点 | Browser evidence | Backend evidence | Artifact | 状态 |
|---|---|---|---|---|---|
| EventPolicy | UI 配置 `AND + OR + NOT`，保存/校验/发布/运行 | `event-policy-complex-condition.spec.ts` 已覆盖 designer 配置、发布、run | API 反查 `rulesJson`、`/event-policy/run` true/false、field impact | 截图 | DONE |
| Automation | Designer 属性面板引用规则中心并保存 | `automation/rule-binding-designer-host.spec.ts` 已覆盖拖入 trigger、属性面板配置、保存 | API 反查 automation trigger rule binding、impact graph | 截图 | DONE |
| BPM | Gateway/UserTask 属性面板引用规则中心并保存 | `bpm/rule-binding-designer-host.spec.ts` 已覆盖 gateway/userTask | API 反查 process designerJson、impact graph | 截图 | DONE |
| Permission | `/enterprise/permissions` 复用权限平台，ABAC 使用规则中心 | `permission/permission-abac-rule-center.spec.ts` 已覆盖 UI host | DB/API 反查 role permission condition、impact graph | 截图 | DONE |
| SLA | SLA 配置表单引用规则中心、字段 catalog、保存、回显、impact/test-runner | `bpm/sla-rule-center-binding.spec.ts` 已覆盖 UI 配置、字段 catalog、test-runner、保存后 reload 回显 | `/api/bpm/sla-configs/{pid}`、usage-index、field impact；`SlaDecisionE2EIntegrationTest` 证明 runtime deadline | 截图 | DONE |
| DMN | 决策表编辑器深化：列管理、FEEL cell、COLLECT+SUM、PRIORITY、analysis、保存、DMN 导入/导出/round-trip | `decisionops/dmn-export-artifact.spec.ts` 覆盖下载；`decisionops/dmn-table-editor-deep.spec.ts` 覆盖真实编辑与运行 | `/api/decision/test-run`、`/api/decision/versions/{pid}`、`/api/decision/tables/*`；后端 evaluator/date FEEL 单测 | `.dmn.xml` 文件名、hitPolicy、outputValues、submittedOn 内容 | DONE |
| FEEL built-ins | DMN cell literal 函数与 Condition AST 函数白名单 parity：`date(...)`、`time(...)`、`date and time(...)`、`duration(...)` | `decisionops/dmn-feel-builtins.spec.ts` 覆盖真实浏览器编辑、local diagnostics、analysis、test-run、保存反查 | `DecisionTableEvaluatorTest`、`ConditionAstEvaluatorTest`、`DecisionRuntimeControllerIntegrationTest` 覆盖 parse/evaluate/validate | analysis payload、runtime outputs、无 `DMN_UNSUPPORTED_FEEL`，截图 `dmn-feel-builtins.png` | DONE |
| Rollout | 灰度发布配置、确定性分流、长窗口指标、promote 审计 | `decisionops/decisionops-rollout-long-window.spec.ts` 覆盖 DSL 页创建/激活、2160h/5m metrics 控件、promote 确认 | `/api/decision/evaluate` ROLLOUT、`/api/decision/logs/recent` 分支日志、`/api/decision/rollouts/{pid}/metrics` 预聚合结果、后端 rollout IT | 截图、metrics payload | DONE |

## 本轮实现修复

1. `DecisionRuleBindingBlock` 支持从后端字段目录合并 mapping picker 字段，SLA DSL 使用 `fieldCatalogMode: "merge"`。
2. `DecisionRuleBindingBlock` 支持动态表单的 JSONB envelope 形态：`{ type: "jsonb", value: "{...}" }`，保存后 reload 能回显规则中心绑定。
3. `FormPageContent` 的 custom block runtime 支持 snake_case / camelCase 读取 fallback，避免 DSL `valueField: "rule_binding"` 被后端 DTO `ruleBinding` 命名转换打断。
4. SLA 表单不再把 `warning_rules` 暴露给通用表单或 command `inputFields`，避免普通 JSON 控件把数组列误写成对象。
5. DMN 导出按钮触发真实浏览器 download，文件名为 `{decisionCode}.dmn.xml`，同时保留 textarea 预览。

## 验证结果

- Browser E2E targeted slice: 7 passed
  `automation/rule-binding-designer-host.spec.ts`
  `bpm/rule-binding-designer-host.spec.ts`
  `bpm/sla-rule-center-binding.spec.ts`
  `decisionops/dmn-export-artifact.spec.ts`
  `decisionops/event-policy-complex-condition.spec.ts`
  `permission/permission-abac-rule-center.spec.ts`
- Frontend unit: 31 passed
- Frontend typecheck: passed
- Backend targeted tests: passed
  `SlaDecisionE2EIntegrationTest`
  `SlaDecisionDeadlineTest`
  `DecisionUsageIndexServiceImplTest`
  `SlaConfigServiceTest`

本地真实栈验证前发现 dev 数据库未初始化 bootstrap 且缺少 2026-06-09/2026-06-11 迁移表列；已通过标准 `/api/bootstrap/setup` 与已有 migration SQL 修复本地环境后完成 E2E。产品代码依赖的 migration 文件已经在仓库中。

## 验收标准

1. SLA E2E 必须通过 UI 修改 `rule_binding`，保存后 API 反查相同 `decisionBinding`。
2. SLA E2E 必须证明 `fieldCatalogMode: merge` 生效：先发布带唯一 fieldRef 的决策，再在 SLA mapping picker 看到该 fieldRef。
3. SLA E2E 必须运行 test-runner，拿到后端 `/api/decision/evaluate` 响应，且重建 usage-index 后 impact 中出现 `SLA_RULE`。
4. DMN 导出 E2E 必须捕获 download，验证文件名、扩展名、非空内容、XML 根节点和 `decisionTable` 内容。
5. 最终声明必须经过 `/e2e-feature-coverage` 思路矩阵和 `/e2e-truth` grep 审查；如果仍有 P1/P2 项未做，措辞必须是“targeted slice 达成”，不能说全平台所有矩阵完成。

## 后续任务推进记录

### 2026-06-11 Round 2: 业务 model metadata 字段目录

状态: DONE。

痛点: 上一轮 `DecisionRuleBindingBlock` 已能合并 `/api/decision/model/fields`，但该 endpoint 主要从已校验/发布决策版本的 `fieldRefsJson` 推导字段。这样只能选择“已经被某个决策引用过”的字段，不能证明 Automation/BPM/SLA/Permission/EventPolicy 的条件字段 picker 已和平台业务模型元数据打通。

方案:

- 后端 `DecisionModelFieldServiceImpl` 先扫描当前租户已发布业务模型和字段绑定。
- 业务模型字段统一投影成规则运行时可消费的 `record.data.<fieldCode>` 路径，保持现有 `ConditionSpec` scope 契约兼容。
- label 使用 `模型显示名 / 字段显示名`，dataType 使用字段元数据类型。
- 再叠加已发布决策版本的 `fieldRefsJson`，保留 `refs` 和 `decisionCodes` 影响分析信息。
- 前端仍复用同一个 `DecisionRuleBindingBlock` 和 DSL host，不新增 React 页面。

验证:

- `DecisionRuntimeControllerIntegrationTest.httpModelFields_includesPublishedMetaModelFieldsWithoutDecisionRefs` 创建真实 meta model + field + binding，不创建任何决策版本，断言 `/api/decision/model/fields` 返回 `record.data.<fieldCode>` 且 `refs=0`、`decisionCodes=[]`。
- `bpm/sla-rule-center-binding.spec.ts` 增加 browser 断言，要求字段 catalog 同时包含决策 fieldRef 和来自 `sla_config.deadline_value` 的业务模型字段。
- 后端完整类复跑: `./gradlew :test --tests com.auraboot.framework.decision.DecisionRuntimeControllerIntegrationTest --no-daemon` -> BUILD SUCCESSFUL。
- 前端类型检查: `pnpm typecheck` -> passed。
- 浏览器真实栈复跑: `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/bpm/sla-rule-center-binding.spec.ts --project=chromium` -> 1 passed。
- `/e2e-truth` 红线自查: 本轮相关 6 个 browser specs 未发现 `test.skip`、`test.fixme`、`waitForTimeout`、阈值放宽、`/p/` 直跳等假通过特征。

### 2026-06-11 Round 3: SLA breach/escalation 与规则中心 deadline 闭环

状态: DONE。

痛点: SLA 已有独立 scheduler 覆盖，规则中心也已有 deadline 决策覆盖，但缺一条直接证明“规则中心 decision binding 计算 deadline 后，SLA record 进入 scheduler，再触发 overdue/escalation”的集成回归。只测 deadline 生成或只测 scheduler 升级都不能证明两段链路已经接上。

方案:

- 复用现有 `SlaActivationListener`、`RuleEvaluationService`、`SlaSchedulerService`，不新增 SLA runtime。
- 新增后端集成用例：发布真实决策表，SLA `ruleBinding` 引用该决策生成 deadline，发布真实 `task_assigned` 事件创建 SLA record。
- 用数据库时间回拨模拟长窗口已过，避免浏览器或后端测试用 sleep 等待 60 秒。
- 调用真实 scheduler scan，断言 record 进入 `overdue`，warning history 记录 `escalate`，并生成 `SLA ESCALATION` urge 通知。
- 修复 `workflow-demo` 浏览器 fixture 的角色幂等性：已有 `wd_manager` / `wd_hr` 用户如果缺 domain role，helper 先查 tenant member PID，再统一补 domain role 与 `tenant_admin`，不再依赖“用户已经在 domain role 成员列表里”的脆弱假设。
- 浏览器真实逾期流程继续复用 `workflow-demo/wd-leave-sla-escalation.spec.ts`，覆盖 UI 登录、请假提交、BPM 实例、SLA record 创建、scheduler 逾期轮询和详情页加载。

验证:

- `./gradlew :test --tests com.auraboot.framework.decision.SlaDecisionE2EIntegrationTest --no-daemon` -> BUILD SUCCESSFUL，3 tests passed。
- `./gradlew :test --tests com.auraboot.framework.bpm.SlaSchedulerServiceTest --no-daemon` -> BUILD SUCCESSFUL，14 tests passed。
- 当前 dev 环境先用 `scripts/import-plugins.sh --backend-url=http://127.0.0.1:6443 --edition=oss workflow-demo` 补齐 workflow-demo 插件命令，引用完整性检查 OK。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/workflow-demo/wd-leave-sla-escalation.spec.ts --project=chromium` -> 1 passed，36.6s。
- `pnpm typecheck` -> passed。
- `/e2e-truth` 红线自查: 本轮相关 workflow-demo / SLA specs 与 helper 未发现 `test.skip`、`test.fixme`、`waitForTimeout`、阈值放宽、`/p/` 直跳等假通过特征。

### 2026-06-11 Round 4: Dashboard 设计器导出 artifact 闭环

状态: DONE。

痛点: 设计器类页面不能只测“按钮可见”。Dashboard designer 已有 widget 类型、属性面板、保存、发布等覆盖，但 `dashboard-export.spec.ts` 只断言 PDF/Excel 按钮出现和空 widgets 时 disabled，没有证明导出事件、文件名、扩展名、文件字节和 workbook 内容。

方案:

- 复用现有 Dashboard designer 和 `DashboardExportExcel`，不新增导出内核。
- 给 `DataSourceConfig` 补稳定 test id：`dashboard-datasource-type-select`、`dashboard-datasource-static-json`，避免 E2E 依赖中文 label 或 DOM 层级。
- 浏览器中真实添加“数据表格” widget，通过属性面板把数据源改成 `static`，写入固定 JSON 数据。
- 保存 dashboard 后点击 Excel 导出，捕获 Playwright download。
- 校验建议文件名为 `{dashboardTitle}.xlsx`，文件头为 ZIP `PK`，并用 `xlsx` 解析 workbook，断言 sheet 名和行数据精确匹配 UI 配置的静态数据。

验证:

- `dashboard/dashboard-export.spec.ts` 新增 `EXP-03`，覆盖 UI 配置静态数据 -> 保存 -> 真实 download -> workbook 解析。
- 刷新当前 dev 栈 admin session 后复跑: `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 ... pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/dashboard/dashboard-export.spec.ts --project=chromium` -> 3 passed。
- `pnpm typecheck` -> passed。
- `/e2e-truth` 红线自查: 本轮相关 spec / component 未发现 `test.skip`、`test.fixme`、实际 `waitForTimeout(`、阈值放宽、`page.request` 绕 UI 等假通过特征。
- 该补测只关闭 Dashboard Excel artifact gap，不替代 Page/BPMN/Automation/Dashboard 全量设计器矩阵。

### 设计器覆盖矩阵快照

| 设计器 | 当前闭环状态 | Browser evidence | Backend evidence | Artifact evidence | 备注 |
|---|---|---|---|---|---|
| Page Designer | DONE | `designer-deep-operations.spec.ts` 覆盖 block 操作、保存/发布、预览、删除、outline、multi-block、import/export | `PD-010` 保存后 GET `/api/pages/{pid}` 反查 block ids 与 pageKey | `{pageKey}.page.json`，校验 `exportVersion=2.0.0`、schema kind/layout/blocks | Round 6 关闭 PageSchema artifact gap |
| BPMN Designer | DONE | `designers.spec.ts` 覆盖 9 类 palette、连接、BPMN import/export JSON 与 version history；`bpm-designer/designer-node-property-matrix.spec.ts` 覆盖 start/user/service/gateway/callActivity/edge 属性矩阵；`designer-receivetask.spec.ts` 覆盖 receiveTask | 导入后 UI 回显 key/name；新增矩阵保存后 GET `/api/bpm/process-definitions/{pid}` 反查 node/edge config，并 deploy 后 GET `/bpmn` 反查 XML | JSON 文件名和 `key/name/description/nodes/edges`；BPMN XML 校验 `commandServiceTaskDelegate`、`calledElement`、`aura.callMappings`、conditionExpression、receiveTask | Round 7 退役旧 `designer/bpmn-node-properties.spec.ts`；Round 12 退役旧 `designer/bpmn-designer-deep.spec.ts`，由分层 targeted specs 承担覆盖 |
| Automation Designer | DONE | `automation-designer-deep.spec.ts` 覆盖 palette、属性、保存、启停、export、Test Run、Debug Step | API 反查 automation/logs/debug session；后端单元覆盖 flat actions deploy 和 debug JSONB | flow JSON export 校验文件名、name/description/nodes/edges/config | Round 6 关闭 Test Run/Debug runtime gap |
| Dashboard Designer | DONE | `dashboard-export.spec.ts` 覆盖保存后 export buttons、Excel export、PDF export | 保存 dashboard 后导出读取当前 widget config | `{dashboardTitle}.xlsx` 校验 ZIP header/sheet/rows；`{dashboardTitle}.pdf` 校验 PDF header/page/content marker | Round 11 关闭 PDF artifact gap |

### 2026-06-11 Round 5: Automation / BPMN artifact 收紧

状态: DONE。

痛点:

- BPMN `F4-E20` 原来 `waitForEvent('download').catch(() => null)`，没有 download 也能通过；且导出 payload 没带导入后的 `description/category`。
- Automation `AUD-DB-01` 原来只验证 Debug/Test Run/Export 任一按钮可见，不证明导出文件存在和内容正确。
- Automation deep 的后端 setup/反查走相对 `/api`，在当前 dev 栈里曾拿到 SPA HTML 后 `.json()` 崩溃，说明测试证据会受 BFF/代理漂移影响。

方案:

- BPMN: `F4-E20` 等待 `window.__bpmnDesignerStore`，确保 React hydration 完成后再上传 JSON；导入固定流程后校验 UI name/key 回显；导出必须捕获 download，解析 JSON 并校验 `key/name/description/nodes/edges`。
- BPMN: `BPMNDesigner.handleExport` 加回 `description/category`，让 import/export round-trip 不丢流程元数据。
- Automation: `AUD-DB-01` 点击真实 `btn-export-automation`，捕获 download，解析 JSON，校验当前 UI 名称对应文件名，校验 flowConfig 的 3 个节点、2 条边、trigger config、action config。
- Automation: 后端 setup、启停、toggle、反查、logs 统一直连 `BACKEND_URL` 并显式带 admin JWT；新增 `readAutomationApi`，若返回 HTML/非 JSON 直接报错。
- 收紧旧浅断言：删除 Flow designer save 的 `test.skip`，移除 BPMN DnD `>=0` 空断言，Automation 固定节点数/actions 列表改为精确断言。

验证:

- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/designer/automation-designer-deep.spec.ts --project=chromium` -> 15 passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/designer/designers.spec.ts --project=chromium --grep "F4-E11|F4-E12|F4-E15|F4-E20"` -> 5 passed。
- `pnpm typecheck` -> passed。

### 2026-06-11 Round 6: Page Designer artifact + Automation runtime/debug 闭环

状态: DONE。

痛点:

- Page Designer toolbar 暴露了导入/导出按钮，但未向 toolbar 注入 `onImport/onExport`，实际按钮是 no-op；旧测试只覆盖设计器编辑/保存，没有验证 PageSchema 文件名、内容、导入后保存和 API 反查。
- Automation Test Run / Debug 之前停留在按钮存在或局部 API 可达，缺少“浏览器点击按钮 -> 后端执行 -> 日志/session 反查”的闭环。
- UI Test Run 暴露后端契约问题：`/trigger` 只能接 `Map<String,String>`，前端传 `{ context: {} }` 会 400；Debug create session 无 body 会 400。
- Trigger runtime 已统一走 SmartEngine，但 `enable()` 只 deploy visual flow，flat actions 自动化启用后运行时找不到 `auto_{pid}` process。
- Debug Step 从 DB 反查 session 时，`executionContext/actionResults` 的 JSONB select 依赖自动映射，可能出现 null context 或 action result 字段丢失，导致 Step 500 或 GET session 缺 `status`。

方案:

- Page Designer 继续复用平台 PageSchema V2，不新增 React 整页；`PageDesignerEditorImpl` 给 toolbar 接入真实 `onImport/onExport`。
- 导出 payload 统一为 `{ exportVersion, exportedAt, metadata, schema }`，文件名为 `{pageKey}.page.json`；导入只接受 PageSchema V2，并保留当前 page identity / modelCode。
- Automation `triggerManually` 接受可选 `Map<String,Object>` body；Debug create session 接受空 body，默认 `DebugSessionCreateRequest`。
- `AutomationServiceImpl.enable()` 对 visual flow 和 flat actions 都执行 `automationProcessRuntime.deploy(automation)`，保持启用状态和 trigger runtime 一致。
- `DebugSessionServiceImpl` 在 Step/Continue 前重建 safe execution context，保证持久化后即使 context 为 null 也有 `automationPid/debugMode/recordId/triggerPayload`。
- `DebugSessionMapper` 给 select 明确挂 `DebugSessionResultMap`，使用 JSONB type handler 反序列化 `breakpoints/executionContext/actionResults/triggerPayload`。
- E2E fixture 使用无 recipients 的 in-app notification，验证 automation runtime 链路本身，避免测试数据依赖具体通知收件人。

验证:

- 真实 API 冒烟: 创建 flat-actions automation -> enable -> trigger -> logs -> debug session -> step，全部 HTTP 200，trigger `status=success`，debug step `status=completed`，action result `send_notification/success`。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/designer/automation-designer-deep.spec.ts --project=chromium` -> 17 passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/page-designer/designer-deep-operations.spec.ts --project=chromium` -> 10 passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/dashboard/dashboard-export.spec.ts --project=chromium` -> 3 passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/designer/designers.spec.ts --project=chromium --grep "F4-E11|F4-E12|F4-E15|F4-E20"` -> 5 passed。
- `./gradlew :test --tests com.auraboot.framework.automation.service.impl.AutomationServiceImplTenantIsolationTest --tests com.auraboot.framework.automation.service.impl.DebugSessionServiceImplTest --no-daemon` -> BUILD SUCCESSFUL，34 tests passed。
- `pnpm typecheck` -> passed。

### 2026-06-11 Round 7: BPMN 节点属性历史套件治理

状态: DONE。

痛点:

- `web-admin/tests/e2e/designer/bpmn-node-properties.spec.ts` 是 3946 行历史大套件，混合了属性面板、保存、hooks、gateway、callActivity 等多类场景。
- 该套件保留大量 `test.skip()`、`waitForTimeout()`、`toBeGreaterThanOrEqual()` 弱断言，并用 React internal `__reactProps$` 和直接 PUT 保存绕过真实 property panel / toolbar 行为。
- 继续保留该文件会让后续 `/e2e-truth` 永远扫到历史债，也会误导“BPMN 节点属性全覆盖”的完成判断。

方案:

- 退役并删除旧 `designer/bpmn-node-properties.spec.ts`。
- 保留并依赖已有分层 targeted BPMN specs：
  - `bpm-designer/designer-usertask-assignee-matrix.spec.ts`
  - `bpm-designer/designer-servicetask-command.spec.ts`
  - `bpm-designer/designer-callactivity.spec.ts`
  - `bpm-designer/designer-gateway-condition.spec.ts`
  - `bpm-designer/designer-sla-panel.spec.ts`
- 新增 `bpm-designer/designer-node-property-matrix.spec.ts` 作为代表性属性矩阵：
  - 使用真实 property panel DOM 控件编辑 `startEvent / userTask / serviceTask / exclusiveGateway / callActivity / sequenceFlow edge`。
  - 保存走 BPMN toolbar save dialog，不再直接 PUT。
  - 部署走真实 deploy 按钮。
  - 后端通过 DTO `designerJson` 反查 node/edge config。
  - XML 通过 `/api/bpm/process-definitions/{pid}/bpmn` 反查 `commandServiceTaskDelegate`、`calledElement`、`aura.callMappings`、`conditionExpression`。
- 只补可测性 test id：`startevent-*`、`endevent-*`、`process-picker-*`、`callactivity-*`，不改变产品行为。

验证:

- `pnpm typecheck` -> passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/bpm-designer/designer-node-property-matrix.spec.ts --project=chromium` -> 1 passed。
- `pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/bpm-designer/designer-usertask-assignee-matrix.spec.ts tests/e2e/bpm-designer/designer-servicetask-command.spec.ts tests/e2e/bpm-designer/designer-callactivity.spec.ts --project=chromium` -> 7 passed。
- `/e2e-truth` 红线自查: 新增矩阵 spec 与同批 3 个 targeted specs 未发现真实 `test.skip`、`test.fixme`、`waitForTimeout()`、retry 或 baseline threshold；仅注释中包含 “No waitForTimeout” 文本。

### 2026-06-11 Round 8: DMN 决策表编辑器深化闭环

状态: DONE。

痛点:

- 原有 `dmn-export-artifact.spec.ts` 只证明 DMN XML 能下载，不能证明输入/输出列管理、FEEL cell、hitPolicy 语义、保存反查、导入/round-trip 是同一条真实链路。
- `decisionops-full-golden.spec.ts` 覆盖了部分 FEEL gap analysis，但属于大巡检，不适合作为 DMN 编辑器专项验收。
- 浏览器专项首次引入 date FEEL 运行时后发现真实 parity gap：analysis 能识别 date range，但后端 `ConditionAstEvaluator` 的 `GT/GTE/LT/LTE/BETWEEN` 只按数字比较，date/datetime 会落到 `UNKNOWN`。
- 保存成功后 `listVersions` 在 dev/BFF 噪声下可能返回 null，workbench 直接 `.find` 导致 “Cannot read properties of null”，虽然 draft 创建和 validate 已成功。

方案:

- 新增 `decisionops/dmn-table-editor-deep.spec.ts`，继续复用 DSL-hosted `DecisionTableWorkbenchBlock` 和平台 `DecisionTableEditor`，不新增整页 React route。
- 浏览器真实操作覆盖：
  - 输入列新增、排序、`dataType=date`、表达式 path。
  - 输出列新增/删除、dataType 切换、allowedValues。
  - FEEL cell 编辑：金额区间、日期区间、catch-all。
  - `dt-analyze` 后端 analysis，断言 `DMN_CONTINUOUS_GAP` 与 `DMN_COMPLEX_INPUT_PROOF`。
  - `COLLECT + SUM` test-run，日期 FEEL 参与运行，后端输出 `route=8`。
  - `PRIORITY` test-run，allowedValues 顺序决定输出 `urgent`。
  - 保存草稿并校验后通过 `/api/decision/versions/{pid}` 反查 `contentJson`，验证列顺序、dataType、aggregation、allowedValues、FEEL、输出值。
  - `round-trip`、真实 download 导出、XML 内容校验、再导入并回显。
- 后端 `ConditionAstEvaluator` 增强 ISO `date/time/datetime/duration` 有序比较和 `BETWEEN`，保持三值逻辑与 numeric fallback。
- 前端 `conditionAst.ts` preview 同步支持 date/time/datetime/duration 有序比较，避免本地 preview 与后端 runtime 漂移。
- `DecisionTableWorkbenchBlock.refreshSavedVersion` 对非数组版本列表做降级，保存成功链路不再被后续刷新异常打断。

验证:

- `./gradlew :test --tests com.auraboot.framework.decision.ast.ConditionAstEvaluatorTest --tests com.auraboot.framework.decision.table.DecisionTableEvaluatorTest --tests com.auraboot.framework.decision.service.impl.DecisionTableDmnXmlServiceImplTest --tests com.auraboot.framework.decision.service.impl.DecisionTableAnalysisServiceImplTest --tests com.auraboot.framework.decision.DecisionTableIntegrationTest --no-daemon` -> BUILD SUCCESSFUL，37 tests passed。
- `pnpm exec vitest run app/shared/decision/ast/__tests__/conditionAst.test.ts app/shared/decision/table/__tests__/decisionTable.test.ts` -> 27 tests passed。
- `pnpm typecheck` -> passed。
- 重启真实 backend `6443` 后运行 `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/decisionops/dmn-table-editor-deep.spec.ts tests/e2e/decisionops/dmn-export-artifact.spec.ts --project=chromium` -> 2 passed。
- 本轮新增 browser spec 不包含 `test.skip`、`test.fixme`、`waitForTimeout()`、retry、宽松阈值或只用 API 代替 UI 的保存/导出验证。

### 2026-06-11 Round 9: 灰度发布长窗口指标容量闭环

状态: DONE。

痛点:

- 后端已经有 `ab_drt_rollout_metric_bucket` 预聚合表、90 天保留、2160h 窗口上限和 5m bucket 下限，但缺真实浏览器证据证明 UI window/bucket 控件会进入 `/metrics` API。
- 旧浏览器巡检只证明灰度页能打开，不能证明 UI 创建/激活灰度后，后端 `ROLLOUT` 分流、执行日志、长窗口指标、promote 审计是同一条链路。
- 真栈首次运行暴露两个真实 gap：
  - dev DB 未应用已入库的 `2026-06-11-decision-rollout-metric-buckets.sql`，`/metrics` 刷新预聚合表会 500。
  - `DrtEvaluateRequest.correlationId` 超过 `ab_drt_log.correlation_id varchar(64)` 时会写库 500，而不是入参 400。

方案:

- 不新增 React 页面；继续复用 DSL-hosted `DecisionRolloutMonitorBlock` 和平台 `DecisionRolloutMonitor`。
- 新增 `decisionops/decisionops-rollout-long-window.spec.ts`：
  - 通过侧边栏进入 `/p/decisionops_rollouts`，不靠 legacy `/decision-ops` 或直接跳页面作为核心路径。
  - UI 创建灰度：baseline v1、candidate v2、100% 流量、cohort routing keys、tenant segment、salt。
  - UI 激活灰度后，后端真实 `/api/decision/evaluate` 使用 `binding=ROLLOUT` 跑三次评估：2 次 eligible 命中 candidate，1 次 tenantSegment 不匹配回落 baseline。
  - API 反查 `/api/decision/logs/recent`，断言 candidate 日志 selectedVersion=2 且 resultKey=`matched=true,truth=TRUE`；baseline 日志 selectedVersion=1 且 resultKey=`matched=false,truth=FALSE`。
  - UI 把 metrics window 改为 `2160` 小时、bucket 改为 `5` 分钟，捕获 `/metrics?windowHours=2160&bucketMinutes=5` 响应，断言 source=`PRE_AGGREGATED_BUCKETS`、retention=90d、baseline evaluations=1、candidate evaluations=2、distribution 精确匹配。
  - UI 执行 promote 确认，API 反查 audit note。
- 后端 `DrtEvaluateRequest` 增加和 `ab_drt_log` / rollout 字段一致的 `@Size` 校验，避免外部请求把日志列长度错误打成 500。
- `DecisionRuntimeControllerIntegrationTest.httpEvaluateRejectsCorrelationIdLongerThanAuditColumn` 覆盖超长 correlationId 返回 HTTP 400。
- 修复 `DecisionRuntimeControllerIntegrationTest.httpDecisionUsageIndexRebuild_andFieldImpactExposeIndexedRefs` 的脆弱断言：`fieldRefs` 是全租户 rebuild 总数，不能精确等于当前 fixture 3 条；改为至少包含本 fixture 字段引用，并保留后续 field impact 对当前 decisionCode 的精确断言。

验证:

- `./gradlew :test --tests com.auraboot.framework.decision.DecisionRuntimeControllerIntegrationTest.httpEvaluateRejectsCorrelationIdLongerThanAuditColumn --tests com.auraboot.framework.decision.DecisionRuntimeControllerIntegrationTest.httpDecisionUsageIndexRebuild_andFieldImpactExposeIndexedRefs --tests com.auraboot.framework.decision.service.impl.DecisionRolloutServiceImplTest --tests com.auraboot.framework.decision.DecisionRuntimeIntegrationTest --no-daemon` -> BUILD SUCCESSFUL，12 tests passed。
- `./gradlew :test --tests com.auraboot.framework.decision.service.impl.DecisionRolloutServiceImplTest --tests com.auraboot.framework.decision.DecisionRuntimeIntegrationTest --tests com.auraboot.framework.decision.DecisionRuntimeControllerIntegrationTest --no-daemon` -> BUILD SUCCESSFUL，37 tests passed。
- `pnpm exec vitest run app/shared/decision/ui/__tests__/DecisionRolloutMonitor.test.tsx app/shared/decision/api/__tests__/decisionApi.test.ts` -> 30 tests passed。
- `pnpm typecheck` -> passed。
- 本地真实 backend `6443` 重启后，`curl http://127.0.0.1:6443/actuator/health` -> `{"status":"UP"}`。
- 首次浏览器运行发现本地 dev DB 缺 `ab_drt_rollout_metric_bucket`；已执行仓库 migration `platform/src/main/resources/database/migrations/2026-06-11-decision-rollout-metric-buckets.sql`，并用 `to_regclass` 验证表存在。
- `PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/decisionops/decisionops-rollout-long-window.spec.ts --project=chromium` -> 1 passed。
- 本轮新增 browser spec 不包含 `test.skip`、`test.fixme`、`waitForTimeout()`、retry、宽松阈值或用 API 代替 UI 的创建/激活/metrics/promote 操作；API 只用于 fixture、反查和后端运行时触发。

### 2026-06-11 Round 10: FEEL 内置函数 parity

状态: DONE。

痛点:

- Round 8 已覆盖 unary-test、range/list、date/time/datetime/duration 的有序比较，但 DMN cell 里的 `date(...)`、`duration(...)` 仍被前端本地诊断和后端 analysis 标为 `DMN_UNSUPPORTED_FEEL`。
- Condition AST 已有 `FunctionCallOperand` 和 `FunctionRegistry`，但默认白名单只有 `string.*`、`collection.size`，没有时间/时长 literal 函数；规则中心跨模块条件无法用同一套函数语义表达日期和时长边界。
- 如果直接接入完整 FEEL 表达式解释器，会扩大到 `if/then/else`、`and/or`、任意表达式求值和安全沙箱，不适合当前收口。

方案:

- 不新增 React 页面，不新增通用 FEEL 解释器；继续复用 DSL-hosted `DecisionTableWorkbenchBlock` 和平台 `DecisionTableEditor`。
- 增加平台级纯函数白名单：
  - `date("2026-06-10")` 与 `date(2026, 6, 10)` -> `DataType.DATE`
  - `time("09:30:00")` 与 `time(9, 30, 0)` -> `DataType.TIME`
  - `date and time("2026-06-10T09:30:00Z")` 与 `date and time(date(...), time(...))` -> `DataType.DATETIME`
  - `duration("P2D")` / `duration("PT4H")` -> `DataType.DURATION`
- DMN cell parser 只允许这些函数作为 literal 值出现在比较、区间、列表或等值 cell 中，例如 `>= date("2026-06-01")`、`[duration("P1D")..duration("P3D")]`；继续拒绝 `if/then/else`、`and/or`、非白名单函数、任意嵌套表达式。
- 前端本地 diagnostics 与后端 analysis 使用同一安全子集认知：白名单 literal 函数不再报 unsupported，非白名单函数仍给出 warning 或 parse error。

验收:

- 后端 evaluator 单测证明 `date(...)`、`duration(...)` 在 DMN cell 中真实影响匹配结果。
- 后端 AST 单测证明 `FunctionCallOperand` 可用时间/时长白名单函数，并证明非白名单函数仍报错。
- API/集成测试证明 `/api/decision/tables/analyze` 对白名单函数不再返回 `DMN_UNSUPPORTED_FEEL`，且 `/api/decision/test-run` 返回正确输出。
- 浏览器 E2E 必须真实编辑 FEEL cell，确认本地 diagnostics 不出现 unsupported，点击 analysis/test-run，并用 API 响应反查 runtime 输出。
- `/e2e-truth` grep 不允许新增 `test.skip`、`test.fixme`、`waitForTimeout()`、retry、宽松阈值或直接 `/p/` 跳页。

实现:

- 后端 `FunctionRegistry` 增加纯 literal 函数白名单：`date`、`time`、`date and time`、`duration`，函数名大小写和空格归一化。
- 后端 `SimpleConditionAdapter` 在 validate 阶段递归检查 `FunctionCallOperand`，非白名单函数返回 `AST_FUNCTION`，避免静默 UNKNOWN。
- 后端 `DecisionTableFeel` 支持白名单 literal 函数作为 DMN cell 值，并保留 `if/then/else`、`and/or`、非白名单函数的 unsupported/parse 诊断。
- 前端 `conditionAst.ts` 与 `decisionTable.ts` 同步支持时间/时长函数预览和本地 diagnostics。
- 共享 `DecisionTableEditor` 的 dataType 下拉补齐 `time`、`duration`。浏览器首轮 E2E 正是因为这里缺 `duration` 失败，说明 UI 可配置字段已被真实验证触达。
- 新增 `decisionops/dmn-feel-builtins.spec.ts`：从 `/home` 侧边栏进入 DSL 决策表页，真实新增输入列、选择 `date/duration`、填写 FEEL cell、点击 analysis/test-run、保存草稿，并反查 `contentJson`。

验证:

- `./gradlew :test --tests com.auraboot.framework.decision.ast.ConditionAstEvaluatorTest --tests com.auraboot.framework.decision.table.DecisionTableEvaluatorTest --tests com.auraboot.framework.decision.runtime.DecisionRuntimeNucleusTest --tests com.auraboot.framework.decision.DecisionRuntimeControllerIntegrationTest.httpDecisionTableFeelBuiltinsAnalyzeAndTestRun --no-daemon` -> BUILD SUCCESSFUL。
- `pnpm exec vitest run app/shared/decision/ast/__tests__/conditionAst.test.ts app/shared/decision/table/__tests__/decisionTable.test.ts app/shared/decision/ui/__tests__/DecisionTableEditor.test.tsx app/ui/smart/decision/__tests__/DecisionTableWorkbenchBlock.test.tsx` -> 44 tests passed。
- `pnpm typecheck` -> passed。
- 重启真实 backend `6443` 后，`curl --noproxy '*' http://127.0.0.1:6443/actuator/health` -> `{"status":"UP"}`。
- `NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/decisionops/dmn-feel-builtins.spec.ts --project=chromium` -> 1 passed。
- 截图证据：`web-admin/test-results/artifacts/decisionops-dmn-feel-built-a8125-lyze-run-and-persist-golden-chromium/dmn-feel-builtins.png`，页面显示保存并校验通过，Test-run 输出 `route=fast`。
- `/e2e-truth` 红线自查：本轮相关 browser spec、frontend tests、backend tests 未发现 `test.skip`、`test.fixme`、`waitForTimeout()`、retry、宽松阈值；browser spec 只从 `/home` 进入页面，`page.request` 仅用于 fixture、保存后反查和 runtime response 读取。

### 2026-06-11 Round 11: Dashboard PDF 导出 artifact 闭环

状态: DONE。

痛点:

- Dashboard Excel export 已在 Round 4 验证真实 download、文件名、ZIP header、workbook sheet 和 rows；但 PDF 仍停留在按钮可见，没有验证点击后是否真的生成 `.pdf`。
- 首次补测发现真实产品 bug：PDF 按钮点击后没有 download。临时 Playwright 复现抓到 console error：`html2canvas` 不能解析 Tailwind 现代色值 `oklch(...)`，catch 后只报错，不产出 artifact。

方案:

- 不改 Dashboard designer 内核，不新增导出内核；继续复用现有 `ExportPdfButton`。
- `ExportPdfButton` 保持优先截图导出：`html2canvas(target) -> jsPDF.addImage -> pdf.save()`。
- 对 cloned document 注入 PDF 专用 fallback CSS，并强制内联 hex 颜色，降低 `oklch/currentColor/SVG` 对 html2canvas 的影响。
- 增加受控 fallback：如果截图导出仍因 CSS/SVG 解析失败，则用 `jsPDF` 生成包含 dashboard title 和 target 可见文本的语义 PDF，仍以 `{dashboardTitle}.pdf` 下载。只有 fallback 也失败时才 toast error。
- 新增 `EXP-04` 浏览器用例：真实打开 Dashboard Designer、设置标题、添加数据表格、写入静态数据、保存、点击 PDF、捕获 download，校验文件名、`%PDF-` header、`/Type /Page` 和内容 marker（截图 PDF 的 `/Subtype /Image` 或 fallback PDF 的 dashboard title）。

验证:

- 临时 Playwright 复现证明修复前 PDF 无 download，console 为 `Attempting to parse an unsupported color function "oklch"`；修复后同路径触发 `{dashboardTitle}.pdf` download。
- `NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/dashboard/dashboard-export.spec.ts --project=chromium --grep "EXP-04"` -> 1 passed。
- `NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 PW_QUICK_WORKERS=2 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/dashboard/dashboard-export.spec.ts --project=chromium` -> 4 passed。
- `pnpm typecheck` -> passed。
- Artifact evidence:
  - `web-admin/test-results/artifacts/dashboard-dashboard-export-5eb7b-rendered-dashboard-artifact-chromium/ExportPdf_1781191305185.pdf`
  - `web-admin/test-results/artifacts/dashboard-dashboard-export-a8cd4-figured-widget-data-as-XLSX-chromium/ExportData_1781191305178.xlsx`
- `/e2e-truth` 红线自查：`dashboard-export.spec.ts` 与 `ExportPdfButton.tsx` 未发现 `test.skip`、`test.fixme`、`waitForTimeout()`、retry、宽松阈值；两个 `waitForEvent('download')` 都是 artifact 验证必要等待。

### 2026-06-11 Round 12: BPMN 历史 deep 套件退役

状态: DONE。

痛点:

- `web-admin/tests/e2e/designer/bpmn-designer-deep.spec.ts` 名称是 deep，但实际大量用 `test.skip(!ok, 'React Flow nodes not rendering')` 包装页面渲染失败。
- 该套件包含固定 `waitForTimeout(500)`、`toBeGreaterThanOrEqual(3)`、API create fixture、只验证导入/导出/部署按钮可见等弱证据。
- 保存相关用例只等 PUT 状态码，不验证真实 designerJson 字段、BPMN XML、deploy 后 runtime 契约；继续保留会让 `/e2e-truth` 每轮都扫到历史假覆盖，也会误导“BPMN deep 已完整覆盖”。

方案:

- 删除旧 `designer/bpmn-designer-deep.spec.ts`，不再把 skip-heavy / visible-only 套件计入完成证据。
- 由分层 targeted specs 承接真实覆盖：
  - `designer/designers.spec.ts`: 9 类 BPMN palette、真实连接、import/export JSON download、文件名和内容 round-trip。
  - `bpm-designer/designer-node-property-matrix.spec.ts`: startEvent、userTask、serviceTask、exclusiveGateway、callActivity、sequenceFlow edge 属性面板真实编辑，toolbar save，deploy，后端 DTO + BPMN XML 反查。
  - `bpm-designer/designer-receivetask.spec.ts`: receiveTask 节点保存、deploy、BPMN XML。
  - `bpm-designer/designer-gateway-condition.spec.ts`: gateway 条件 builder、保存、deploy、runtime 分支。
  - `bpm-designer/designer-servicetask-command.spec.ts`、`designer-servicetask-http.spec.ts`、`designer-callactivity.spec.ts`: service/callActivity 的后端 XML 与 runtime 语义。
- 对设计器完成声明继续采用“消费方矩阵”口径：覆盖代表性组件族、属性、保存、deploy、导出/导入、runtime；不把旧 shallow suite 的 pass count 当作深度覆盖。

验收:

- `designer/bpmn-designer-deep.spec.ts` 不再存在。
- BPMN 设计器证据只引用上述分层 specs 和后端反查，不引用旧 deep 套件。
- `/e2e-truth` 针对 BPMN 设计器收口集合不应再出现旧套件的 skip-heavy、fixed wait、visible-only import/export/deploy 证据。

验证:

- `pnpm typecheck` -> passed。
- `NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174 BACKEND_URL=http://127.0.0.1:6443 pnpm exec playwright test -c playwright.quick.config.ts tests/e2e/designer/designers.spec.ts tests/e2e/bpm-designer/designer-node-property-matrix.spec.ts tests/e2e/bpm-designer/designer-receivetask.spec.ts --project=chromium --grep "F4-E14|F4-E15|F4-E16|F4-E20|edits representative node|receiveTask"` -> 7 passed。
- `/e2e-truth` 红线自查: 本轮 BPMN 收口证据集未发现 `test.skip`、`test.fixme`、`waitForTimeout()`、宽松阈值、retry、`waitForEvent('download').catch` 或直接 PUT 保存绕 UI。
- 旧套件引用自查: `rg -n "bpmn-designer-deep|BPD-" web-admin/tests/e2e` -> 0 matches。

## 后续非本轮 scope

| 优先级 | 项 | 原因 |
|---|---|---|
| P2 | 全量设计器极限矩阵 | Page/BPMN/Automation/Dashboard 已有代表性组件、属性、保存、导入/导出 artifact 闭环；PDF/Excel/JSON 关键 artifact 已覆盖。所有拖拽组件类型和全部属性组合的笛卡尔积仍不应在一个规则中心收口 goal 内伪称穷举，后续按新增组件/真实缺陷用独立矩阵补测 |
