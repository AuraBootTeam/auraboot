# Workflow Demo Plugin 设计文档

> 文档日期：2026-04-15（2026-04-15 晚：根据 OSS 平台能力调研修订）
> 插件目标：OSS 官方**工作流入门样例**，一个插件串起 Smart Engine 全栈能力（流程设计器 / 页面配置 / 任务中心 / SLA / Drools / 通知）。业务上下文选最普适的"请假申请"。
> **插件身份**：100% 配置（零 Java）。所有运行时能力通过 OSS 核心新增的 3 个通用 handler 承载。

## 1. 背景

- `sc_workflow_main` 已于 `58bc2f9` 删除：19 节点"凑类型"式 demo，业务语境空洞，测试覆盖薄。
- 企业版 `annual-plan / dual-prevention / asset-management` 含真实 BPMN，但业务耦合重，不适合 OSS 入门。
- `core-bpm` 插件仅提供导航菜单，无流程定义。
- 开源用户当前没有任何"看就能跑"的 Smart Engine 样例。

本插件补齐这一空白。

## 2. 定位与命名

| 项 | 值 |
|---|---|
| pluginId | `com.auraboot.workflow-demo` |
| namespace | `wd` |
| 位置 | `auraboot/plugins/workflow-demo/`（OSS） |
| pluginType | `config` |
| 依赖 | `com.auraboot.core-bpm`、`com.auraboot.core-meta` |
| 默认导入 | 是（与 `crm-starter` 同级别的"开箱 demo"） |
| 国际化 | zh-CN / en 双语齐全 |

## 3. 能力映射矩阵

一个插件覆盖以下全部能力：

| 能力 | 落地产物 |
|---|---|
| 流程设计器 | `processes.json` 定义 `wd_leave_approval` 流程，含 9 节点 designerJson |
| 页面配置 | `pages.json` 3 张页面（wd_leave_request 的 list/form/detail），通过 Page Designer 渲染 |
| 表单与字段 | `fields.json` / `bindings.json` 覆盖日期区间、枚举、数值、长文本、附件 5 类 |
| 任务中心 | userTask 节点产生待办，出现在 task-center，可审批/驳回/转办 |
| SLA | 主管审批节点挂 `sla_config`，24h 未处理 → 升级到上级 + 通知 |
| Drools 规则引擎 | `rules.json` 定义 2 条 DRL 规则：余额校验、病假附件校验 |
| 通知中心 | 审批通过/驳回、SLA 预警/升级 → in-app notification |
| 权限/角色 | 4 内建角色：employee / manager / hr / admin |
| 选人组件 | OSS `UserSelect`（抄送多选） + `OrgTreePicker`（表单可选）；任务中心的"转办"内置 UserSelect，自动继承 |

## 4. 数据模型

### 4.1 `wd_leave_request`（主表，10 字段）
| 字段 | 类型 | 说明 |
|---|---|---|
| wd_req_code | text | 申请编号，auto_generated |
| wd_req_applicant | ref:sys_user | 申请人 |
| wd_req_type | dict:wd_leave_type | 请假类型（年假/病假/事假/调休） |
| wd_req_start_date | date | 开始日期 |
| wd_req_end_date | date | 结束日期 |
| wd_req_days | decimal | 天数（前端按开始/结束计算回填） |
| wd_req_reason | longtext | 请假原因 |
| wd_req_attachments | attachment | 附件（病假>2 天必填） |
| wd_req_cc_users | ref:sys_user[] | 抄送人（多选，渲染用 OSS `UserSelect` 组件） |
| wd_req_status | enum | draft / submitted / approving / approved / rejected |
| wd_req_process_instance | text | BPM 流程实例 pid（关联审批链） |

### 4.2 `wd_leave_balance`（余额表，4 字段）
| 字段 | 类型 | 说明 |
|---|---|---|
| wd_bal_employee | ref:sys_user | 员工 |
| wd_bal_year | integer | 年度 |
| wd_bal_annual_remaining | decimal | 剩余年假 |
| wd_bal_sick_used | decimal | 已用病假 |

seed：为 5 个测试员工预置余额数据。

### 4.3 `wd_leave_type`（字典）
```
annual  | 年假 | limit_by_balance=true, require_attachment_gt=0
sick    | 病假 | limit_by_balance=false, require_attachment_gt=2
personal| 事假 | limit_by_balance=false, require_attachment_gt=0
comp    | 调休 | limit_by_balance=true,  require_attachment_gt=0
```

## 5. BPMN 流程定义

**流程 key**：`wd_leave_approval`

**设计原则**：
- **合法性校验**放在 Command 层（提交前），非法申请根本不进流程 —— 避免产生注定 terminate 的实例
- **路由分派**放在流程内 serviceTask —— 运行时决策，方便审计和可视化
- 两处**都用 Drools**，demo 一次覆盖"前置校验"和"流程内规则调用"两种集成模式
- **审批结果只改请假单状态**，不触动 `wd_leave_balance`。余额结转（计提/调账/跨年度）是真实 HR 系统的 concern，超出 demo 范围。balance 表作为"HR 角色管理的受控数据"存在，不参与审批逻辑

```
startEvent
  → userTask(提交)
  → serviceTask(Drools：计算审批人角色 approverRole)
  → exclusiveGateway(gw_approver：approverRole?)
       ├─ manager → userTask(主管审批)  ← SLA
       └─ hr      → userTask(HR 审批)
       → exclusiveGateway(gw_result：任务结果?)
            ├─ approved → serviceTask(发"通过"通知) → endEvent(approved)
            └─ rejected → serviceTask(发"驳回"通知) → endEvent(rejected)
```

**节点清单（11 节点，5 类全覆盖）**

| id | type | commandCode / config | 说明 |
|---|---|---|---|
| start_1 | startEvent | — | 流程起点 |
| task_submit | userTask | formPageKey=wd_leave_request_form | 员工填写 |
| svc_rule_route | serviceTask | `bpm:run-rule`，ruleCode=`wd_leave_routing` | 写 `approverRole` 到流程变量 |
| gw_approver | exclusiveGateway | `approverRole == 'manager' / 'hr'` | 分流 |
| task_manager_approve | userTask | role=`wd_manager` + SLA | 主管审批 |
| task_hr_approve | userTask | role=`wd_hr` + SLA | HR 审批 |
| gw_result | exclusiveGateway | `taskResult == 'approved' / 'rejected'` | 分流 |
| svc_notify_approved | serviceTask | `bpm:publish-notification`，eventCode=`wd_request_approved` | 通知申请人 |
| svc_notify_rejected | serviceTask | `bpm:publish-notification`，eventCode=`wd_request_rejected` | 通知申请人 |
| end_approved | endEvent | — | 通过终态 |
| end_rejected | endEvent | — | 驳回终态 |

5 类统计：startEvent×1 + userTask×3 + serviceTask×3 + exclusiveGateway×2 + endEvent×2 = 11。

边：9 条，全部带 `conditionExpression`（遵循 `project_smartengine_default_flow` 约束，无 default flow）。

**DRL 规则文件**

### 提交前校验 `rules/wd_leave_validation.drl`（由 Command 调用）
```drl
rule "reject_insufficient_annual_balance"
    when
        $req: LeaveRequest(type == "annual", days > balanceRemaining)
    then
        $req.setValid(false);
        $req.setReason("annual_leave_insufficient");
end

rule "reject_sick_without_attachment"
    when
        $req: LeaveRequest(type == "sick", days > 2, attachments.size == 0)
    then
        $req.setValid(false);
        $req.setReason("sick_attachment_required");
end
```

Command `wd:submit_leave_request` 在启动流程前跑这组规则，`valid=false` 直接抛 `BusinessException`（前端弹错误 toast + 保持草稿态），不创建流程实例。

### 流程内路由 `rules/wd_leave_routing.drl`（由 svc_rule_route 调用）
```drl
rule "route_short_to_manager"
    when
        $req: LeaveRequest(days < 3)
    then
        $req.setApproverRole("manager");
end

rule "route_long_to_hr"
    when
        $req: LeaveRequest(days >= 3)
    then
        $req.setApproverRole("hr");
end
```

serviceTask 把 `approverRole` 写入流程变量，供 `gw_approver` 读取。未来策略调整（如改成 "<5 天主管"）只改 DRL，BPMN 不动。

## 6. SLA 配置

**demo 用极短超时让 E2E 能实测**，生产部署时由 admin 在 UI 里调大即可。

`sla.json`：
```json
[
  {
    "slaKey": "wd_manager_approve_sla",
    "processKey": "wd_leave_approval",
    "nodeId": "task_manager_approve",
    "timeoutSeconds": 30,
    "warningBeforeSeconds": 10,
    "suspendPolicy": "PAUSE",
    "escalationTargetType": "role_parent",
    "escalationTargetValue": "wd_manager",
    "comment": "Demo-only tight timeout; production should set timeoutHours: 24"
  },
  {
    "slaKey": "wd_hr_approve_sla",
    "processKey": "wd_leave_approval",
    "nodeId": "task_hr_approve",
    "timeoutSeconds": 30,
    "warningBeforeSeconds": 10,
    "suspendPolicy": "PAUSE",
    "escalationTargetType": "role_parent",
    "escalationTargetValue": "wd_hr",
    "comment": "Demo-only"
  }
]
```

- 待办 20s 后发 `sla_warning` 通知
- 30s 未审批 → 自动改派到上级 + 发 `sla_escalated` 通知
- E2E 里用 `page.waitForTimeout` 是禁止的；用 `expect(...).toBeVisible({ timeout: 40_000 })` 等通知出现即可，不破坏"禁止显式 sleep"规范
- 无需测试时间快进 API（上一版草案中待决点 #2 关闭）

## 7. 页面配置（Page Designer 产物）

| pageKey | kind | 说明 |
|---|---|---|
| `wd_leave_request_list` | list | 表格列：编号/申请人/类型/起止/天数/状态；筛选：status/type/applicant；toolbar：新建 |
| `wd_leave_request_form` | form | 分组：基本信息 / 请假详情 / 附件；wd_req_days 由 start/end 回填；提交触发 `wd:submit_leave_request` Command |
| `wd_leave_request_detail` | detail | tabs：基本信息 / 审批历史（子表：bpm_task 关联 processInstance） |

## 8. Commands

| code | 说明 |
|---|---|
| `wd:create_leave_request` | 创建草稿 |
| `wd:submit_leave_request` | 提交 → 启动 `wd_leave_approval` 流程实例 → 写回 wd_req_process_instance |
| `wd:cancel_leave_request` | 申请人撤回（仅 draft/submitted 状态） |
| `wd:approve_task` / `wd:reject_task` | 审批动作（复用 core-bpm 标准 Command） |

## 9. 角色与权限

| 角色 code | 名称 | 权限 |
|---|---|---|
| `wd_employee` | 员工 | CRUD 自己的 leave_request；看自己的 balance |
| `wd_manager` | 主管 | 看下属申请 + 审批 manager 节点 |
| `wd_hr` | HR | 看全租户申请 + 审批 hr 节点 + 维护 balance |
| `wd_admin` | 流程管理员 | 全权限 + 编辑 rules / sla_config |

seed 用户：给 `admin@example.com` 叠加 `wd_admin` 角色，另外 bootstrap 时创建 5 个员工（alice/bob/carol 员工，diana 主管，eve HR）并预置 `wd_leave_balance`。

## 10. 通知事件

复用现有 `framework/notification` + `BpmNotificationListener`：

| 触发点 | 事件 | 接收人 |
|---|---|---|
| userTask 创建 | `task_assigned` | 审批人 |
| SLA 20s | `sla_warning` | 审批人 |
| SLA 30s | `sla_escalated` | 审批人 + 上级 |
| svc_notify_approved | `wd_request_approved` | 申请人 + 抄送人 |
| svc_notify_rejected | `wd_request_rejected` | 申请人 |

## 11. E2E 测试（4 spec，金标准级）

全部放在 `auraboot/web-admin/tests/e2e/workflow-demo/`。

| spec | 场景 |
|---|---|
| `wd-leave-short-manager.spec.ts` | alice 提交 2 天病假 → Drools 路由到主管 diana → 通过 → 状态=approved + balance 不变 + in-app 通知出现 |
| `wd-leave-long-hr.spec.ts` | bob 提交 5 天年假 → Drools 路由到 HR eve → 驳回 → 状态=rejected + balance 不扣 |
| `wd-leave-rule-block.spec.ts` | carol 余额 0 提交 3 天年假 → 提交时 Drools `valid=false` → 前端弹错 "annual_leave_insufficient" + 状态保持 draft + 未创建流程实例 |
| `wd-leave-sla-escalation.spec.ts` | alice 提交 → 30s 内不审批 → 等 `sla_escalated` 通知出现（单测超时设 45s）+ 审批人自动改派到上级 |

每 spec 按 14 维度断言，含：
- 从侧边栏菜单导航（禁止 page.goto 直达）
- 断言列表行数、详情字段具体值、任务中心出现待办、流程图高亮当前节点
- UI 动作次数 > API 调用次数

## 12. 插件目录结构

```
plugins/workflow-demo/
├── plugin.json
├── config/
│   ├── default-bootstrap.json
│   ├── models.json
│   ├── fields.json
│   ├── bindings.json
│   ├── dicts.json
│   ├── commands.json
│   ├── permissions.json
│   ├── roles.json
│   ├── menus.json
│   ├── pages.json
│   ├── processes.json
│   ├── rules.json
│   ├── sla.json
│   ├── namedQueries.json
│   └── i18n.json
└── seed/
    ├── wd_leave_balance.json       (5 员工余额)
    └── wd_users_roles.json         (alice/bob/carol/diana/eve 角色绑定)
```

## 13. 风险与待决

| 项 | 说明 | 建议处置 |
|---|---|---|
| SLA E2E 时长 | demo SLA 直接设 30s，E2E 等 45s 收尾，无需时间快进 API | 已决 |
| Drools 拦截的流程出口 | svc_rule_check 后跟 `gw_valid`，读 `ruleResult.valid` 分流；符合 BPMN 规范 | 已决 |
| 路由策略变更需求 | 未来"<5 天才给主管"这类调整不动 BPMN，改 DRL 即可 | Drools 路由是主动设计 |
| namespace 冲突 | `wd` 可能和未来插件撞 | 已检查：无冲突 |
| Page Designer 产物 | pages.json 手写还是用设计器导出？ | 用设计器在 demo 环境画好 → 导出 JSON 放入插件 → 二次导入时作为初始化 DSL |
| 依赖 core-bpm | core-bpm 已在 OSS | 无阻塞 |

## 14. 实施阶段（仅列阶段，不写时间）

仅分阶段，不定 milestone：

1. **Phase 1 · 骨架**：models / fields / bindings / dicts / commands / permissions / roles / menus / i18n / pages（3 页）
2. **Phase 2 · 流程**：processes.json（含 designerJson）+ rules.json（Drools DRL）+ sla.json + namedQueries（审批历史查询）
3. **Phase 3 · 平台通用能力 + 插件接线**：OSS 核心新增 3 个通用 BPM handler（`bpm:run-rule` / `bpm:start-process` / `bpm:publish-notification`），loader 增加 `rules` / `sla` 分支，`BpmRuleService.importRule()` / `SlaConfigService.importSlaConfig()` 两个 upsert 方法；workflow-demo commands.json 改用通用 handler 串联，去掉扣余额相关节点；seed + bootstrap + oss-reset-and-init.sh 接入
4. **Phase 4 · E2E**：4 个 spec 写完，全部按金标准 14 维度断言通过

每 Phase 完成即可 commit + push，不要求一次性完成。

## 15. Phase 2 implementation notes (2026-04-15)

Phase 2 implementation surfaced several divergences between the original design
intent and the OSS platform's actual capabilities. Deliverables were adjusted
as follows.

### 15.1 DRL facts are `Map<String, Object>`, not typed POJOs

`DroolsEngineService` (OSS `platform/src/main/java/.../bpm/rule/DroolsEngineService.java`)
passes a single `Map<String, Object>` as the fact and reserves `_ruleResult`
(also a `Map`) as the only output channel. No typed `LeaveRequestFact` class
exists; authoring one would require shipping a JAR.

DRL files (`rules/wd_leave_validation.drl` and `rules/wd_leave_routing.drl`)
were therefore rewritten to match facts against `Map(this["key"] == ...)` and
to write results via `((Map)$req.get("_ruleResult")).put(...)`. Field access
uses `Number` casts because the importer will deliver JSON numeric literals as
either `Integer` or `BigDecimal`.

### 15.2 `rules` / `sla` / `drlFiles` resourceDirs are not yet wired

`PluginDirectoryLoader.loadResourcesFromDirs` only handles
`models / fields / modelFieldBindings / dicts / commands / bindingRules / menus / permissions / roles / pages / processes / i18n / namedQueries / savedViews / dashboards`.
There is no branch for `rules` or `sla`.

Consequences:

- `config/rules.json` and `config/sla.json` are **authored and committed**
  exactly against the `BpmRule` and `SlaConfigEntity` schemas, and are
  registered in `plugin.json` under `resourceDirs.rules` / `resourceDirs.sla`.
  They will be silently ignored by the current importer.
- A follow-up backend task (tracked as a Phase 3 gap below) must extend the
  loader to parse these two keys into their respective DTOs and hand them to
  `PluginResourceImporterImpl`.
- `rules/*.drl` is a parallel source of truth next to `ruleContent` embedded
  in `rules.json`; the DRL files are committed so reviewers can read rules
  without unescaping newlines. `ruleContentFile` is a forward-compatible hint
  for the loader.

### 15.3 `extension.*` 钩子方案作废，改为 3 个通用 BPM handler

**2026-04-15 晚更新**：Phase 2 里写的 `extension.preflightRule` / `extension.startProcess`
/ `extension.deductionRules` 这几个钩子，平台 pipeline 根本不会读取 —— 属于虚构字段。
经平台能力调研（`2026-04-15` 晚），Phase 3 改为在 OSS 核心加 3 个通用
`CommandHandlerExtension`，全部 plugin 声明式复用：

| commandCode | 职责 | 基于 |
|---|---|---|
| `bpm:run-rule` | `args.ruleCode` + `args.facts` → 调 `DroolsEngineService.evaluate()` → 返回规则输出 | 薄包装 |
| `bpm:start-process` | `args.processKey` + `args.businessKey` + `args.variables` → 调 `BpmIntegrationService.startBusinessProcess()` | 参照 `BuiltinStartApprovalHandler` |
| `bpm:publish-notification` | `args.eventCode` + `args.recipientFrom` + `args.templateParams` → 调 `NotificationService.send()` | 薄包装 |

调用约定：Command pipeline 的 `HandlerPhase` 已经从 `ExtensionRegistry` 按
`commandCode` 拿到 handler，插件 `commands.json` 只需声明 `type: handler` +
`handlerCode: bpm:run-rule` + 业务参数即可。

**业务选择**：审批通过/驳回**只改状态 + 发通知**，不触动 `wd_leave_balance`。
余额的结转/计提/调账是真实 HR 系统的 concern，超出 demo 范围。因此放弃
`wd:execute_deduct_balance` 命令及对应的 svc_deduct 节点。

**工作流节点调整**：
- 原 `svc_deduct`（approved 分支后）拆成 `svc_notify_approved` / `svc_notify_rejected` 两个 serviceTask，各自调 `bpm:publish-notification`，节点总数 10 → 11
- 没有 "extension" 字段虚构，`wd:submit_leave_request` 改为 Command pipeline 调用 `bpm:run-rule`（校验）+ `bpm:start-process`（启流程）两步

### 15.3a Phase 2 `commands.json` / `processes.json` 需要按新约定重写

Phase 2 提交的 `commands.json` 中 `extension.*` 字段、`wd:execute_deduct_balance`
命令、以及 `processes.json` 的 `svc_deduct` 节点将在 Phase 3 统一改造。

### 15.4 Edges all carry conditionExpression — including unconditional ones

Per memory `project_smartengine_default_flow`, SmartEngine ignores the BPMN
`default=` attribute at runtime. All 10 edges in `wd_leave_approval` carry a
`conditionExpression`; unconditional edges use `${true}` as a non-magic
literal that the existing gateway evaluator short-circuits.

### 15.5 approval history query targets `ab_approval_task`

OSS has no `ab_bpm_task` table; `ab_approval_task` is the canonical persistent
record of BPM approval tasks (columns: `task_title`, `actual_approver_id`,
`status`, `approval_data`, `approval_comment`, `created_at`, `completed_at`,
`chain_execution_id`). The `wd_leave_request_approval_history` named query
joins on `chain_execution_id = :processInstanceId` and aliases columns to
camelCase (quoted identifiers) so the detail-page sub-table's
`taskName / assignee / result / comment / completedAt` bindings resolve
without a UI-side transform.

### 15.6 Phase 3 工作清单（最终版）

**OSS 核心（`auraboot/platform`）**

1. 新增 3 个 handler：
   - `framework/bpm/handler/BpmRunRuleHandler.java`（~60 行）
   - `framework/bpm/handler/BpmStartProcessHandler.java`（参照 `BuiltinStartApprovalHandler`，~80 行）
   - `framework/bpm/handler/BpmPublishNotificationHandler.java`（~50 行）
   - 每个 handler 注册到 `ExtensionRegistry`，按 commandCode 匹配
2. `framework/plugin/service/impl/PluginDirectoryLoader.java` 增加 `rules` 和 `sla` 两个分支（~30 行）
3. `framework/plugin/service/impl/PluginImportServiceImpl.java` 增加 `importRules()` / `importSlaConfigs()` 调用
4. `framework/bpm/rule/DroolsRuleService.java` 增加 `importRule(RuleDefinitionDTO)` upsert（by ruleCode）
5. `framework/bpm/service/SlaConfigService.java` 增加 `importSlaConfig(SlaConfigDTO)` upsert（by slaCode）
6. DRL 文件加载：当 `rules.json` 项带 `ruleContentFile: "rules/xxx.drl"`，loader 读文件内容填充 `ruleContent` 后再入库
7. 单元测试 / 集成测试配套

**workflow-demo 插件改造（`auraboot/plugins/workflow-demo`）**

1. `commands.json` 重写：
   - 删 `wd:execute_deduct_balance`
   - `wd:submit_leave_request` 拆成 pipeline 形态，step1 = `bpm:run-rule`（wd_leave_validation）、step2 = `bpm:start-process`（wd_leave_approval）、step3 = 状态迁移到 submitted
2. `processes.json` 重写：
   - 删 svc_deduct 节点及相关边
   - 加 svc_notify_approved / svc_notify_rejected 两个 serviceTask，commandCode = `bpm:publish-notification`
3. `rules.json` 清理：去掉任何扣减相关的规则（只保留 validation + routing 两组）
4. `sla.json` 保持 30s 不变
5. i18n 补充 `wd_request_approved` / `wd_request_rejected` 通知模板
6. `default-bootstrap.json` 加 seed 用户绑定

**集成**

- `scripts/oss-reset-and-init.sh` 把 `workflow-demo` 加入默认导入序列
- Seed（Phase 3 末或 Phase 4 初）：5 个 demo 员工 + 预置 `wd_leave_balance`

**Phase 4 保留不变**：4 个 E2E spec。
