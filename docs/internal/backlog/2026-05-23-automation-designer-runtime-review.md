# Automation 设计器 + 后端链路执行 — 评审结论 / 目标架构 / 实现路线

> 日期:2026-05-23 · 分支:`review/automation-designer-runtime` · 状态:已实现并收口(详见 §5 收口状态,2026-06-04)
> 评审方式:静态为主(主对话 + 3 个只读 reviewer subagent 交叉印证,全程 verify-before-flag)+ 待补 P0 runtime 实测
> 红线纪律:每条 P0/P1 均经 ls/grep/read 实地取证;一条 i18n 误判已由 subagent 自检撤回(嵌套 yaml key 实际存在)

---

## 0. 一句话结论

可视化 automation 设计器画得很完整,但**后端用的是一个伪装成图引擎的扁平顺序执行器**:设计器存的 `flowConfig` 永不被执行、条件/循环控制节点在运行时形同虚设。叠加一条**跨租户 IDOR** 安全漏洞、设计器**无字段级校验**、执行链路**零测试覆盖**。根因是产品意图(图自动化)与执行实现(扁平 actions)从一开始就错位。

目标架构已与 owner 讨论锁定:**automation 定位为"全自动图编排"(n8n / Salesforce Flow 档),编译到已有的 SmartEngine 引擎执行**,人工任务归 BPM。

---

## 1. 验证发现(findings)

### P0(合并阻塞)

**P0-1 设计器存的 `flowConfig` 永不被执行**(契约断裂)
- 前端 `web-admin/app/framework/smart/automation/components/AutomationEditPageImpl.tsx:93-117` 保存只发 `{name, description, flowConfig}`,不发 `triggerType`/`actions`/`triggerConfig`。
- 后端 `platform/.../automation/service/impl/AutomationServiceImpl.java:60-83` 直接 `setTriggerType(request.getTriggerType())`(设计器路径下为 null),**不从 flowConfig 反推**;`validateCreateRequest:399-409` 显式接受 "flowConfig-only、triggerType/actions 可空"。
- 全仓**无任何 flowConfig→actions/流程的编译器**(grep 仅命中无关的 `CommandActionDeriver`)。
- 运行时 `AutomationTriggerServiceImpl`:事件分发按 `findEnabledByModelCodeAndTriggerType(modelCode, triggerType)` 匹配(trigger_type=null → 永不命中);`executeAutomation:298` 只遍历 `getActions()`(空)。
- **后果**:纯经设计器从零创建的自动化 = 一张存下来、但触发时执行 0 个动作、静默 no-op 的图。
- 交叉印证:WS-A(主对话亲查)+ WS-E(测试 agent 独立确认 flowConfig→执行零翻译)。

**P0-2 执行器线性按 `sequence` 跑,无视图拓扑;condition 不 gate、loop 是 no-op**
- `AutomationTriggerServiceImpl.executeAutomation:314-337` 顺序遍历 `actions`,**全程不读 edges**。
- `executor/impl/ControlNodeExecutor.java:49-71` CONDITION 返回 `{branch:"true"/"false"}`,但执行循环**从不据此跳过下游**——条件为 false 时下游 action 照跑。
- LOOP(`ControlNodeExecutor` 注释自承"交给 orchestrator")而 orchestrator 无 loop 处理 → no-op。
- DELAY `Thread.sleep` ≤5min(`:81`)与整体 `EXECUTION_TIMEOUT`(60s,`:312-321`)冲突,长 delay 必被截断,且阻塞 `@Async` 线程(叠加单规则信号量 10 → 线程池耗尽风险);`InterruptedException` 未恢复中断标志(`:89`)。
- 交叉印证:WS-B(主对话亲查)+ WS-E(测试 agent 确认无任何测试断言 condition=false 跳过下游)。

**P0-3 跨租户 IDOR(安全,独立于架构方向,必须修)**
- `platform/.../application/database/mybatis/MybatisPlusConfig.java:146` 把 `ab_automation` 放入 `TenantLineInnerInterceptor` 白名单(理由"scheduler 跨租户扫描")→ 该表查询**不自动注入 tenant_id**。
- `AutomationMapper.findByPid:22` `SELECT * FROM ab_automation WHERE pid=#{pid} AND deleted_flag=false`、`AutomationServiceImpl.findByPid:86-95`(LambdaQueryWrapper)均**无 tenant 条件**。
- 所有 controller 写操作(`getByPid`/`update`/`delete`/`enable`/`disable`/`toggle`/`duplicate`/`triggerManually`、`DebugController.createSession`)走 `findByPid` 后直接操作,**无 `tenantId` 归属校验**。
- 反证:同仓 `CapabilityViewService:319/791/...` 查 `ab_automation` 时显式 `WHERE tenant_id=#{params.tenantId}` → 证明白名单契约要求"查询层手动补 tenant",CRUD 路径破坏了该契约。
- **后果**:持 `automation.read/update/admin` 的任意租户用户,凭(枚举)pid 即可读取/篡改/删除/触发**他租户**的自动化(含 secret、action 配置)。经典水平越权(BOLA/IDOR)。
- 主对话已亲自复核(grep ignoreTable + findByPid 取证)。

**P0-4 required 字段空提交可静默保存(设计器无字段级校验)**
- `AutomationEditor.tsx:71-87 handleSave` 只调 `onSave(data)`,无校验;`flow-designer-sdk/core/FlowDesigner.tsx:84-89` 同样无校验门。
- 校验基础设施存在但**从未接线**:`setValidationResult` 仅在 README/test 出现,生产组件零调用;`onValidate` 未被 `AutomationEditor` 传入;`FlowFieldAdapter.error` 永远 undefined。
- **后果**:把 required(如 modelCode/recordId/expression)留空可直接保存,无字段错误态。违反红线 2.2「required 空提交可保存=blocker」。

**P0-5 执行链路零测试覆盖(测试盲区本身)**
- 全树**无任何真实 Spring-context 集成测试**调用过 `executeAutomation`/`onRecordCreate`(grep 确认 0)。`executeAutomation` 的 3 个测试(`AutomationTriggerServiceImplTest:325-375`)全 mock executor、无 DB、未注入 control node。
- 唯一摸"执行结果"的 E2E(`tests/e2e/automation/llm-call-node.spec.ts:305/656/699`)用 `route.fulfill` 把 `/trigger` 整个 mock,断言的是测试自注入的假 `actionResults`。
- `automation-deep.spec.ts:219` 含 `expect(true).toBe(true)` no-op 兜底;AT-006/007/008 "action type" 仅断言名字出现在列表;AT-009 断言日志为空且注释自认"hasn't been triggered"。
- **后果**:这正是 P0-1/P0-2 长期没被拦住的原因——测试与产品缺陷在同一处重合(只验"能画/能存"不验"能跑")。

**P0-6 模板库零 i18n + raw category code 泄漏**
- `components/TemplateGallery.tsx` 无 i18n import,`Automation Templates`(L87)/`Search templates...`(L130)/`Use Template`(L218) 等硬编码英文;`{template.category}`(L180) 直接渲染 raw code。`TemplatePreviewDialog.tsx` 同样。
- 加重:`i18n.en-US.yaml:527` 已有 `automation.template.*` 块,组件绕过未用。违反红线 #3。

### P1

- **P1-1 Webhook HMAC 基于 `Map.toString()` + 非常量时间比较**:`AutomationWebhookController.java:67` 对 `payload.toString()`(HashMap 文本,顺序不稳)算 HMAC 而非原始 body → 签名功能性损坏;`:99 equalsIgnoreCase` / `:71 .equals` 非常量时间。(端点仍需 JWT,故 P1)
- **P1-2 ControlNodeExecutor 的 SpEL 无长度上限/黑名单**:`ControlNodeExecutor.java:57-62` 是第二个 SpEL 求值点,未套用 `evaluateCondition` 的 500 上限 + 危险模式黑名单。**非 RCE**(仍用 `SimpleEvaluationContext.forReadOnlyDataBinding()`,Spring 6 下禁 `T()`/`new`/方法调用,RCE 边界有效),缺的是 ReDoS/纵深一致性。
- **P1-3 resource-select / log detail 吞错无错误态**:`resourceSelectService.ts` 6 个 fetch `catch { return [] }`(model/field/command/process 下拉 API 失败静默显示空);`ExecutionLogDialog.tsx:90 catch {}` 静默失败。违反红线 #8/#10。
- **P1-4 debug 4 组件硬编码英文**:`debug/components/` 下 AutomationDebugger/DebugLogPanel/DebugToolbar/DebugVariablePanel 无 i18n。
- **P1-5 编排测试深度不足**:`executeAutomation` 测试全 mock executor、无 DB,无法暴露 P0-2/P0-3;E2E "action type" 测试名误导实为存在性 smoke。

### P2

- **P2-1 i18n 降级不一致(鲁棒性隐患)**:`FlowPalette.tsx:73 st(label)||type`(miss→泄漏 raw type)vs `FlowPropertyPanel.tsx:69 st(...)`(miss→空 label)。当前 key 齐全不泄漏,但新增节点漏 key 时无 lint 拦截。
- **P2-2 共享渲染路径 dict-select 硬编码中文**:`PropertyFieldRenderer.tsx:526/537`(automation 未直接用,但同文件)。
- **P2-3 ExecutionLogDialog 局部硬编码 + raw code**:`Loading...`(L134)、statusConfig 标签、`action.actionType`(L56)/`log.triggerType`(L120) 直显 raw code。
- **P2-4 双 base-path**:debug 用 `/api/automation`(单数,DebugController),其余 `/api/automations`(复数),易混淆建议归一。
- **缺失能力 `trigger-bpm-event` 前端节点**:后端 `onBpmEvent` + `BpmEventAutomationBridge` 完整,但设计器无该触发节点,BPM→automation 只能改库/调 API 配置。

### 已验证为「非问题」(防误报澄清)

- SpEL **无 RCE**:`SimpleEvaluationContext.forReadOnlyDataBinding()` 在 spring-expression 6.x 下禁类型引用/构造器/任意方法,黑名单只是冗余第一层(test 已验 `T()`/`new`/`getClass` 均被拒)。
- `CallApiExecutor` **SSRF 已防护**:`:62 SsrfValidator.validate(url)`(拒 loopback/link-local/IPv4-mapped-IPv6/DNS rebinding,先插值后校验顺序正确)。
- 权限注解**全经 `MetaPermission` 常量**,无裸字符串,READ/MANAGE/ADMIN 边界合理(符合红线 #13);admin stream controller 由 `/api/admin/**` URL 前缀守卫。
- `ab_automation_log` 读路径**租户安全**(不在 ignoreTable,interceptor 自动注入 tenant)。
- 调试器**是真后端**(REST + 真 SSE,与 `DebugController` 9 端点逐一对齐),非 mock;"Test Run" 真打 `/trigger`。
- PropertyType **无"配不了"缺口**:节点用到的 12 种 type 在面板 switch 均有渲染分支。

### 存疑(待后续复核)

- `executeAutomation:276` 的 `MetaContext.exists()?...:automation.getTenantId()` 在 `@Async` 线程下是否有 ThreadLocal 残留串号风险——需查 `eventTaskExecutor` 的 TaskDecorator / MetaContext 清理(在 automation 模块外)。
- `AutomationSchedulerTest` 断言深度未读(是否真测调度→执行)。
- 企业版仓库是否有补充 automation 执行测试,本轮范围未含。

---

## 2. 目标架构决策(DDR,已与 owner 讨论锁定)

### 2.1 产品定位
- **定位 B:全自动图编排**(分支/循环/持久延时/error 分支,n8n / Salesforce Flow 档),非 Zapier 线性档。
- **边界**:人工任务 / 审批 = BPM;automation 只管自动步骤,不暴露人工节点。
- **方向**:统一 —— 两个授权入口(轻 automation / 全 BPMN),**同一 SmartEngine 底座**。业界佐证:Salesforce 砍 Workflow Rules + Process Builder 收敛到 Flow;ServiceNow 用 Flow Designer 统一。

### 2.2 目标:automation `flowConfig` → 编译为 SmartEngine 流程定义

| 设计器节点 | SmartEngine 映射 | 备注 |
|-----------|-----------------|------|
| trigger(record/scheduled/webhook/bpm-event) | Start Event + 事件订阅 | **memory 模式**承接高频 record 触发,免 DB 实例开销 |
| action(update/create/notify/call-api/llm/execute_command/send-webhook/start-process) | **Service Task delegate** | 复用 `CommandServiceTaskDelegate`/`HttpServiceTaskDelegate` 先例;`execute_command` 已正确走 `CommandExecutor` 完整 pipeline,保留 |
| condition | **Exclusive Gateway** | 替掉"返回 branch 无人消费"死逻辑 |
| loop | **Multi-Instance / Loop** | 替掉 no-op |
| delay | ⏳ **Timer** | SmartEngine timer 尚不完整 → **挂起,后续讨论完善**;现状 `Thread.sleep` 玩具级 |
| flowConfig | → 编译器 → SmartEngine 流程定义 | 参考已有 `BpmnToJsonConverter`/`JsonToBpmnConverter` |

### 2.3 决策要点
- **不补扁平编译器**:P0-1/P0-2 的正确修法是 flowConfig→SmartEngine 流程,补扁平 actions 编译器仍撞扁平执行器的墙。
- **无迁移 / 干净替换**:全仓零 shipped/seed 自动化(`default-bootstrap.json` 仅权限码、`sales-templates` 仅描述提及、`schema.sql` 仅 DDL),dev 阶段无生产数据 → 一次干净替换,**禁兼容垫片 / forwarding stub**(dev 阶段红线)。唯一要动的是用 `actions[]` 建自动化的测试 fixture/E2E。
- **memory 模式**承接高频触发,回应"每条 record 起流程实例"的开销顾虑。
- **timer 挂起**:delay 节点待 SmartEngine timer 完善后单独讨论。

---

## 3. 实现路线(roadmap 草案,待拍板;干净替换,非迁移)

1. **flowConfig → SmartEngine 编译器**(P0):trigger=start / action=service task / condition=gateway / loop=multi-instance。参考 `BpmnToJsonConverter`。
2. **ActionExecutor → Service Task delegate 适配层**:现有 8 类 executor 挂成 delegate,保留 `execute_command` 走完整 CommandExecutor pipeline。
3. **干净替换扁平执行器**:删 `ControlNodeExecutor` no-op 分支/循环 + `executeAutomation` 线性 loop,不留垫片。
4. **修 P0-3 IDOR**(独立于架构,优先):`findByPid` 等读路径显式补 `tenant_id`,或 controller 写操作前断言归属;scheduler 跨租户扫描走独立显式 cross-tenant mapper。
5. **设计器字段级校验**(P0-4):接线 validation engine,required 空提交拦截 + 字段错误态。
6. **补执行链路测试**(P0-5):真 Spring-context IT 覆盖 create→trigger→断言动作发生 + condition gating + flowConfig 执行;E2E 去 mock 验真实执行。
7. **i18n 补齐**(P0-6/P1-4):TemplateGallery/debug 组件接 i18n,category 走翻译。
8. **delay/timer 挂起项**:待 SmartEngine timer 完善后单独讨论。
9. **双授权入口 UX**:automation 轻面藏 BPMN 术语 + 补 `trigger-bpm-event` 节点。
10. **运行历史/回放统一**:automation log ↔ SmartEngine 实例。
11. **次要清理**:Webhook HMAC(P1-1)、resource-select 错误态(P1-3)、双 base-path 归一(P2-4)。

---

## 4. runtime 实测状态

- P0-1/P0-2/P0-3 已三方静态交叉印证 + 主对话亲查(grep/read 取证)。
- **P0-1 / P0-2 已落 runtime 特征测试**(真实 `executeAutomation` orchestrator,仅 mock 外部动作执行,免 DB),`platform/src/test/.../automation/trigger/impl/AutomationTriggerServiceImplTest.java`:
  - `executeAutomation_flowConfigOnly_executesNothing_P0BUG` — flowConfig 齐全但 actions 空 → `actionExecutor` **从未被调用**,零动作执行。**PASSED**(证明 flowConfig 被执行器忽略)。
  - `executeAutomation_conditionFalse_doesNotGateDownstream_P0BUG` — condition 返回 false branch → 下游 update_record **仍被执行**。**PASSED**(证明 orchestrator 不消费 branch、不 gate)。
  - 这两条今天 PASS 是因为断言的是当前 broken 行为(特征测试);P0 修复后按方法注释**反转断言**即转为正向回归测试,直接补上 P0-5 的执行链路覆盖缺口。
  - 运行命令:`platform/gradlew -p platform :test --tests "com.auraboot.framework.automation.trigger.impl.AutomationTriggerServiceImplTest"`(根项目 test task;子模块 storage/mq 无此测试)。
- 采用轻量 IT 而非 isolated docker 全栈 UI 复现:静态证据已三方交叉印证,编排类 P0 用真实 orchestrator 单测即可作 runtime 硬证,且免 ≥2 worktree 的共享 DB 冲突(红线 #11)。

---

## 5. 收口状态(2026-06-04)

### 5.1 本会话完成(4 PR,全部 merged 到 OSS `main`)

| 项 | PR | 说明 |
|----|----|----|
| **新发现 P0 — 设计器创建的 automation 无法保存 / 永不触发** | **#417** | 全仓**从无** flowConfig→trigger 字段派生:设计器 `handleSave` 只 POST `{name, description, flowConfig}`,`create()` 从 request 取 `triggerType/modelCode`(=null),而 `ab_automation.model_code`/`trigger_type` 是 NOT NULL → 设计器保存直接 NOT NULL 违规失败(即便列可空,事件分发 `findEnabledByModelCodeAndTriggerType` 也永不命中)。原评审聚焦「flowConfig 不被执行」(P0-1),漏了更前置的「设计器根本存不下」——因为原评审是静态 + runtime 特征测试走的是 flat-field payload。修复:新增 `AutomationFlowTriggerDeriver`(读 trigger 节点 `data.config` → 派生 `triggerType/modelCode/triggerConfig`,接入 `create()`/`update()`)。真栈 PG IT 又暴露第二处:`actions` 列 NOT NULL,设计器 payload 不带 actions → 同样保存失败;补 `create()` actions 缺省空 `[]`。 |
| **缺失能力 `trigger-bpm-event` 前端节点**(roadmap #9) | **#417** | `triggers.ts` 新增节点(process-select 存为 `modelCode`==BPM processKey + eventTypes multiselect)+ 4 locale i18n;后端 `onBpmEvent`/`BpmEventAutomationBridge` 此前已完整。 |
| **P1-1 Webhook HMAC** | **#415** | HMAC 改为对原始 body 字节计算(原 `payload.toString()` 的 HashMap 顺序不稳=验签功能损坏)+ `MessageDigest.isEqual` 常量时间比较(含 token 模式)。 |
| **P1-3 resource-select 吞错** | **#416** | 9 个 `catch { return [] }` 改为传播错误;`BaseResourceSelect`/`DependentMultiSelect` 渲染本地化错误态 + Retry;`ExecutionLogDialog` 暴露 detail 加载错误。 |
| **(派生场景)stale cutover 测试** | **#414** | `AutomationTriggerServiceImplTest` 2 个 cutover 测试在 main 已红(#318 G5 给 `run()` 加第 4 参 logId 后未更新 `verify` 参数数);修复恢复覆盖。 |

验证:派生器单测 8/8、bpm-event 节点 vitest 10/10、resource-select 63/63、webhook 8/8、cutover 27/27;**真 PostgreSQL IT `AutomationFlowConfigDerivationIntegrationTest` 2/2**(设计器式 create 成功 + 派生列持久化 + `getByModelCode` 可查),现有 `AutomationServiceIntegrationTest` 17/17 无回归。

### 5.2 此前会话已完成(T2 核心,本文档原 §2/§3)

P0-1/P0-2(flowConfig→SmartEngine 编译器 + 桥接 delegate + 条件网关 + loop delegate for-each + 行为 cutover):#263/#267/#268。P0-3 IDOR:#264。P0-4 校验门:#269。P0-5 执行链测试:#298。P0-6 模板 i18n:#298。Roadmap #10 运行历史/nodeStatus:#318(G5)。

### 5.3 延后(owner 决策)

- **P2-4 base-path 归一**(`/api/automation` 单数 vs `/api/automations` 复数):DEFERRED。纯美观一致性,但改 `DebugController` 路由会动 debugger 9 端点、需真栈 E2E 验证,边际价值 < 风险(红线 #19)。dev 阶段可 breaking(禁 forwarding stub),归一到复数后须 debugger E2E 验 9 端点可达。
- **delay/timer**(roadmap #8):仍挂起,等 SmartEngine timer 完善;现状 `Thread.sleep` 玩具级。

### 5.4 仍开放(原评审 P1/P2,未在本轮处理)

- **P1-2** `ControlNodeExecutor` 第二处 SpEL 求值点缺长度上限/黑名单(非 RCE,缺 ReDoS 纵深一致性)。
- **P1-4** debug 4 组件(AutomationDebugger/DebugLogPanel/DebugToolbar/DebugVariablePanel)硬编码英文。
- **P2-1** i18n 降级不一致(`FlowPalette` miss→泄漏 raw type vs `FlowPropertyPanel` miss→空);新增节点漏 key 无 lint 拦截。
- **P2-2** 共享 `PropertyFieldRenderer` dict-select 硬编码中文。
- **P2-3** `ExecutionLogDialog` 局部硬编码 + raw code(`Loading...`/statusConfig 标签/`actionType`/`triggerType` 直显)——#416 仅补了错误态,i18n 化未做。
- **双授权入口 UX / T4 BPMN→flow-designer-sdk 迁移**(roadmap #9 的 UX 收敛部分):独立工作线,进行中(见 `DDR-2026-05-23-automation-bpm-designer-convergence.md`)。
