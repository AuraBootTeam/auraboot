# Workflow Demo Plugin 设计文档

> 文档日期：2026-04-15
> 插件目标：OSS 官方**工作流入门样例**，一个插件串起 Smart Engine 全栈能力（流程设计器 / 页面配置 / 任务中心 / SLA / Drools / 通知）。业务上下文选最普适的"请假申请"。

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

```
startEvent
  → userTask(提交)
  → serviceTask(Drools：计算审批人角色 approverRole)
  → exclusiveGateway(gw_approver：approverRole?)
       ├─ manager → userTask(主管审批)  ← SLA
       └─ hr      → userTask(HR 审批)
       → exclusiveGateway(gw_result：任务结果?)
            ├─ approved → serviceTask(扣减余额+通知) → endEvent(approved)
            └─ rejected → endEvent(rejected)
```

**节点清单（10 节点，5 类全覆盖）**

| id | type | 说明 |
|---|---|---|
| start_1 | startEvent | 流程起点 |
| task_submit | userTask | 员工填写表单（formPageKey=wd_leave_request_form） |
| svc_rule_route | serviceTask | 调 Drools：输出 `{approverRole}` 写入流程变量 |
| gw_approver | exclusiveGateway | `approverRole == 'manager' / 'hr'` 分流 |
| task_manager_approve | userTask | 主管审批（assigneeType=role/wd_manager）**挂 SLA** |
| task_hr_approve | userTask | HR 审批（assigneeType=role/wd_hr）**挂 SLA** |
| gw_result | exclusiveGateway | `taskResult == 'approved' / 'rejected'` 分流 |
| svc_deduct | serviceTask | 扣减余额 + 发通知 |
| end_approved | endEvent | 审批通过终态 |
| end_rejected | endEvent | 驳回终态 |

5 类节点统计：startEvent×1 + userTask×3 + serviceTask×2 + exclusiveGateway×2 + endEvent×2 = 10。

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

seed 用户：给 `admin@auraboot.com` 叠加 `wd_admin` 角色，另外 bootstrap 时创建 5 个员工（alice/bob/carol 员工，diana 主管，eve HR）并预置 `wd_leave_balance`。

## 10. 通知事件

复用现有 `framework/notification` + `BpmNotificationListener`：

| 触发点 | 事件 | 接收人 |
|---|---|---|
| userTask 创建 | `task_assigned` | 审批人 |
| SLA 20h | `sla_warning` | 审批人 |
| SLA 24h | `sla_escalated` | 审批人 + 上级 |
| end_approved | `request_approved` | 申请人 |
| end_rejected | `request_rejected` | 申请人 |
| end_terminated | `request_terminated` | 申请人 |

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
3. **Phase 3 · Seed + 集成**：default-bootstrap 装订角色和 demo 用户；seed 脚本灌余额数据；oss-reset-and-init.sh 加入导入序列
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

### 15.3 Commands are declarative, not a free-form pipeline

Existing OSS/enterprise commands use declarative fields
(`type`, `inputFields`, `autoSetFields`, `validation`, `preconditions`,
`postActions`, `cascadeDelete`, `extension`). There is no stock pipeline step
for "run Drools rule" nor "start BPM process"; those are attached to the
process via `extension.triggerCommand` and executed by `BpmProcessStarter`
(not shown in OSS).

`wd:submit_leave_request` therefore keeps `type: state_transition` and
declares its phase-2 behaviour inside `extension`:

- `extension.preflightRule` — `ruleCode`, `factBuilder` (EL-style
  placeholders), `balanceLookup`, and `onInvalid.throwException` hint
- `extension.startProcess` — `processKey`, `variables`,
  `storeInstanceIdIn: "wd_req_process_instance"`

The runtime wiring (a Phase 3 backend task) must:

1. Read `extension.preflightRule` before the state transition, resolve the
   balance record, evaluate the DRL, and throw `BusinessException(messageKey)`
   on `valid=false`.
2. After the transition, start the process defined in `extension.startProcess`
   and persist the returned `processInstanceId` into the named field.

`wd:execute_deduct_balance` uses the same shape under
`extension.deductionRules` and `extension.notification`; the BPM engine's
serviceTask runner (Phase 3) must honor them when it sees
`commandCode: wd:execute_deduct_balance` on the `svc_deduct` node.

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

### 15.6 Phase 3 gaps (tracked, not delivered in this commit)

- Backend: extend `PluginDirectoryLoader` with `rules` and `sla` branches;
  add importer methods for `BpmRule` and `SlaConfigEntity`.
- Backend: implement the `extension.preflightRule` / `extension.startProcess`
  hooks on `CommandPipeline` (or whatever runs `state_transition`).
- Backend: implement `extension.deductionRules` + `extension.notification`
  on the `serviceTask` runner.
- Seed: 5 demo employees + per-year `wd_leave_balance` rows
  (`default-bootstrap.json` extension planned for Phase 3).
- E2E: 4 specs per §11 remain Phase 4.
- reset-and-init.sh OSS variant: add `workflow-demo` to the import list.

## 15.7 Phase 3b 架构简化

Phase 3 早期的实现把每个 `serviceTask` 都桥接回 Command Pipeline（`bpm:run-rule` / `bpm:publish-notification` / `bpm:start-process` 三个 CommandHandler），一次规则/一次通知就要穿过 16 个 phase，回调链长、异常栈难读、且 `start-process` 又会在 postAction 里再次触发 pipeline，容易形成隐式死循环。

Phase 3b 把 serviceTask 从 Command Pipeline 上剥离：

- **两个瘦 delegate**：`DroolsServiceTaskDelegate` / `NotificationServiceTaskDelegate` 直接实现 SmartEngine 的 `JavaDelegation`，只消费 BPMN 上的 `smart:*` 扩展属性（`ruleCode` / `factsVars` / `eventCode` / `recipientFrom` / `templateParamsVars`），不走 Command Pipeline。`JsonToBpmnConverter` 识别新节点类型 `rule-task` / `notification-task` 并输出 `smart:class="<bean>"` + `smart:<attr>` 属性。
- **PreActionsPhase（@Order 750）**：在 `AssertPhase` 和 `PreInvariantPhase` 之间新增一段 `preActions` 执行阶段，首发支持 `bpm:run-rule`，通过 `contextLookup` 从其它模型加载上下文（例：查询申请人的 `wd_leave_balance`），拼 facts 后调 `DroolsEngineService`。规则返回 `valid=false` 直接抛 `BusinessException` 终止提交，不写任何 DB。
- **postAction `start_process`**：`PostExecutionPhase` 新增分支，直接调 `BpmIntegrationService.startBusinessProcess` 并把返回的 `processInstanceId` 写回记录的 `storeInstanceIdIn` 字段；无 Handler 间接层。
- **插件导入即部署**：`PluginResourceImporterImpl.importProcess` 现在在 `autoDeploy=true` 时把 `designerJson` 转 BPMN XML 并 `deployWithUTF8Content` 到 SmartEngine，按 `processKey` 做幂等跳过；失败抛 `PluginException` 让导入事务回滚。
- **删除**：`BpmStartProcessHandler` 及其测试（上述 postAction 分支取代），保留 `BpmRunRuleHandler` / `BpmPublishNotificationHandler` 以支持仍需走 Command Pipeline 的命令式调用入口。
