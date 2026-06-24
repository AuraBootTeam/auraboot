---
type: mockup-reference
status: active
created: 2026-06-24
---

# 规则中心元模型驱动最新 Mockup 说明

## 1. Mockup 当前状态

当前最新 mockup 文件:

`docs/assets/mockups/rule-center-meta-model-v4-mock.html`

它已经从说明型页面改为产品工作台页面。页面内不再放大段需求解释,而是使用真实后台页面结构表达业务:

- 顶部栏:面包屑、影响面、测试运行、保存草稿、发布。
- 标题区:策略名称、状态、版本、ruleCode、owner、更新时间。
- 场景 tab:SLA、BPM、Automation、Permission。
- 指标区:消费方、字段事实、动作、阻断项。
- 左侧:事实目录和字段列表。
- 中间:规则配置、条件构造器、动作输出、DMN 决策表。
- 右侧:条件片段库、检查器、发布检查。
- 底部:影响面表。

## 2. 这版 mockup 要表达的产品形态

这不是一个单独的规则 demo,而是 AuraBoot 的统一策略编排器。它服务多个消费场景:

- SLA 用它配置适用条件、预警、超时升级和通知动作。
- BPM 用它配置网关路由、审批人、候选组、会签策略和节点动作。
- Automation 用它配置触发条件、控制节点和动作参数映射。
- Permission 用它配置 ABAC、行级可见性和命令前置校验。

页面目标是让用户明确看到:

1. 当前正在配置哪条规则。
2. 规则属于哪个消费场景。
3. 规则由哪个触发点触发。
4. 条件来自哪个共享片段。
5. 条件片段内部有哪些条件。
6. 条件引用了哪些元模型字段。
7. DMN 决策表如何把输入转成输出。
8. 命中后执行哪些动作。
9. 发布前有哪些检查。
10. 改动会影响哪些消费者。

## 3. 页面区域说明

### 3.1 左侧导航

左侧导航使用平台后台导航风格。当前高亮项为“策略编排器”。

导航项:

- 策略编排器
- 条件片段
- DMN 决策表
- 动作目录
- 执行日志
- SLA
- BPM
- Automation
- Permission

意图:

- 规则中心是基础设施入口。
- SLA/BPM/Automation/Permission 是消费场景入口。
- 条件片段、DMN、动作目录、执行日志是基础资产入口。

### 3.2 顶部操作区

顶部操作包含:

- 影响面
- 测试运行
- 保存草稿
- 发布

意图:

- 保存草稿不等于发布。
- 发布前必须能查看影响面。
- 测试运行是设计器内的一等动作。
- 发布是主按钮。

### 3.3 标题区

标题区显示当前策略:

- 策略名称,如 `BPM 审批路由策略`。
- 状态,如 Draft。
- 版本,如 v7。
- 规则编号,如 `BPM_APPROVER_ROUTE`。
- owner。
- 更新时间。

意图:

- 让用户知道自己正在编辑哪条策略。
- 让版本、状态、责任人可见。

### 3.4 场景 tab

场景 tab 包括:

- SLA
- BPM
- Automation
- Permission

点击后页面会切换当前规则上下文:

- 标题变化。
- ruleCode 变化。
- consumer 变化。
- trigger 变化。
- 默认条件片段变化。

示例:

| 场景 | ruleCode | consumer | trigger |
| --- | --- | --- | --- |
| SLA | SLA_ESCALATE_HIGH_VALUE | SLA / warningRules | breach.warning.30m |
| BPM | BPM_APPROVER_ROUTE | BPM / gateway + assignee | task.enter.approval |
| Automation | AUTO_TICKET_ESCALATION | Automation / trigger | record.updated |
| Permission | ABAC_TICKET_VISIBILITY | Permission / row access | query.precheck |

### 3.5 指标区

指标包括:

- 消费方数量。
- 字段事实数量。
- 动作数量。
- 阻断项数量。

意图:

- 这是工作台状态摘要,不是营销指标。
- 用户能快速判断当前策略复杂度和发布风险。

### 3.6 事实目录

事实目录位于左侧工作区。

来源包括:

- 客诉工单 MODEL。
- 客诉处置上下文 VIRTUAL。
- 操作者与组织 SYSTEM。

字段包括:

- 工单优先级 `record.data.priority`
- 客户等级 `record.data.customerTier`
- 申请金额 `record.data.amount`
- 责任处理人 `record.data.assigneeUser`
- 超时分钟 `record.data.overdueMinutes`

每个字段展示:

- 业务名称。
- 运行时路径。
- dict 或 dataType。

意图:

- 业务用户不需要手写路径。
- 技术侧仍能看到实际运行时 refPath。
- 字段来自元模型和虚拟模型,不是页面硬编码。

### 3.7 规则卡片

规则卡片位于中间上方。

字段:

- 规则编号。
- 消费场景。
- 触发点。
- 版本策略。

按钮:

- 校验 AST。
- 试运行。
- 保存新版本。
- 禁用。

意图:

- 规则本身是一等资产。
- 条件、DMN、动作都是规则的组成部分。
- 版本策略直接可见。

### 3.8 条件与输出

条件区域展示当前共享条件片段。

示例条件:

- 申请金额 > 100,000。
- 风险等级 属于 高、极高。
- actor.roles 包含 department_manager。

支持动作:

- 添加条件。
- 添加条件组。
- 切换 OR。
- 保存为片段。
- 派生版本。

右侧动作输出展示:

- 审批人 `decision.assigneeUserId`
- 抄送任务 `successOwner, teamLead`
- 发送消息 `IM + Email`

意图:

- 条件配置是主流程,不是隐藏在弹窗里。
- 片段复用和版本派生是主流程。
- 条件命中后的动作输出在同一屏可见。

### 3.9 DMN 决策表

DMN 表展示输入到输出的映射:

输入列:

- priority
- customerTier
- amount

输出列:

- route
- assignee
- actions

按钮:

- 分析。
- 导出 DMN。

意图:

- 多条件、多输出逻辑进入决策表。
- 简单条件留在条件构造器。
- 决策表输出可供 BPM/SLA/Automation 动作消费。

移动端:

- DMN 表在卡片内横向滚动。
- 页面本身不横向滚动。

### 3.10 条件片段库

右侧条件片段库展示可复用片段:

- 高价值紧急客诉 v3。
- 高金额审批升级 v5。
- 同组织负责人可见 v2。

每个片段展示:

- 名称。
- 版本。
- 消费者数量。
- 关键字段或字典。

点击片段:

- 高亮选中片段。
- 条件构造器标题更新。
- 检查器更新。

意图:

- 共享条件片段是资产库。
- 不同场景可以复用同一片段。
- 用户能看到片段版本和复用范围。

### 3.11 检查器

检查器展示当前选中对象。

选中字段时展示:

- 类型 FIELD。
- 名称。
- 编号。
- 路径。
- 范围或字典。

选中片段时展示:

- 类型 FRAGMENT。
- 名称。
- 编号。
- 路径。
- 使用范围。

意图:

- 不弹大说明。
- 所有上下文信息就地展示。

### 3.12 发布检查

发布检查展示:

- 字段目录 ok。
- 片段版本 v5。
- DMN 分析 0 gaps。
- 影响面 7 refs。

意图:

- 发布前风险直观可见。
- ready 不代表无影响,而是代表影响面已被识别并可确认。

### 3.13 影响面表

影响面表展示:

- 资产。
- 引用。
- 消费者。
- 版本策略。
- 状态。

示例:

| 资产 | 引用 | 消费者 | 版本策略 | 状态 |
| --- | --- | --- | --- | --- |
| FRAG_HIGH_AMOUNT_APPROVAL | BPM_APPROVER_ROUTE | BPM | fixed v5 | ok |
| record.data.amount | DMN_APPROVER_TABLE | BPM, Automation | latest metadata | ok |
| customer_tier | FRAG_URGENT_HIGH_VALUE_TICKET | SLA, Permission | dict code | new value |

意图:

- 字段、字典、片段、DMN 都进入同一张影响面视图。
- 发布前用户能判断变更影响。

## 4. 当前 mockup 的交互状态

已实现的 mockup 交互:

- 点击 SLA/BPM/Automation/Permission tab,切换策略上下文。
- 点击条件片段卡片,切换当前片段。
- 点击字段行,检查器切换为字段详情。
- 移动端 DMN 表格卡片内横向滚动。

当前 mockup 是静态 HTML + 少量本地状态,不是接入后端的真实实现。

## 5. 当前 mockup 的验证结果

浏览器验证覆盖:

- 桌面 1440 宽度截图。
- 移动 390 宽度截图。
- 场景切换。
- 条件片段切换。
- 字段选择。
- 检查器更新。
- 控制台错误检查。
- 页面横向溢出检查。
- 页面说明段落检查。

验证结果:

- 桌面无页面横向溢出。
- 移动无页面横向溢出。
- 移动 DMN 表为卡片内横向滚动。
- 控制台无 error/warning。
- 页面说明段落数量为 0。
- 场景、片段、字段交互均可更新页面状态。

## 6. 与上一版的主要差异

上一版问题:

- 像方案说明页。
- 顶部有长段解释。
- 页面通过文字解释“元模型”和“共享规则”,而不是通过真实控件展示。
- 条件片段和规则配置虽然存在,但信息层级仍偏讲解。

当前版变化:

- 删除说明段落。
- 删除 hero 式开头。
- 把规则配置提升到主工作区。
- 把条件构造器做成实际表单控件。
- 把动作输出做成右侧节点链。
- 把条件片段库、检查器、发布检查放到右侧产品工作区。
- 把影响面做成真实表格。
- 保留元模型字段、虚拟模型、字典、DMN、动作、发布检查等核心能力,但不再用大段文字解释。

## 7. 这份 mockup 后续实现时的硬性要求

实现时必须满足:

1. 页面不能退回说明型设计。
2. 规则配置必须是主路径。
3. 条件片段必须是独立资产库。
4. 字段选择必须来自 Fact Catalog。
5. 条件必须保存为 Condition AST。
6. 片段必须支持版本。
7. DMN 输入列必须来自事实字段。
8. 动作必须来自统一 Action Schema。
9. 发布必须走影响面检查。
10. SLA/BPM/Automation/Permission 必须真实消费规则输出。
