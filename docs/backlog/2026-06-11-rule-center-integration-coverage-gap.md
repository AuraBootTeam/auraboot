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

这份文档只覆盖当前“补齐真实测试和必要平台能力”的收口范围。DMN 编辑器深化、完整灰度发布演进、全量设计器矩阵属于后续独立 scope，不能用这份补测文档宣称全部完成。

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
| DMN | 导出 DMN XML | `decisionops/dmn-export-artifact.spec.ts` 已覆盖浏览器 download | `/api/decision/tables/export-dmn` | `.dmn.xml` 文件名和内容 | DONE |

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

## 后续非本轮 scope

| 优先级 | 项 | 原因 |
|---|---|---|
| P1 | 全设计器组件类型/属性/导入导出矩阵 | 需要按 Page/BPMN/Automation/Dashboard designer 分独立矩阵 |
| P2 | DMN 编辑器完整深化 | NEW scope，需独立 goal |
| P2 | 灰度发布长窗口指标容量设计 | NEW scope，需独立 goal |
