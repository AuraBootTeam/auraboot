---
type: mockup-reference
status: active
created: 2026-06-24
---

# 规则中心元模型驱动方案全量需求

## 1. 背景与来龙去脉

本轮工作的起点不是单独做一个规则页面,而是核查 OSS main 环境下 BPM、规则中心、SLA、设计器等链路是否真实可用。实际核查过程中暴露出几个连续问题:

1. BPM 流程管理页能打开,但流程标识、流程名称、部署时间等关键字段为空或不可读,页面像 seed/demo 数据,不像可核查链路的产品页面。
2. SLA monitor 和 SLA config detail 打开体验异常,有的链接进入登录页或打不开,页面交互不像完整产品。
3. 决策中心页面虽然有入口,但用户难以判断应该在哪里配置规则、在哪里配置表达式、规则如何被 SLA/BPM/Automation 复用。
4. 早期 mockup 把“规则中心是基础设施”讲清楚了一部分,但没有把真实操作路径做清楚:业务人员如何新建规则、选择字段、组合条件、沉淀共享条件片段、绑定 DMN、输出动作、发布给各模块使用。
5. 用户补充平台底层已有元模型能力:业务 model、field、dict、virtual model。规则中心不能另造孤立字段体系,必须消费这套元模型。
6. 用户进一步明确:规则会和很多能力打交道,包括发送消息、抄送任务、短信、IM、更新记录、发起流程、执行命令、调用 API 等;条件多样化,触发后的行为也多样化。
7. 最新诉求是看“真实页面长什么样”,不是看解释型方案页。因此当前 mockup 已从说明页改为后台产品工作台形态,删除长段解释,通过页面控件、数据、状态和表格表达完整业务。

结论:规则中心应该作为平台基础设施建设,并提供统一 Strategy Studio UI。SLA、BPM、Automation、Permission 等模块不是各自实现一套规则配置页,而是以消费方身份引用同一套事实目录、条件片段、DMN 决策、动作目录和发布治理能力。

## 2. 产品定位

规则中心是 AuraBoot 平台的策略基础设施,用于配置、复用、执行和治理所有会影响业务结果的条件与决策。

它要解决的不是“能写一个表达式”,而是以下完整问题:

- 字段从哪里来:从平台元模型、字典和虚拟模型来。
- 条件怎么配置:用可视化条件构造器配置,保存为结构化 Condition AST。
- 条件怎么复用:沉淀为共享条件片段,有编号、版本、作用域、消费者和影响面。
- 复杂决策怎么做:用 DMN/决策表表达多输入、多输出规则。
- 命中后做什么:通过统一动作目录执行消息、短信、IM、任务抄送、记录更新、流程启动、命令执行、API/webhook 调用等动作。
- 各模块怎么接入:SLA、BPM、Automation、Permission 通过 consumerType/consumerRef 引用规则、条件片段或决策。
- 发布前怎么保证安全:必须检查字段、字典、虚拟模型、片段版本、DMN 分析、动作绑定和影响面。
- 运行后怎么追踪:每次执行都应有 traceId、输入摘要、结果、命中规则、动作执行状态和调用方信息。

## 3. 不做什么

本需求明确不做以下方向:

- 不让业务用户手写 Drools/DRL 或任意脚本。
- 不让 SLA、BPM、Automation、Permission 各自维护一套不同的条件语法。
- 不把字段列表硬编码在页面 props 或 seed 数据里。
- 不做一个只展示能力的 demo 页面。
- 不把 mockup 做成说明文档式页面。
- 不用“先跑通 MVP”作为省略版本治理、影响面、测试运行、权限和审计的理由。

## 4. 统一概念模型

### 4.1 Fact Catalog 事实目录

Fact Catalog 是规则中心能使用的字段事实集合。它来自四类来源:

| 来源 | 示例 | 运行时路径 |
| --- | --- | --- |
| MODEL | 客诉工单.priority、申请单.amount | `record.data.priority`, `record.data.amount` |
| VIRTUAL_MODEL | 客诉处置上下文.customerTier、overdueMinutes | `record.data.customerTier`, `record.data.overdueMinutes` |
| ACTOR | 当前操作者角色、组织路径 | `actor.roles`, `actor.orgPath` |
| SYSTEM | 当前时间、租户、流程上下文 | `runtime.now`, `process.taskKey` |

每个事实字段至少需要包含:

```ts
type FactCatalogItem = {
  sourceType: 'MODEL' | 'VIRTUAL_MODEL' | 'ACTOR' | 'SYSTEM';
  sourceCode: string;
  fieldId?: string;
  fieldCode: string;
  label: string;
  dataType: 'string' | 'text' | 'integer' | 'decimal' | 'boolean' | 'date' | 'datetime' | 'enum' | 'reference' | 'json';
  dictCode?: string;
  refPath: string;
  derived: boolean;
  nullable: boolean;
  writable: boolean;
  usageCount: number;
};
```

### 4.2 Dict 字典

字典用于驱动 enum 字段的可选值、展示标签、条件操作符和 DMN 输入值。保存时使用稳定 code,展示时使用业务标签。

例子:

```ts
type DictDefinition = {
  dictCode: 'ticket_priority' | 'customer_tier' | 'sla_status';
  values: Array<{
    code: string;
    label: string;
    enabled: boolean;
    color?: 'neutral' | 'blue' | 'amber' | 'green' | 'red';
  }>;
};
```

字典变化必须进入影响面检查。新增、禁用、重命名字典值时,所有引用该字典值的条件片段、DMN 表和动作模板都需要提示风险。

### 4.3 Virtual Model 虚拟模型

虚拟模型用于把跨模块上下文组织成可用事实。例如 SLA 超时升级可能同时需要工单、客户、SLA 记录、BPM 当前任务、操作者组织信息。如果每个规则都手动拼这些字段,规则会失控。

示例:

```ts
type VirtualFactModel = {
  virtualModelCode: 'complaint_resolution_context';
  label: '客诉处置上下文';
  joins: Array<{
    from: string;
    to: string;
    on: string;
  }>;
  computedFields: Array<{
    fieldCode: 'customerTier' | 'overdueMinutes' | 'successOwner';
    label: string;
    dataType: string;
    refPath: string;
  }>;
};
```

虚拟模型必须支持发布前校验。如果 join 字段、计算表达式或权限上下文失效,依赖它的策略不能发布。

### 4.4 Condition AST 条件结构

业务条件统一保存为结构化 AST,而不是保存页面表达式字符串。

示例:

```ts
type ConditionAst = {
  type: 'group';
  op: 'and' | 'or';
  children: Array<ConditionAst | ConditionClause>;
};

type ConditionClause = {
  type: 'clause';
  fieldRef: string;
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in' | 'not_in' | 'contains' | 'exists' | 'between';
  value: unknown;
};
```

必须支持:

- AND / OR 条件组。
- 嵌套条件组。
- 字段选择器。
- 根据字段类型显示合法操作符。
- 根据字典显示合法枚举值。
- 根据 reference 类型显示用户/组织/模型记录选择器。
- 保存后可回显。
- 前端可预览,后端权威执行。
- 非法字段、非法操作符、非法字典值必须报错。

### 4.5 Condition Fragment 共享条件片段

条件片段是可复用资产。它不是某条规则内部的临时条件。

示例:

```ts
type ConditionFragment = {
  fragmentCode: string;
  name: string;
  version: number;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  scope: Array<'SLA' | 'BPM' | 'AUTOMATION' | 'PERMISSION' | 'EVENT_POLICY'>;
  conditionSpec: { root: ConditionAst };
  inputFacts: string[];
  owner: string;
  consumers: Array<{
    consumerType: string;
    consumerRef: string;
    versionPolicy: 'fixed' | 'latestCompatible';
  }>;
};
```

片段必须支持:

- 新建片段。
- 保存当前条件为片段。
- 引用固定版本。
- 引用 latest compatible。
- 派生新版本。
- 替换引用。
- 查看差异。
- 查看影响面。
- 禁用或废弃旧版本。

### 4.6 Strategy Rule 策略规则

Strategy Rule 是消费场景中的具体规则草稿或发布版本。

示例:

```ts
type StrategyRule = {
  ruleCode: string;
  name: string;
  consumerType: 'SLA' | 'BPM' | 'AUTOMATION' | 'PERMISSION' | 'EVENT_POLICY';
  consumerRef: string;
  trigger: string;
  conditionRef?: {
    fragmentCode: string;
    versionPolicy: 'fixed' | 'latestCompatible';
    version?: number;
  };
  inlineConditionSpec?: { root: ConditionAst };
  decisionRef?: string;
  actionSequence?: ActionBinding[];
  status: 'DRAFT' | 'PUBLISHED' | 'DISABLED';
  version: number;
};
```

### 4.7 Decision / DMN 决策

DMN/决策表用于多条件、多输出的业务判断。

典型输入:

- priority
- customerTier
- amount
- riskLevel
- overdueMinutes
- actor.roles

典型输出:

- route
- assigneeUserId
- candidateGroup
- approvalMode
- escalationLevel
- channels
- ownerUserId
- actions

决策表必须支持:

- 输入列来自 Fact Catalog。
- 输出列有明确 schema。
- 单元格支持枚举、区间、列表、通配符。
- 支持分析 gap、overlap、conflict。
- 支持 test-run。
- 支持导入/导出 DMN。
- 支持运行时执行并返回结构化输出。

### 4.8 Action Schema 动作目录

动作目录是规则命中后的行为集合。动作不是写死在 SLA 或 BPM 页面里,而是平台统一能力。

必须至少覆盖:

| 动作 | 用途 |
| --- | --- |
| send_notification | 站内通知、邮件、短信、IM |
| cc_task | 抄送 BPM 任务或业务任务 |
| update_record | 更新当前或关联模型记录 |
| create_record | 创建任务、待办、工单、跟进记录 |
| execute_command | 执行平台 command |
| call_api | 调用外部 API |
| send_webhook | 发 webhook |
| start_process | 发起 BPM 流程 |
| transfer_task | 转派当前任务 |

动作参数可以绑定:

- `record.data.*`
- `actor.*`
- `decision.*`
- `process.*`
- `runtime.*`
- 前一个 action 的输出。

### 4.9 Usage Index 影响面

规则中心必须维护统一影响面索引。以下资产都要能被追踪:

- model field
- dict value
- virtual model field
- condition fragment
- decision table
- action schema
- consumer rule

影响面至少回答:

- 这个字段被哪些条件片段使用?
- 这个字典值被哪些 DMN 单元格使用?
- 这个条件片段被哪些 SLA/BPM/Automation/Permission 引用?
- 这个虚拟模型改动会阻断哪些策略发布?
- 这个动作 schema 改动会影响哪些规则?

### 4.10 Decision Log 执行日志

每次运行都要记录:

```ts
type DecisionLog = {
  traceId: string;
  ruleCode?: string;
  fragmentCode?: string;
  decisionCode?: string;
  consumerType: string;
  consumerRef: string;
  inputDigest: string;
  matched: boolean;
  result: unknown;
  actionResults: unknown[];
  durationMs: number;
  error?: string;
  createdAt: string;
};
```

默认不保存完整敏感输入,但 debug 模式可以短期保留脱敏输入。

## 5. 消费场景需求

### 5.1 SLA

SLA 需要使用规则中心处理:

- SLA 适用条件:哪些记录、流程节点、客户等级适用某个 SLA。
- deadline 计算:固定时长、字段驱动、决策表驱动。
- warningRules:提前 30 分钟、60 分钟、24 小时等预警。
- breach:超时后升级。
- escalation actions:发送通知、短信、IM、抄送任务、转派、更新记录、发起流程、执行 Automation。

例子:

```ts
{
  consumerType: 'SLA',
  consumerRef: 'complaint_handle_node_sla',
  trigger: 'breach.warning.30m',
  conditionRef: { fragmentCode: 'FRAG_URGENT_HIGH_VALUE_TICKET', versionPolicy: 'fixed', version: 3 },
  decisionRef: 'DMN_SLA_ESCALATION',
  actionSequence: ['send_notification', 'cc_task', 'update_record']
}
```

### 5.2 BPM

BPM 需要使用规则中心处理:

- exclusive gateway 路由。
- rule task。
- 审批人 resolver。
- 候选组 resolver。
- 会签/或签策略。
- 节点进入前校验。
- 节点完成后动作。
- 自动通过/自动驳回条件。

例子:

```ts
{
  consumerType: 'BPM',
  consumerRef: 'complaint_approval_task',
  trigger: 'task.enter.approval',
  conditionRef: { fragmentCode: 'FRAG_HIGH_AMOUNT_APPROVAL', versionPolicy: 'fixed', version: 5 },
  decisionRef: 'DMN_APPROVER_ROUTE',
  actionSequence: ['cc_task', 'send_notification']
}
```

### 5.3 Automation

Automation 需要使用规则中心处理:

- trigger condition。
- control-condition 节点。
- action 参数映射。
- BPM 事件触发后的条件判断。
- 记录创建、更新、状态变化后的动作编排。

例子:

```ts
{
  consumerType: 'AUTOMATION',
  consumerRef: 'ticket_escalation_auto',
  trigger: 'record.updated',
  conditionRef: { fragmentCode: 'FRAG_URGENT_HIGH_VALUE_TICKET', versionPolicy: 'latestCompatible' },
  actionSequence: ['send_notification', 'start_process', 'call_api']
}
```

### 5.4 Permission / ABAC

Permission 需要使用规则中心处理:

- 行级可见性。
- 字段可编辑性。
- 操作按钮可用性。
- 命令执行前校验。

例子:

```ts
{
  consumerType: 'PERMISSION',
  consumerRef: 'ticket_row_access',
  trigger: 'query.precheck',
  conditionRef: { fragmentCode: 'FRAG_SAME_ORG_OWNER_VISIBLE', versionPolicy: 'fixed', version: 2 },
  decisionRef: 'DMN_TICKET_ACCESS'
}
```

### 5.5 Event Policy

Event Policy 需要使用规则中心处理:

- 事件是否命中。
- 事件是否需要进入 Automation。
- 事件是否需要通知或审计。
- 幂等 key 如何生成。

### 5.6 动态页面与 bindingRules

动态页面和 bindingRules 也可能消费同一套条件能力:

- 字段 visibleWhen / enableWhen / readOnlyWhen。
- 表单提交前校验。
- 字段联动填充。
- 跨字段校验。

这类 UI-only 表达式可以留在前端 runtime,但影响业务结果的规则必须落到 Condition AST 或 decisionRef。

## 6. 最新 mockup 对应的页面需求

最新 mockup 是产品态后台页面,不是说明页。页面名称为“策略编排器”。

页面结构:

1. 左侧导航:策略编排器、条件片段、DMN 决策表、动作目录、执行日志、SLA、BPM、Automation、Permission。
2. 顶部操作:影响面、测试运行、保存草稿、发布。
3. 标题区:当前策略名称、状态、版本、ruleCode、owner、更新时间。
4. 场景 tab:SLA、BPM、Automation、Permission。
5. 指标区:消费方数量、字段事实数量、动作数量、阻断项数量。
6. 左侧事实目录:事实来源、字段列表、字段类型、字典标签、运行时路径。
7. 中间规则配置:ruleCode、consumer、trigger、versionPolicy。
8. 中间条件构造器:字段、操作符、值、AND/OR、添加条件、添加条件组。
9. 中间动作输出:审批人、抄送任务、发送消息等动作节点。
10. 中间 DMN 表:输入列、输出列、分析、导出。
11. 右侧条件片段库:片段名称、版本、消费者数量、关键字段。
12. 右侧检查器:当前选中的 field 或 fragment 的详细信息。
13. 右侧发布检查:字段目录、片段版本、DMN 分析、影响面。
14. 底部影响面表:资产、引用、消费者、版本策略、状态。

页面交互:

- 点击场景 tab 后,标题、ruleCode、consumer、trigger、versionPolicy、默认片段同步切换。
- 点击字段后,字段高亮,检查器显示字段名称、字段 code、运行时路径、字典或类型。
- 点击条件片段后,片段高亮,条件构造器标题和检查器显示片段信息。
- 点击发布前必须先通过发布检查。
- DMN 表在移动端使用卡片内横向滚动,不撑破页面。

页面视觉要求:

- 不出现讲解型长段文本。
- 不用营销页 hero。
- 不嵌套大卡片堆叠解释能力。
- 页面像真实后台产品页,不是 demo。
- 控件高度、圆角、间距、状态色接近 AuraBoot 管理台风格。
- 数据密度要支持扫描,但不能挤压关键规则配置。

## 7. 用户旅程

### 7.1 新建 BPM 审批路由规则

1. 用户进入策略编排器。
2. 选择 BPM 场景。
3. 创建规则 `BPM_APPROVER_ROUTE`。
4. 选择触发点 `task.enter.approval`。
5. 从条件片段库选择 `高金额审批升级 v5`。
6. 检查条件:
   - 申请金额大于 100,000。
   - 风险等级属于高或极高。
   - actor.roles 包含 department_manager。
7. 打开 DMN 决策表。
8. 配置输出 route、assignee、actions。
9. 配置动作:审批人、抄送任务、发送消息。
10. 点击测试运行。
11. 查看命中结果和动作输出。
12. 查看影响面。
13. 发布。

### 7.2 新建 SLA 超时升级规则

1. 用户选择 SLA 场景。
2. 创建规则 `SLA_ESCALATE_HIGH_VALUE`。
3. 选择触发点 `breach.warning.30m`。
4. 选择条件片段 `高价值紧急客诉 v3`。
5. 条件使用 priority、customerTier、overdueMinutes。
6. 决策输出 escalationLevel、channels、ownerUserId。
7. 动作包括发送 IM、短信、邮件、抄送任务、更新工单升级等级。
8. 发布前检查字典值和虚拟模型是否有效。
9. 发布后 SLA scheduler 调用 Decision Runtime 执行。

### 7.3 Automation 复用条件片段

1. 用户选择 Automation 场景。
2. 创建记录更新触发规则。
3. 复用 `高价值紧急客诉` 条件片段,版本策略为 latest compatible。
4. 命中后执行 send_notification、start_process、call_api。
5. 运行日志记录 traceId、命中片段、动作结果。

### 7.4 Permission ABAC 复用条件片段

1. 用户选择 Permission 场景。
2. 创建行级可见性规则。
3. 复用 `同组织负责人可见 v2`。
4. 条件使用 actor.roles、org.path、record.data.assigneeUser。
5. 输出 matched 或 denyReason。
6. 权限 runtime 在查询或命令前置校验时调用。

## 8. 保存与发布流程

### 8.1 保存草稿

保存草稿时必须保存:

- ruleCode
- consumerType
- consumerRef
- trigger
- conditionRef 或 inlineConditionSpec
- decisionRef
- actionSequence
- versionPolicy
- status = DRAFT

### 8.2 校验

校验必须覆盖:

- 字段是否存在。
- 字段类型是否支持操作符。
- 字典值是否合法。
- 虚拟模型是否可解析。
- 条件 AST 是否合法。
- DMN 输入输出 schema 是否匹配。
- 动作参数是否能绑定到字段或决策输出。
- 消费方是否有权限引用该规则。

### 8.3 测试运行

测试运行需要用户提供或系统生成一份上下文:

```ts
type DecisionTestContext = {
  record: { data: Record<string, unknown> };
  actor: { roles: string[]; orgPath?: string };
  process?: { processKey?: string; taskKey?: string; taskId?: string };
  sla?: { recordPid?: string; overdueMinutes?: number };
  runtime: { now: string; tenantId: string };
};
```

返回:

- matched。
- 命中的条件片段。
- DMN 输出。
- 将执行的 actions。
- 校验或执行错误。
- traceId。

### 8.4 发布

发布前必须通过:

- Fact Catalog 校验。
- Condition Fragment 版本校验。
- DMN 分析。
- Action Schema 校验。
- Usage Impact 确认。
- 权限校验。

如果影响面中存在高风险变更,必须要求用户确认。

## 9. 权限需求

至少需要以下权限:

| 权限 | 能力 |
| --- | --- |
| strategy.rule.read | 查看策略 |
| strategy.rule.write | 编辑草稿 |
| strategy.rule.publish | 发布策略 |
| strategy.fragment.read | 查看条件片段 |
| strategy.fragment.write | 新建/编辑条件片段 |
| strategy.fragment.deprecate | 废弃片段版本 |
| strategy.decision.write | 编辑 DMN |
| strategy.action.write | 配置动作 |
| strategy.impact.read | 查看影响面 |
| strategy.test.run | 试运行 |

## 10. 审计需求

需要审计以下操作:

- 创建规则。
- 修改规则。
- 发布规则。
- 禁用规则。
- 创建条件片段。
- 派生条件片段版本。
- 替换条件片段引用。
- 导入/导出 DMN。
- 试运行。
- 确认影响面。
- 执行动作。

## 11. 错误与空态

页面必须覆盖:

- 没有可用事实字段:显示空态和刷新/同步元模型动作。
- 字段被删除:规则显示阻断,不能发布。
- 字典值被禁用:显示 warning,要求用户替换或确认。
- 虚拟模型不可解析:依赖策略不能发布。
- 条件片段版本不存在:引用显示错误。
- DMN 分析存在 gap/overlap/conflict:不能直接发布。
- 动作参数缺失:动作节点显示错误。
- 测试运行失败:显示 traceId 和错误原因。

## 12. E2E 验收需求

前端浏览器必须覆盖:

- 场景切换:SLA/BPM/Automation/Permission。
- 字段选择:字段高亮,检查器更新。
- 条件片段选择:片段高亮,条件构造器和检查器更新。
- 条件编辑:添加条件、删除条件、切换 AND/OR、选择字典值。
- 保存为片段。
- 派生版本。
- 替换引用。
- DMN 编辑、分析、导出。
- 动作配置。
- 测试运行。
- 影响面确认。
- 发布。
- 移动端 DMN 表格横向滚动。

后端集成必须覆盖:

- Fact Catalog 返回 model/field/dict/virtual model 字段。
- 没有任何决策引用时,业务模型字段仍能进入事实目录。
- Condition AST 校验和执行。
- Condition Fragment 版本引用。
- Decision Runtime test-run。
- SLA 调用规则输出 warning/breach actions。
- BPM 调用规则输出 route/assignee/actions。
- Automation 调用规则输出 actions。
- Permission 调用规则输出 matched/denyReason。
- DecisionLog 写入 traceId。

## 13. 完成定义

本需求完成的标准:

1. 用户可以从统一 Strategy Studio 完成规则创建、条件配置、片段复用、DMN 绑定、动作配置、测试运行、影响面确认和发布。
2. SLA、BPM、Automation、Permission 至少各有一条真实消费链路。
3. 字段目录来自平台元模型,不是硬编码字段。
4. 条件保存为 Condition AST 或 conditionRef。
5. 复杂决策由 DMN/Decision Table 承载。
6. 动作来自统一 Action Schema。
7. 发布前有影响面检查。
8. 运行后有日志和 traceId。
9. 浏览器 E2E 和后端集成证据成对。
10. 页面观感是产品工作台,不是 demo 或说明页。
