# Spec 1 · BPM 审批语义补齐

> 日期：2026-04-16
> 状态：Draft
> 系列：OSS BPM 闭环开源（共 3 个 Spec）
> - **Spec 1（本文）**：审批语义补齐（撤回/抄送/Page DSL bpm 能力）
> - Spec 2（待写）：SLA 可视化配置器
> - Spec 3（待写）：规则可视化编辑器

---

## 1. 背景与目标

AuraBoot 的 BPM 核心已在 OSS 仓库（`auraboot/platform`）：BPMN 运行时（SmartEngine）、图形设计器（xyflow）、表单设计器/运行时、Drools 规则引擎、SLA 定义/定时器/看板、加签/转办。

但要对外宣称"**审批流闭环**"，还缺几块：

| 缺口 | 影响 |
|------|------|
| 撤回（withdraw） | 发起人无法撤回进行中的流程，审批语义不完整 |
| 抄送（cc） | `CcRequest` DTO 存在但无 endpoint，协作链路断裂 |
| Page DSL 无 BPM 原生入口 | CRUD 页面触发审批需绕 Command+Automation 间接路径，"低代码"不名副实 |

**目标**：补齐以上三块，让 OSS 用户在 Page Designer 中配置一个"提交审批"按钮 + 详情页审批面板，无需写代码即可跑通完整审批流。

### 不在范围

- 示例流程包（另一 worktree 并行开发，后续合并）
- E2E 测试（记为 TODO，等示例包合并后基于业务流程补）
- SLA 可视化配置器（Spec 2）
- 规则可视化编辑器（Spec 3）

---

## 1.1 前置条件（Task 0：阻塞性）

**P0 · SmartEngine 部署链路修复**

worktree `workflow-demo-phase2` 最新 commit（`b5ac3196`）记录：BPMN XML 写入了 `ab_bpm_process_definition`，但 SmartEngine 的部署表 `se_deployment_instance` 为空、`deployment_id` 为 NULL，导致流程实例无法启动。

**现象**：
- `workflow-demo` 所有 E2E "skip gracefully"
- 所有依赖 `startProcess(processKey, ...)` 的调用会失败
- 本 Spec 新增的 `executionMode:"bpm"` 路径、`withdraw`/`cc` endpoint、`bpm-panel` 展示都**无法端到端验证**

**Task 0 交付**：
- 定位部署链路断点（`BpmProcessDefinitionService.save()` 之后谁应调用 SmartEngine `DeploymentBuilder`？）
- 补齐 XML → SmartEngine deployment → `se_deployment_instance` 写入流程
- 集成测试：发布流程定义后可成功 `startProcess(key, ...)`
- 回归验证：workflow-demo 的 3 个 E2E 不再 skip

**顺序约束**：Task 0 **必须先完成**，再进入本 Spec 的 Task 1-8。

---

## 1.2 与 workflow-demo 的交接

`workflow-demo` 目前用"绕路"方式实现审批触发（`postActions: start_process`），`wd_req_cc_users` 字段定义了但后端无实现。本 Spec 落地后，workflow-demo 需做以下适配（**不在本 Spec 范围内**，由示例包 worktree 负责）：

| workflow-demo 改动 | 依赖本 Spec 的哪个交付件 |
|---|---|
| `commands.json` 中 `submit_leave_request` 改为 action `executionMode:"bpm"` | 交付件 2、6 |
| `wd_leave_request_detail.json` 用 `bpm-panel` 替代分散的 `form-section + sub-table` 审批展示 | 交付件 5 |
| `wd_req_cc_users` 字段改为调用 `/api/bpm/tasks/{id}/cc` | 交付件 1 |
| 流程定义补 `withdrawPolicy` / `ccPolicy` | 交付件 3 |

**接口约定**：本 Spec 保证新 endpoint / block / executor 行为稳定，示例包 worktree 在 Spec 1 合并后按上表迁移。

---

## 2. 架构决策汇总

| 议题 | 决策 | 理由 |
|------|------|------|
| Page DSL 形态 | **C 组合**：action `executionMode:"bpm"` + 新 blockType `bpm-panel` | 列表按钮走 action 语义；详情页需要完整审批视图 |
| withdrawPolicy | 流程定义声明 `strict` \| `loose` \| `none`，默认 `strict` | 灵活可配，strict 保守安全 |
| ccPolicy | 流程定义声明谁能抄送（`initiator` \| `assignee` \| `all`）；收件人只读 + 可评论 | c+y 语义：按流程定义控制 + 可评论不可审批 |
| 后端代码归属 | BPM 业务进 `framework/bpm/`；触发器进 `framework/action/executor/BpmActionExecutor`；action→bpm 单向依赖 | 边界清晰，职责单一 |
| 撤回持久化 | 复用 `BpmAuditService` 写入现有审计表，operation=WITHDRAW | 审计统一 |
| 抄送持久化 | 新表 `ab_bpm_cc_record` + 推 Inbox 通知 + 写 audit 事件 | 专表承载语义（已读/留言），Inbox 管通知，audit 管流水 |
| 权限模型 | **C 混合**：action 走 action permission；bpm-panel 内置操作走"身份推导 + 可选 IAM 覆盖" | 开箱即用，需要收紧时可配 |
| 前端插件 | `plugins/core-bpm`（BPM 设计器外的所有前端） | 集中管理 |

---

## 3. 详细设计

### 3.1 A 面：action.executionMode = "bpm"

在现有 action DSL 中新增 `executionMode: "bpm"` 枚举值。引擎识别后跳过 Command/Automation 路径，直接调用 `BpmStartProcessService`。

```json
{
  "code": "submit_leave_approval",
  "label": { "zh_CN": "提交审批", "en_US": "Submit for Approval" },
  "executionMode": "bpm",
  "bpm": {
    "processKey": "leave_request",
    "businessKeyField": "id",
    "variables": {
      "days": "$.days",
      "reason": "$.reason",
      "department_id": "$.department_id"
    },
    "formBindingRef": "leave_request_form",
    "onSuccess": { "toast": "$i18n:bpm.submit.success", "refresh": true }
  },
  "placement": ["list.toolbar", "detail.toolbar"],
  "permission": "leave_request.submit"
}
```

**关键字段说明**：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `executionMode` | enum | 是 | `"bpm"` 表示走 BPM 路径 |
| `bpm.processKey` | string | 是 | BPM 流程定义 key，从流程引擎拉列表 |
| `bpm.businessKeyField` | string | 是 | 当前记录中作为流程业务主键的字段；同一记录不可重复发起 |
| `bpm.variables` | object | 否 | JSONPath 映射，从当前记录抽取流程变量 |
| `bpm.formBindingRef` | string | 否 | 引用已有表单绑定（覆盖流程定义中的默认绑定） |
| `bpm.onSuccess` | object | 否 | 触发成功后的 UI 反馈 |

**后端执行链路**：

```
ActionDispatcher
  → 识别 executionMode == "bpm"
  → BpmActionExecutor.execute(actionDef, recordData)
    → 校验 businessKey 唯一（不可重复发起）
    → 提取 variables（JSONPath 解析）
    → BpmStartProcessService.startProcess(processKey, businessKey, variables)
    → 返回 processInstanceId
```

`BpmActionExecutor` 位于 `framework/action/executor/`，依赖 `framework/bpm/` 的 `BpmStartProcessService`。单向依赖：action → bpm。

### 3.2 B 面：blockType = "bpm-panel"

新增 blockType，放在详情页，渲染完整审批视图。

```json
{
  "blockType": "bpm-panel",
  "id": "approval_panel",
  "bpm": {
    "processKey": "leave_request",
    "businessKeyField": "id",
    "sections": {
      "status": {
        "visible": true,
        "showAssignees": true,
        "showSla": true
      },
      "diagram": {
        "visible": true,
        "highlightActiveNode": true
      },
      "operations": {
        "visible": true,
        "operations": ["approve", "reject", "addSign", "transfer", "withdraw", "cc"],
        "approveForm": { "fieldsFromNode": true, "commentRequired": false },
        "rejectForm": { "commentRequired": true }
      },
      "history": {
        "visible": true,
        "showComments": true,
        "showAttachments": true
      }
    }
  },
  "visibleWhen": "record.process_instance_id != null"
}

// visibleWhen 使用 JS 表达式语法（与现有 block 可见性规则一致），
// record 变量指向当前详情页的记录对象。
```

**4 个 section**：

| Section | 说明 | 数据来源 |
|---------|------|----------|
| `status` | 当前节点名、审批人头像组、SLA 剩余时间 | `GET /api/bpm/process-instances/{id}/status` |
| `diagram` | BPMN 缩略图 + 高亮当前节点 | 复用 core-designer 只读渲染器 + 当前节点 id |
| `operations` | 审批/驳回/加签/转办/撤回/抄送 按钮组 | 根据当前用户身份 + policy 动态过滤 |
| `history` | 审批历史时间线（操作/意见/附件/抄送事件） | `GET /api/bpm/process-instances/{id}/audit-trail`（含 cc 事件） |

**可见性规则**：
- `visibleWhen` 控制整个 block 是否渲染（未发起流程的记录不显示）
- `operations` 内每个按钮根据身份 + policy 动态显示/隐藏：
  - `withdraw`：仅 initiator 可见 + 遵循 `withdrawPolicy`
  - `cc`：遵循 `ccPolicy` 声明（initiator / assignee / all）
  - `approve/reject/addSign/transfer`：仅当前任务候选人可见
  - 如流程定义声明了 `requiredPermissions`，叠加 IAM 校验

**前端组件结构**：

```
BpmPanelBlock.tsx
├── BpmStatusSection.tsx        （状态栏 + 头像组 + SLA 倒计时）
├── BpmDiagramSection.tsx       （只读 BPMN 图，复用 bpmn-designer 的 viewer mode）
├── BpmOperationsSection.tsx    （按钮组 + 弹窗表单）
│   ├── ApproveDialog.tsx       （通过表单）
│   ├── RejectDialog.tsx        （驳回表单，意见必填）
│   ├── WithdrawDialog.tsx      （撤回确认 + 原因）
│   ├── CcDialog.tsx            （人员选择 + 留言）
│   ├── AddSignDialog.tsx       （复用现有）
│   └── TransferDialog.tsx      （复用现有）
└── BpmHistorySection.tsx       （时间线组件）
```

### 3.3 撤回（Withdraw）

**API**：`POST /api/bpm/tasks/{taskId}/withdraw`

```json
{
  "reason": "填写有误，需要修改"
}
```

**策略解析（WithdrawService）**：

| withdrawPolicy | 行为 |
|----------------|------|
| `strict`（默认） | 仅发起人可撤回；且流程中**无任何节点**已被审批通过（第一个 approve 发生前） |
| `loose` | 仅发起人可撤回；流程进行中随时可撤回；已通过的审批节点标记"被撤回"保留审计 |
| `none` | 禁止撤回 |

**执行步骤**：
1. 校验当前用户 == 流程发起人
2. 读取流程定义的 `withdrawPolicy`
3. `strict` 模式：查询已完成任务，若有 approve 操作则拒绝撤回
4. 调用 `SmartEngineBpmAdapter.terminateProcess(processInstanceId, "WITHDRAWN")`
5. 写入 `BpmAuditService`：operation=WITHDRAW, reason=用户输入
6. 更新业务记录的流程状态字段

### 3.4 抄送（CC）

**API**：`POST /api/bpm/tasks/{taskId}/cc`

```json
{
  "receiverUserIds": ["user-001", "user-002"],
  "comment": "请知悉此审批进度"
}
```

**策略解析（CcService）**：

| ccPolicy | 谁能发起抄送 |
|----------|-------------|
| `initiator` | 仅流程发起人 |
| `assignee` | 仅当前任务审批人 |
| `all`（默认） | 发起人 + 审批人均可 |

**执行步骤**：
1. 校验当前用户身份符合 `ccPolicy`
2. 写入 `ab_bpm_cc_record`（语义数据）
3. 为每个 receiver 推送 Inbox 通知（`InboxService.push`）
4. 写入 `BpmAuditService`：operation=CC, details=receiverIds

**收件人能力**：
- 在 Inbox 看到通知："XX 流程抄送给您"
- 点击进入流程详情页（bpm-panel 可见）
- 可在 history section 留言（`POST /api/bpm/process-instances/{id}/comments`）
- **不可**参与审批操作（approve/reject/addSign/transfer 按钮不显示）

### 3.5 数据库变更

```sql
-- 流程定义扩展
ALTER TABLE ab_bpm_process_definition
  ADD COLUMN withdraw_policy VARCHAR(20) DEFAULT 'strict',
  ADD COLUMN cc_policy VARCHAR(20) DEFAULT 'all',
  ADD COLUMN required_permissions JSONB;

-- 抄送记录表（新建）
CREATE TABLE ab_bpm_cc_record (
  id BIGINT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  process_instance_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64),
  sender_id BIGINT NOT NULL,
  receiver_user_ids JSONB NOT NULL,
  comment TEXT,
  read_state JSONB DEFAULT '{}',
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_cc_tenant FOREIGN KEY (tenant_id) REFERENCES ab_tenant(id)
);

CREATE INDEX idx_cc_process ON ab_bpm_cc_record(process_instance_id);
CREATE INDEX idx_cc_tenant ON ab_bpm_cc_record(tenant_id);
```

> 按硬约束：直改 `schema.sql`，不考虑迁移兼容。

### 3.6 权限模型

**层次结构**：

```
┌─────────────────────────────────────────────────┐
│ 第一层：Action Permission                        │
│ action.permission = "leave_request.submit"       │
│ → 控制谁能看到/点击"提交审批"按钮                  │
├─────────────────────────────────────────────────┤
│ 第二层：身份推导（默认，零配置）                    │
│ 发起人 → 可撤回（受 withdrawPolicy 约束）          │
│ 审批人 → 可 approve/reject/addSign/transfer/cc   │
│ 被抄送人 → 可查看 + 评论                          │
├─────────────────────────────────────────────────┤
│ 第三层：IAM 覆盖（可选，流程定义声明）              │
│ requiredPermissions: {                           │
│   "withdraw": "bpm.withdraw.advanced",           │
│   "cc": "bpm.cc.restricted"                      │
│ }                                                │
│ → 在身份推导通过后，叠加 IAM 权限校验              │
└─────────────────────────────────────────────────┘
```

**开箱即用**：只配第一层（action permission），第二层身份推导自动生效。
**大型组织**：第三层按需启用，通过流程定义 `requiredPermissions` 字段开关。

### 3.7 PropertySchema 扩展

遵循 Studio 硬约束（SchemaBlockConfigPanel 自动渲染）。

**action.bpm**（dependsOn `executionMode == "bpm"`）：
- `processKey`：widget=select，数据源=`GET /api/bpm/process-definitions`
- `businessKeyField`：widget=field-selector（当前模型字段列表）
- `variables`：widget=key-value-editor（key=变量名, value=JSONPath）
- `formBindingRef`：widget=select，数据源=`GET /api/bpm/form-bindings`
- `onSuccess.toast`：widget=i18n-text
- `onSuccess.refresh`：widget=switch

**bpm-panel**：
- `processKey`：同上
- `businessKeyField`：同上
- `sections.status.visible`：widget=switch
- `sections.status.showAssignees`：widget=switch，dependsOn `sections.status.visible`
- `sections.status.showSla`：widget=switch，dependsOn `sections.status.visible`
- `sections.diagram.visible`：widget=switch
- `sections.operations.visible`：widget=switch
- `sections.operations.operations`：widget=multi-select，选项=6 种操作
- `sections.history.visible`：widget=switch

---

## 4. 交付件清单

| # | 交付件 | 说明 |
|---|--------|------|
| **0** | **SmartEngine 部署链路修复**（前置，阻塞性） | 详见 §1.1 |
| 1 | 后端：`WithdrawService` + `CcService` + endpoint | 含策略解析 + 审计 + Inbox 推送 |
| 2 | 后端：`BpmActionExecutor` | `framework/action/executor/`，处理 `executionMode:bpm` |
| 3 | 后端：流程定义字段扩展 | `withdrawPolicy` / `ccPolicy` / `requiredPermissions` |
| 4 | 后端：`ab_bpm_cc_record` 表 | schema.sql 直改 |
| 5 | 前端：`BpmPanelBlock.tsx` + 4 section | 注册到 `core-bpm` 插件 |
| 6 | 前端：ActionExecutionMode 扩展 bpm | 含 BPM 触发逻辑 |
| 7 | 前端：PropertySchema 扩展 | action.bpm + bpm-panel 配置面板 |
| 8 | 数据库：schema.sql 变更 | ALTER + CREATE TABLE |
| 9 | 文档：`docs/system-reference/` 更新 | BPM 子系统 + Page DSL 能力参考 |
| 10 | **TODO**：E2E 测试 | 等示例流程包合并后补 10-12 个 spec |

---

## 5. 不动的部分

- SmartEngine 适配层
- BPMN 图形设计器
- Command / Automation 现有路径（保留作为"复杂编排"备选，不废弃）
- 表单运行时
- SLA 运行时（Spec 2 覆盖可视化配置器）
- Drools 规则引擎（Spec 3 覆盖可视化编辑器）

---

## 6. 风险与注意事项

| 风险 | 缓解 |
|------|------|
| **Task 0（SmartEngine 部署）可能范围扩大** | 若部署链路修复牵涉 SmartEngine JAR 版本/初始化顺序等系统性问题，考虑拆独立 Spec 0；Spec 1 剩余 Task 在 Task 0 完成前进入 draft 模式（可写代码，不可验证） |
| SmartEngine `terminateProcess` 行为不确定 | 开发前写集成测试验证终止后的流程实例状态 |
| bpm-panel 只读 BPMN 图渲染性能 | 复用 core-designer viewer mode，限制缩放级别 |
| 身份推导 + IAM 叠加的优先级混乱 | 明确"先身份推导（必过），再 IAM 校验（叠加）"顺序 |
| JSONPath 变量提取在嵌套对象上的兼容性 | 使用 jayway-jsonpath 库，测试覆盖嵌套/数组场景 |
