---
type: backlog
status: shipped
created: 2026-06-18
---

# 多元素顺序会签(sequential multi-instance)分析与实现

<!-- no-precipitation: 本文是一次性 niche 引擎特性(SmartEngine 多元素顺序会签)的根因分析 + 修复方案 + 落地记录;durable 价值即本文档本身,无可上升 canonical 的复发型规则。 -->

> 状态:**已实现并验证(2026-06-18,SmartEngine 4.0.2)**。owner 改变决定从"保持现状"→"完成全部 gap";已按 §6 方案在引擎实现候选缓存 + 全量 nrOfInstances,SEQ-01 解禁并真栈通过。
> **实现摘要**:`UserTaskBehavior.enter` 顺序分支缓存全候选列表(String 变量,scope=activityInstanceId);`handleMultiInstance` 顺序时 `nrOfInstances` 取缓存全量;`queryTaskAssigneeCandidateInstance` 从缓存读(回退 dispatcher)。非持久化变量存储(storage-custom)自动回退旧行为,storage-custom 回归 56/0/0;auraboot 真栈 `BpmMultiInstanceSequentialTest` SEQ-01/02/03 **3/3 通过 @ auraboot_50 4.0.2**。
> 关联:`docs/backlog/2026-06-17-bpmn-designer-golden-gap.md` G-T6;SmartEngine fork = `AuraBootTeam/SmartEngine` **v4.0.2**(branch `feat/bpm-seq-mi-and-message`,commit `3e2d102`)。

## 1. 业务能力定义

**顺序会签**:一个 userTask 由多个审批人**按顺序、一个接一个**审批(u1 批完才轮 u2,u2 批完才轮 u3,全部完成流程才往下)。区别于**并发会签**(N 人同时收任务、凑齐条件即过)。

## 2. SEQ-01 测什么 / 当前实际行为

`BpmMultiInstanceSequentialTest.SEQ-01`:3 审批人 `[u1,u2,u3]`,断言 1 活动任务→完成→新 1 个→完成→新 1 个→完成→流程结束(逐个推进)。

**实测(真栈 SmartEngine 4.0.1,2026-06-17)**:完成第 1 个任务后 **0 个新任务 spawn** → 会签活动在第 1 个就结束,未轮到 u2/u3。
- SEQ-02(单元素)✅ 通过;SEQ-03(空集)✅ 通过(原被错误 @Disabled,已解禁)。

## 3. 引擎 MI 模型(取证)

引擎运行模型 `MultiInstanceLoopCharacteristics` 只有 `sequential` / `completionCondition` / `abortCondition` —— **无标准 BPMN loopCardinality / collection 运行时支持**。会签实例数 = `TaskAssigneeCandidateInstance`(受理候选人)数,由 auraboot `IdAndGroupTaskAssigneeDispatcher` 把 `smart:miCollection="${approverList}"` 解析得到(顺序时赋递增优先级 1/2/3;`UserTaskBehaviorHelper.findBatchOfHighestPriorityTaskAssigneeList` 按最高优先级分批)。

## 4. 多元素顺序不迭代的两个根因(叠加)

**根因 1 — 计数取"已创建数"**:`UserTaskBehavior.handleMultiInstance` 算 `nrOfInstances = totalExecutionInstanceList.size()` = 当前**已创建**的 EI 数。顺序时一次只建 1 个 → 完成 task#1 后 `nrOfCompletedInstances=1, nrOfInstances=1` → 完成条件 `nrOfCompletedInstances == nrOfInstances`(1==1)为真 → 引擎判定"全完成"并结束;它不知道后面还有 2 人。

**根因 2 — 候选人在完成时取不到**:创建"下一个"顺序任务时,`compensateExecutionAndTask` 重新调 dispatcher 读 `context.getRequest()` 里的 `approverList`。流程**启动**时 request 有 `approverList`(并发因此 enter 一次建全 N 个);任务**完成**时 request 只有完成命令参数(无 approverList)→ 重解析得 **0 候选** → 即使计数对也无下一候选可建。

**为什么并发/单/空 OK**:并发 enter 一次解析全部 → N 个 EI 全建好 → `nrOfInstances=N`,完成条件 N==N 仅在全完成后真;单=1 候选完成 1 个就对;空=跳过活动。

## 5. 搜索结论:引擎无顺序 MI 测试覆盖

全仓(源码+测试+编译 resources)`isSequential="true"` / `setSequential(true)` 出现次数 = **0**。14+ 个 MI 测试(`MultiInstanceTest` / `MultiInstanceCompatible*` / `VariableInstanceAndMultiInstanceTest` / `CompatibleActivitiAndCustomExtensionProcessTest` 等)**全部并发**(isSequential 默认 false),用 quorum 型完成条件。

→ **顺序会签是"代码路径存在但从未被测/未被证明跑通"的脚手架**(`UserTaskBehavior.isSequential` 分支 + `compensateExecutionAndTask` 存在,但无任何测试驱动 isSequential=true)。

## 6. 完整修复方案(待 owner 决策,= SmartEngine 4.0.2)

让顺序会签的全部候选人在 enter 时缓存/持久化,后续每轮复用(不依赖完成时已为空的 request):

1. **enter**(request 有 approverList):解析的全候选列表(id/类型/优先级)序列化持久化(经引擎变量服务 → `se_variable_instance`)。
2. **handleMultiInstance**:顺序时 `nrOfInstances` 绑全候选基数(从缓存读)→ 完成条件不再第 1 个就提前为真。
3. **compensateExecutionAndTask**:从缓存取下一候选(剔除已分派)创建下一任务 → 候选耗尽才结束。

涉及 `UserTaskBehavior.enter` + `handleMultiInstance` + `UserTaskBehaviorHelper.compensateExecutionAndTask` 三处 + 候选序列化/持久化 + 全回归(确保不破坏并发会签 + 引擎 534 测试套件)。

> 注:曾试单做"计数绑全量"无效——因根因 2(完成时候选取不到),两个必须一起改;已干净回退,引擎留 4.0.1。

## 7. 待 owner 确认

owner 记得"SE 支持顺序会签/回迁"。证据显示:**代码路径在,但无测试覆盖、多元素实测不迭代**。两种可能:
- (a) 记得的是**代码存在**(未测/未跑通)→ 上述 4.0.2 方案是完整修复路径;
- (b) 记得有**具体能跑的用法/测试**(我未搜到)→ 请指测试类名 / BPMN / 配置模式,照其复现验证。

**ROI 考量**:多元素顺序会签相对 niche(并发会签、或"一节点一审批人串多节点"更常见);引擎改动有持久化设计 + 回归面。是否现在做由 owner 定。

## 决策(已回填 2026-06-18)

**owner 决定:保持现状(不现在做)。** 多元素顺序会签相对 niche,引擎修复有持久化设计 + 534 测试回归面;暂不投入。本分析(双根因 + 4.0.2 完整候选缓存方案)作为长存记录保留,日后真有流程需要"多审批人按顺序一个接一个"时按 §6 方案实现 + 解禁 SEQ-01 真栈验证。SEQ-01 维持 `@Disabled`(诊断准确),SEQ-02/03 通过。

- [x] owner review:决定 **保持现状**,暂不实现 4.0.2(niche / ROI)
- [ ] (deferred)若日后决定做:实现 SmartEngine 4.0.2 候选缓存方案(§6)+ 解禁 SEQ-01 真栈验证
- [ ] (deferred)若 owner 后续想起具体能跑的用法/测试(路径 b):按其指的模式复现
