# OSS BPM 模块目标架构设计

**Date**: 2026-04-17
**Status**: 草案 · 待 Controller 评审
**Scope**: BPM 模块整体重新对齐——抽象层、表结构、SmartEngine 集成方式、Spec 拆分

---

## 0. 背景

Spec 1（2026-04-16-oss-bpm-closure-spec1）实施过程中，subagent 反复在 `BpmEngine` 抽象层 vs `ProcessEngineService` 之间走错路（Task 3 commit `666dcd3b` 已修复一次，Task 6 commit `2a07e5b8` 又重蹈）。memory rule（`project_bpm_adapter_stub`）和文档警告都未能拦住。根因是 **结构性问题**：

- `BpmEngine` 接口长得像生产代码，但 `SmartEngineBpmAdapter` 是 in-memory stub
- 双轨 API 并存（`BpmEngine.startProcess` vs `ProcessEngineService.startProcess`），代码上无任何标记区分
- `TestBpmFixture` 自身混用两条路径
- 生产代码 0 个 caller 使用 `BpmEngine`，但其存在是诱惑

本设计回答：**BPM 模块的最终目标形态是什么？什么留、什么删、什么补**。

---

## 1. 能力清单（BPM 对平台提供什么）

| 类别 | 能力 | 谁用 |
|---|---|---|
| 定义管理 | BPMN XML 部署/卸载/版本化；元数据持久化 | 插件导入、流程设计器 |
| 实例生命周期 | start / suspend / resume / terminate / query | Action / Form / Command / API |
| 任务操作 | approve / reject / transfer / claim | 审批 UI |
| 审批语义扩展 | withdraw（policy strict/loose/none）、cc（policy initiator/assignee/all） | 审批 UI（Spec 1） |
| 触发集成 | 5 种触发路径（Action / Form / Command pipeline / Service Task / API） | 业务模块、designer |
| 可视化查询 | 节点状态、历史、变量、BPMN canvas 高亮数据 | bpm-panel block |
| 审计 | 所有 AuraBoot 业务语义操作写 `ab_bpm_audit_record`；SE 自动记的不重复 | 自身 + 审计页 |
| 监督 / 督办 | 创建监督、查询监督、自动关闭（**Spec 4 新增，复用 SmartEngine SupervisionService**） | 审批升级场景 |

**Spec 2 范围**（SLA 可视化）和 **Spec 3 范围**（规则可视化）在路线图中保留位置，本文档不展开。

---

## 2. 分层架构

```
┌─────────────────────────────────────────────────────────┐
│ HTTP Controller 层                                       │
│  ProcessDefinitionController / ProcessInstanceController │
│  TaskController（含 /withdraw、/cc）                     │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│ Service 层（BPM 业务边界，对外唯一入口）                   │
│                                                          │
│  ProcessDeploymentService  ← 部署 / undeploy / 元数据   │
│  ProcessEngineService      ← start / suspend / 查询      │
│  TaskService               ← approve / reject / transfer │
│  WithdrawService           ← 撤回 + policy（Spec 1）     │
│  CcService                 ← 抄送 + policy（Spec 1）     │
│  BpmAuditService           ← 业务语义审计（仅 SE 不记的）│
│  BpmFormService            ← Form ↔ Process 桥接         │
│  BpmnExtensionParser       ← 解析 BPMN aura: 命名空间    │
│                                                          │
│  ─ 触发器（薄壳：拼参数 + 调 service）─────────────────  │
│  BpmActionExecutor         ← Action.executionMode=bpm    │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│ SmartEngine 集成边界                                     │
│  - SmartEngine API 直接调用：仅在 service 层内部           │
│  - 加值：tenant 注入、initiator 注入、审计联动             │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│ SmartEngine（Alibaba 流程引擎）                          │
│  ProcessCommandService / TaskCommandService /            │
│  NotificationCommandService（CC + Inform） /             │
│  SupervisionCommandService（督办）/                      │
│  ProcessQueryService / TaskQueryService / ...            │
└─────────────────────────────────────────────────────────┘

数据库表归属：
  ab_bpm_*    AuraBoot BPM 模块业务元数据
  ab_sla_*    AuraBoot SLA 模块（Spec 2）
  se_*        SmartEngine 引擎私有
```

---

## 3. 核心决策（决策日志）

### D1. 删除 `BpmEngine` 抽象层

**决策**：物理删除 `framework/bpm/engine/` 整个包。
**理由**：
- 生产路径 0 个 caller
- 接口太薄（10 方法），捕捉不了真实生产需求（tenant/audit/form binding/controlMode）
- "未来可换引擎" 是假设——`ProcessEngineService` 已与 SmartEngine 深度耦合，真要换引擎本就要重写整层
- memory 软规则证明拦不住，必须代码物理消除

**删除清单**：
- `framework/bpm/engine/BpmEngine.java`
- `framework/bpm/engine/BpmEngineFactory.java`
- `framework/bpm/engine/adapter/SmartEngineBpmAdapter.java`
- `framework/bpm/engine/config/BpmAutoConfiguration.java`
- `framework/bpm/engine/config/BpmProperties.java`
- `framework/bpm/engine/dto/{ProcessInstanceInfo,TaskInfo,HistoryRecord}.java`
- `framework/bpm/engine/exception/BpmEngineException.java`
- `framework/bpm/engine/BpmEngineAbstractionTest.java`

### D2. SmartEngine 集成方式：厚 service + 不抽象

**决策**：BPM service 层直接调 SmartEngine API，**不再加任何 facade / adapter / interface**。
**理由**：
- 真要换引擎，service 层语义和方法签名都要重写——抽象层无救
- 假抽象比真耦合危险（已被 Task 6 证明）

**类型边界**：
- Service 层内部：随便用 `ProcessInstance` / `TaskInstance` 等 SmartEngine 类型
- Controller 层：可以直接返回 SmartEngine 类型
- **业务模块（plugins / agent / chat / bpm-panel 后端等）**：**只通过 service 方法调用，禁止直接 import `com.auraboot.smart.framework.engine.*`**

### D3. CC 改用 SmartEngine NotificationService

**决策**：删除 `ab_bpm_cc_record` 表 + entity + mapper + typehandler。`CcService` 重写为薄壳，调用 `smartEngine.getNotificationCommandService()` + `smartEngine.createNotificationQuery()`。

**理由**：SmartEngine 已经提供完整 CC 能力：
- `NotificationCommandService.sendNotification(processInstanceId, taskId, sender, List<receivers>, title, content, tenantId)` — 1 sender → N receivers 自动展开
- `createNotificationQuery().receiverUserId(uid).readStatus("unread").orderByCreateTime().desc().listPage(0, 10)` — fluent API 完美匹配 inbox 查询
- `markAsRead(id)` / `batchMarkAsRead(List<id>)` — 已读管理
- `se_notification_instance` 索引 `(receiver_user_id, read_status, tenant_id)` — 查询性能合理
- `NotificationConstant.NotificationType.CC = "cc"` — 一等公民

`CcService` 仍负责：
- `CcPolicy` 策略门（initiator/assignee/all 授权检查）
- AuraBoot 业务审计（写一条 `BpmAuditOperation.CC` 到 `ab_bpm_audit_record`，记录"我执行了 cc 这个操作"）

### D4. `ab_bpm_process_definition` 退化为平台元数据表

**决策**：删除所有引擎重复字段 + 所有业务字段。表只剩 AuraBoot 平台元数据（pid / tenant_id / deployment_id / plugin_pid / extension）。

**删除字段**（共 17 列）：
- 引擎重复：`process_key`, `process_name`, `description`, `category`, `bpmn_content`, `status`, `version`, `is_current`, `deployed_at`
- 业务策略下沉到 BPMN extension：`withdraw_policy`, `cc_policy`, `required_permissions`, `form_bindings`
- 死元数据：`business_data_bindings`
- 退役归 SLA：`timeout_hours`, `timeout_action`, `escalate_to_user_id`

**理由**：
- 引擎重复字段 → 单一真相在 `se_deployment_instance`，业务读时通过 `DeploymentQueryService` 查
- 业务策略 → 走 BPMN extension（详见 D5）
- `business_data_bindings` → grep 全仓没人 read 它做决策，纯死元数据
- timeout 字段是 SLA 表落地前的 "GAP-003" 临时实现，应 sunset

### D5. 节点级配置进 BPMN extension elements，不建独立表

**决策**：**不**建 `ab_bpm_node_config` 表。所有节点级配置（form binding / required permissions / cc policy override）通过 BPMN extension elements 表达。流程级业务策略（withdrawPolicy / ccPolicy）也进 `<process>` 元素的 extensionElements。

**理由**：
- **业界主流共识**：Camunda / Flowable / Activiti / SAP / 钉钉 / 飞书均采用"流程定义文件即真相"
- **没有流程结构在文件 + 节点配置在 DB 的派系**——这恰是当前设计的反模式
- Single source of truth：流程结构 + 节点配置 = 一个文件 = 一个版本，原子升降版
- 不可能"节点改名/删除但配置漂移"
- 插件包导入导出只需 .bpmn 文件
- SmartEngine 已经走 extension 路线（`smart:assigneeType` 等），延伸自然

**Trade-off 应对**：
- 跨流程查询难 → BpmnExtensionParser + Caffeine 缓存（按 deployment_id 失效），命中率近 100%
- 独立编辑成本 → 改业务策略 = 重新部署一个新版本，正是版本化本意
- 索引冗余的退路：将来真有"按策略类型批量查询流程"的 admin UI 需求，可在 `ab_bpm_process_definition` 加冗余 `withdraw_policy_indexed` 列，部署时由 deploy service 从 BPMN 抽取写入。**现在不加（YAGNI）**

### D6. BPMN extension 约定：复用 SmartEngine `<smart:properties>` 机制

**决策**：复用 SmartEngine 已有的 `<smart:properties>` 容器存放 AuraBoot 业务配置。**不引入新 namespace**——用名称前缀 `aura.` 隔离 AuraBoot 字段。

| Namespace | URI | 用途 |
|---|---|---|
| `smart:` | `http://smartengine.org/schema/process` | SmartEngine 已有 namespace，所有 extension 一律走它 |

**SmartEngine 现成能力**（关键发现）：

`com.auraboot.smart.framework.engine.smart.Properties` parser 在部署阶段解析 `<smart:properties>`，结果挂在 `IdBasedElement.getProperties()` 返回的 `Map<String, String>` 上。每个 BPMN 元素（`<process>`, `<userTask>`, `<serviceTask>` 等）都自带 `getProperties()` 方法。`RepositoryQueryService.getAllCachedProcessDefinition()` 已实现缓存，**业务侧不用自己写 XML parser、不用自己加缓存**。

**示例**：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:smart="http://smartengine.org/schema/process"
             targetNamespace="http://auraboot.com/bpm">

  <process id="leave_request" isExecutable="true">
    <extensionElements>
      <smart:properties>
        <smart:property name="aura.withdrawPolicy" value="strict"/>
        <smart:property name="aura.ccPolicy"       value="all"/>
      </smart:properties>
    </extensionElements>

    <userTask id="manager_approval" name="经理审批"
              smart:assigneeType="user"
              smart:assigneeExpression="${manager}">
      <extensionElements>
        <smart:properties>
          <smart:property name="aura.formKey"             value="leave-request-manager-form"/>
          <smart:property name="aura.requiredPermissions" type="json" value='["LEAVE_APPROVE_MANAGER"]'/>
          <smart:property name="aura.ccPolicyOverride"    value="initiator"/>
        </smart:properties>
      </extensionElements>
    </userTask>
  </process>
</definitions>
```

**命名约定**：
- AuraBoot 业务字段一律 `aura.<key>` 前缀，与 SmartEngine 自有 properties（如 `task1InParam1`）隔离
- 简单 string 值：`<smart:property name="aura.xxx" value="..."/>`
- 复杂 JSON 值：加 `type="json"` 属性 + value 内嵌 JSON 字符串（不需 CDATA，引号转义即可）
- 复杂结构尽量拆字段成多个简单 property；只有列表/对象才用 `type="json"`

### D7. Form binding 走 formKey 引用模式（Camunda 标准）

**决策**：BPMN 节点 extension 只放 `formKey="leave-request-manager"`（引用），form 定义本身在独立模块。

**理由**：Camunda 经典模式——表单和流程版本独立，互不强制耦合。AuraBoot 平台已有 `ab_page_schema`（page DSL）可作为 form 注册表的候选，具体 form repository 选型在 Spec 1 范围外讨论。

**Spec 1 范围内**：BpmnExtensionParser 提供 `getNodeFormKey(deploymentId, nodeId)`，业务侧自行解析 formKey 到 form 渲染。

### D8. SmartEngine 能力盘点与采用决策

| SmartEngine 能力 | AuraBoot 现状 | 处置 |
|---|---|---|
| Notification（CC/inform） | 自建 `ab_bpm_cc_record` | **替换**（D3） |
| Supervision（督办/监督） | 完全没用——0 wrapper、特性空缺 | **采用**（Spec 4 新增 BpmSupervisionService） |
| Task Transfer | `TaskService.transferTask()` 已包装 | 保持现状 |
| Assignee 操作记录 | SE 自动记 + `BpmAuditService` 重复记 | **去重**（Spec 1.5：审计查询读 SE 表，不再自写） |
| Execution（signal/jump/retry） | `ProcessOrchestrationService` 已用 signal；`jumpFrom`/`jumpTo` UNSAFE | **保持 + 收紧**（Spec 1.5：jump 限管理员） |
| Rollback | `TaskService` 已包装 | 保持现状 |
| TaskAssignee 候选/实际 | `IdAndGroupTaskAssigneeDispatcher` 已配置 | 保持现状 |
| AdHoc | 0 引用 | 暂不采用 |

### D9. Audit 边界收窄

`BpmAuditService` 只记 **SmartEngine 不感知的 AuraBoot 业务语义**：
- approve / reject（审批语义）
- withdraw（AuraBoot 自有，SE 没有 withdraw 概念）
- cc（业务侧记一次"我抄送了谁"，receiver/已读走 SE notification）

**SmartEngine 自动记的不重复写**：
- transfer → `se_task_transfer_record`
- rollback → `se_process_rollback_record`
- add/remove assignee → `se_assignee_operation_record`

**审计查询入口**（待 Spec 1.5 实现）统一通过 `BpmAuditQueryService` 聚合多源：

```java
List<AuditEvent> queryByProcess(processInstanceId)
  = ab_bpm_audit_record (业务语义)
  ∪ se_task_transfer_record (转移)
  ∪ se_process_rollback_record (回滚)
  ∪ se_assignee_operation_record (加减签);
```

### D10. 依赖方向（单向规则 / 红线）

**允许**：
```
business modules → framework/bpm/service/*
framework/bpm/service/* → SmartEngine API + DB mappers
```

**禁止**：
- 业务模块 `import com.auraboot.smart.framework.engine.*`
- 业务模块 `SELECT FROM se_*`（包括 namedQuery / 任何 SQL 路径）
- 业务模块 `SELECT FROM ab_bpm_*` 或 `SELECT FROM ab_sla_*`（绕过 service 层）
- `framework/bpm/` 反向依赖业务插件代码
- 重建任何"engine-agnostic"中间抽象层（违反 D1）

### D11. 触发器是薄壳

所有触发器（`BpmActionExecutor` / `BpmFormService` / Saga 回调）只做"拼参数 + 调 ProcessEngineService"，不重新实现引擎调用。每个触发器 < 200 LOC。

### D12. 测试 fixture 也走真路径

`TestBpmFixture` 全部走 `ProcessDeploymentService` + `ProcessEngineService`。**没有"测试快路径"**——任何在测试中 work 的代码必须在生产同样 work。`bpmEngine.deployProcess()` 之类的调用全部消除。

---

## 4. 数据模型最终形态

### 4.1 `ab_bpm_process_definition`（瘦身后）

```sql
CREATE TABLE ab_bpm_process_definition (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    pid             VARCHAR(26) UNIQUE NOT NULL,
    tenant_id       BIGINT NOT NULL,
    deployment_id   VARCHAR(64) NOT NULL,    -- FK → se_deployment_instance.id
    plugin_pid      VARCHAR(26),
    extension       JSONB NOT NULL DEFAULT '{}'::jsonb,
    deleted_flag    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      BIGINT,
    updated_by      BIGINT
);
CREATE UNIQUE INDEX uq_bpm_process_def_deployment
    ON ab_bpm_process_definition(deployment_id) WHERE deleted_flag = FALSE;
CREATE INDEX idx_bpm_process_def_tenant
    ON ab_bpm_process_definition(tenant_id) WHERE deleted_flag = FALSE;
CREATE INDEX idx_bpm_process_def_plugin
    ON ab_bpm_process_definition(plugin_pid) WHERE deleted_flag = FALSE;
```

业务字段 0 个。仅平台元数据。

### 4.2 删除的表

| 表 | 替代 |
|---|---|
| `ab_bpm_cc_record` | `se_notification_instance`（通过 NotificationCommandService） |

### 4.3 不创建的表

| 表 | 替代 |
|---|---|
| `ab_bpm_node_config` | BPMN extension elements（`<aura:property>`） |

### 4.4 保持的表

| 表 | 状态 |
|---|---|
| `ab_bpm_audit_record` | 角色收窄（D9） |
| `ab_bpm_domain_config` | Spec 2 范围 |
| `ab_sla_config` / `ab_sla_record` | Spec 2 范围（含 schema 清理） |
| `ab_chain_execution` | 现有 Saga / 链式审批，独立模块 |

---

## 5. Service 层职责清单

| Service | 职责 | 依赖 |
|---|---|---|
| `ProcessDeploymentService` | BPMN 部署 / undeploy / 元数据 CRUD / 解析 BPMN extension 写入冗余索引列（如有） | `SmartEngine.DeploymentCommandService` + `BpmProcessDefinitionMapper` |
| `ProcessEngineService` | start / suspend / resume / terminate / 查询；tenant + initiator 注入 | `SmartEngine.ProcessCommandService` + `BpmAuditService` |
| `TaskService` | approve / reject / transfer / claim / rollback；包装 SmartEngine TaskCommandService | `SmartEngine.TaskCommandService` + `BpmAuditService` |
| `WithdrawService` | 撤回 + WithdrawPolicy（strict/loose/none）；策略读 `<smart:properties>` | `SmartEngine.ProcessCommandService.abort` + `BpmAuditService` + `BpmExtensionAccessor` |
| `CcService` | 抄送 + CcPolicy 授权门；委托 SmartEngine NotificationService 发送和查询 | `SmartEngine.NotificationCommandService` + `BpmExtensionAccessor` |
| `BpmAuditService` | 业务语义审计写入与查询（仅 approve/reject/withdraw/cc-action） | `BpmAuditMapper` |
| `BpmAuditQueryService`（Spec 1.5 新增） | 聚合查询 ab_bpm_audit_record + se_task_transfer_record + se_process_rollback_record + se_assignee_operation_record | 多 mapper |
| `BpmFormService` | Form ↔ Process 桥接（form 提交 → 启动流程 / 完成任务） | `ProcessEngineService` + `TaskService` |
| `BpmExtensionAccessor`（新增，~50 LOC） | 类型安全 wrapper：将 `IdBasedElement.getProperties().get("aura.xxx")` 包装成 `Optional<WithdrawPolicy> getWithdrawPolicy(processKey)` 等 typed 访问器。SmartEngine 已自动解析 + 缓存，本类不做 IO | `SmartEngine.RepositoryQueryService` |
| `BpmActionExecutor` | Action.executionMode=bpm 的薄壳触发器；jsonpath 抽变量 + 调 `ProcessEngineService.startProcess` | `ProcessEngineService` |
| `BpmSupervisionService`（Spec 4） | 督办创建 / 查询 / 自动关闭；包装 SmartEngine SupervisionService | `SmartEngine.SupervisionCommandService` |

---

## 6. Spec 拆分与路线图

| Spec | 范围 | 状态 |
|---|---|---|
| **Spec 1** | CC 改用 SmartEngine NotificationService（重写 CcService、删 `ab_bpm_cc_record`）+ Withdraw（保留，但策略改读 BPMN extension）+ BpmActionExecutor（薄壳）+ 删除 BpmEngine 抽象 + BpmnExtensionParser 工具类 + bpm-panel 前端 | **进行中**（部分已完成代码需重构） |
| **Spec 1.5** | `ab_bpm_process_definition` 瘦身（删 17 业务列）+ 删 `business_data_bindings` 字段+DTO+插件 schema + 审计去重（BpmAuditQueryService 聚合多源）+ jump UNSAFE 收紧到管理员权限 + sunset timeout 字段（迁 SLA） | 待启动（Spec 1 完成后立即） |
| **Spec 2** | SLA 模块完善（schema 大小写统一、命名修正、索引补全、可视化前端） | 待启动 |
| **Spec 3** | Drools 规则可视化 + 网关条件编辑器 | 待启动 |
| **Spec 4** | Supervision 模块（BpmSupervisionService + endpoint + supervision-list block） | 待启动 |

**Spec 1.5 的紧迫性**：在 Spec 1 完成后立即做。否则下一个 spec 会继续基于错的 schema 写代码。

---

## 7. Spec 1 范围调整（受本设计影响）

Spec 1 原始计划基于"DB 列存策略"。本设计转为"BPMN extension 存策略"后，**已完成代码需要部分回滚和重构**：

| Task | 原始范围 | 调整后 |
|---|---|---|
| Task 0（前置）SmartEngine 部署修复 | ✅ 完成 | 保持 |
| Task 1 schema：`withdraw_policy` / `cc_policy` / `required_permissions` 列 | ✅ 完成 | **回滚 ALTER**（不再加列） |
| Task 1 schema：`ab_bpm_cc_record` 表 + 索引 | ✅ 完成 | **回滚 CREATE TABLE**（删除） |
| Task 2 `WithdrawPolicy` / `CcPolicy` enums | ✅ 完成 | **保留**（值仍是 BPMN extension 的合法取值） |
| Task 2 实体新增字段 | ✅ 完成 | **回滚 entity getter/setter** |
| Task 3 `WithdrawService` | ✅ 完成 | **改写** policy 读取：从 `def.getWithdrawPolicy()` 改为 `extensionAccessor.getWithdrawPolicy(processKey)`（底层走 SmartEngine `getProperties().get("aura.withdrawPolicy")`） |
| Task 4 `BpmCcRecord` entity + mapper | ✅ 完成 | **删除** |
| Task 5 `CcService` + 集成测试 | ✅ 完成 | **重写**（用 NotificationService 替代自有持久化）|
| Task 6 `BpmActionExecutor` | ⚠️ 待重做 | 按 D11 重新实现（用 `ProcessEngineService`，不用 `BpmEngine`） |
| Task 7-13 前端 | ⏸ 未启动 | 调整：bpm-panel 通过 `/api/bpm/notifications/inbox` 拉 CC 列表（SmartEngine 数据） |
| Task 14 文档同步 | ⏸ 未启动 | 同步本目标设计 |

**新增 Task**：
- Task -1：`BpmExtensionAccessor` typed wrapper + 单元测试（约 50 LOC，复用 SmartEngine 已有 `getProperties()` 解析）
- Task 0a：删除 `BpmEngine` 抽象层 + 修 `TestBpmFixture.deployProcess(String)` 走真路径
- Task 0b：升级测试 fixture BPMN 模板，加 `<smart:properties>` 含 `aura.withdrawPolicy` / `aura.ccPolicy` 等

---

## 8. 反模式 / 红线

加入 `docs/standards/architecture.md`：

### 红线 RL-BPM-1：BPM 模块入口约束
- 业务模块禁止 `import com.auraboot.smart.framework.engine.*`
- 业务模块禁止 `SELECT FROM se_*` / `SELECT FROM ab_bpm_*` / `SELECT FROM ab_sla_*`（包括 namedQuery）
- 业务模块只能通过 `framework/bpm/service/*` 公开方法访问 BPM 能力

### 红线 RL-BPM-2：禁止 BPM 抽象层
- 禁止重建 `framework/bpm/engine/` 包
- 禁止新增"engine-agnostic" facade / adapter / interface
- 历史教训：`BpmEngine` 抽象层 2 次导致 subagent 走错路（Task 3 + Task 6）

### 红线 RL-BPM-3：节点配置必须在 BPMN 中
- 节点级业务策略（form binding / required permissions / cc override）必须放 `<extensionElements><smart:properties>` 中，名称前缀 `aura.`
- 禁止新建 `ab_bpm_node_config` 类似的"节点配置表"
- 流程级业务策略（withdrawPolicy / ccPolicy）必须放 `<process>` 元素的 `<extensionElements><smart:properties>`，名称前缀 `aura.`
- 索引冗余字段（如有）必须由 deploy service 从 BPMN 抽取写入，BPMN 是真相
- 禁止重新引入额外 namespace（不允许 `xmlns:aura="..."`、`xmlns:flowable="..."` 等）；统一走 SmartEngine 的 `smart:` namespace + `aura.` 名称前缀

### 红线 RL-BPM-4：Audit 边界
- `BpmAuditService` 只记 SmartEngine 不感知的业务语义（approve/reject/withdraw/cc-action）
- 禁止重复写入 SmartEngine 已自动记录的事件（transfer/rollback/assignee 操作）
- 审计查询通过 `BpmAuditQueryService` 聚合多源，不直接 SQL

### 红线 RL-BPM-5：测试 fixture 走真路径
- 测试 fixture 禁止使用任何"测试快路径"绕过生产代码
- 部署、启动、查询全部走 `ProcessDeploymentService` + `ProcessEngineService` + SmartEngine API

---

## 9. 实施代价估算（粗略）

| 项 | LOC 变化 |
|---|---|
| 删除 `framework/bpm/engine/` 整包 | -700 |
| 删除 `ab_bpm_cc_record` 相关代码（entity / mapper / typehandler / `CcService` 重写） | -300 + 80 = 净 -220 |
| `ab_bpm_process_definition` 瘦身 + 所有 reader 调整 | ±0（删 column getter，加 BpmnExtensionParser 调用）|
| 删除 `business_data_bindings` 全链路 | -100 |
| `BpmExtensionAccessor` typed wrapper（复用 SmartEngine 现成 parser） | +50 |
| `BpmAuditQueryService` 聚合多源 | +150 |
| Spec 1 已 commit 代码部分回滚 | -200 |
| **净变化** | **约 -1020 LOC** |

不含 Spec 4（Supervision 模块）和 Spec 2（SLA）的新增工作量。

---

## 10. Open Questions

| # | 问题 | 备注 |
|---|---|---|
| Q1 | Form repository 选型：复用 `ab_page_schema` 还是新建 `ab_form_definition`？ | 不阻塞 Spec 1（Spec 1 只需 BpmnExtensionParser 提供 formKey 字符串）；Spec 1 完成后单独讨论 |
| Q2 | ~~`BpmnExtensionParser` 缓存失效粒度~~ | **已解决**：复用 SmartEngine `RepositoryQueryService.getAllCachedProcessDefinition()`，缓存策略由 SmartEngine 管理，无需自建 |
| Q3 | Spec 1.5 是否合并进 Spec 1？ | 倾向独立——Spec 1 是用户可见的"审批语义补齐"，Spec 1.5 是内部清理；分开发布更清晰 |
| Q4 | 索引冗余列的同步机制：trigger / service 主动写？ | 倾向 service 主动写（trigger 难调试）；现在不实现 |

---

## Reviewers

待 Controller 评审本设计后，启动 Spec 1 重构 + Spec 1.5 plan 编写。
