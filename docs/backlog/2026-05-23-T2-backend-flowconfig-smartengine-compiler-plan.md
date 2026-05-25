# T2 — 后端 flowConfig→SmartEngine 编译器 plan

> 日期:2026-05-23 · 状态:**plan,核心决策待 owner 确认后执行** · 依据:`2026-05-23-automation-designer-runtime-review.md`(P0-1/P0-2)、`2026-05-23-unified-graph-grammar-spec.md`、`DDR-2026-05-23-automation-bpm-designer-convergence.md`
> 目标:修 P0-1(flowConfig 永不执行)+ P0-2(扁平执行器无视拓扑),让 automation 真正按图在 SmartEngine 上跑。**独立于前端 track,可并行。**

## Context

契约测绘(只读)已确认可复用的后端件:
- `JsonToBpmnConverter.convertFromJsonNode(root{key,name,nodes,edges,aura})` → BPMN XML(支持 startEvent/serviceTask/exclusiveGateway/callActivity;serviceTask 通用路径可用 `data.config.className` 直接指定 delegate bean)。
- `ProcessDeploymentService.create(CreateProcessRequest{processKey,processName,designerJson})` → `deploy(pid)`(内部 `smartEngine.getRepositoryCommandService().deploy(xml, tenantId)`)。
- `ProcessEngineService.startProcess(processKey, businessKey, variables)`(定义须先 deploy 进 repository,MEMORY 模式也是)。
- MEMORY 模式:照抄 `ProcessOrchestrationService.executeWithStorageMode`(`StorageModeHolder.set(MEMORY)` + `PersisterSession.create/destroy`),只切实例存储、不落库。
- delegate 配置流:静态配置走 `executionContext.getBaseElement().getProperties()`(smart:* 属性);运行值走 `executionContext.getRequest()`(= startProcess 的 variables)。
- splice 点:`AutomationTriggerServiceImpl.executeAutomation`(行 270)——入口分流。

## 关键架构决策(★需 owner 确认)

**现有 4 个 BPM delegate 与 automation action 语义有真差距**(测绘 G2):notification 限 applicant/assignee well-known 变量、无 channel;update 仅单字段字面值;http 无 body;execute_command 走 `_chain_nodes` 不兼容 smart:*。直接复用会**丢 automation action 能力**。

### 选定方案(推荐):桥接 delegate,复用 ActionExecutor
**SmartEngine 只负责图编排(顺序/网关/循环/等待);动作活儿仍交给已有且已测的 `CompositeActionExecutor`。** 新增一个薄桥接:
- `AutomationActionServiceTaskDelegate`(新,实现 `JavaDelegation`):从 process variable `_automation_actions: Map<nodeId, {type, config}>`(仿 CommandServiceTaskDelegate 的 `_chain_nodes`)按 activityId 取本节点 action 配置 → 组 `AutomationAction` → 调 `compositeActionExecutor.execute(action, ctx)`(ctx 来自 process variables)→ 结果写回 process variable。
- 编译器对每个 action 节点 emit 通用 `serviceTask` + `data.config.className="automationActionServiceTaskDelegate"`(converter 原生支持直接写 className,**converter 零改**)。
- 收益:**converter 零改 + 全部 ActionExecutor 零语义差距复用**(notification 多收件人/channel、update 多字段+`${var}`、call-api body、llm、execute_command 全保留)。

### 备选(不推荐):直接映射到现有 BPM delegate
emit notification-task/record-update-task 等。优点:无新 delegate。缺点:**受限于 BPM delegate 的窄语义**(见上 4 处差距),automation action 能力缩水;execute_command 无法纯 smart:* 表达。→ 否决。

> 这条决策决定整个编译器形状,故标 ★ 待确认。

## 编译器设计(AutomationFlowCompiler,新)

`flowConfig(GraphDocument)` → `designerJson`:
| automation 节点 | emit |
|---|---|
| trigger-* | `startEvent`(trigger 元信息留 automation 实体/meta,不进 BPMN) |
| action-*(全部) | 通用 `serviceTask` + `config.className="automationActionServiceTaskDelegate"`,id=节点 id |
| control-condition | `exclusiveGateway`;**每条出边 emit `edge.data.condition`(SpEL→`{type:expression,content}`)**(converter 硬约束:default 边也要 condition) |
| control-loop | multi-instance(后续切片) |
| control-delay | ⏳ timer(挂起) |
| 终点 | `endEvent` |

- **部署时机**:automation 保存/启用时编译+部署(processKey=`auto_<automationPid>`);或首次触发懒部署。→ 决策见开放点。
- action 配置不进 BPMN 属性,统一在 startProcess 时以 `_automation_actions` 变量传入(避免改 converter 写任意 smart:*)。

## 触发接线(splice)

`executeAutomation` 入口分流:
- `automation.getFlowConfig()` 有内容 → 走新路径:确保已部署 → 构造 variables(`_automation_actions` + 触发上下文:`recordId`、record 字段、tenantId、触发者→收件人语义所需 key)→ `executeWithStorageMode(MEMORY, () -> startProcess(processKey, recordId, variables))`。
- 否则 → 旧 actions 循环(过渡期保留;cutover 后删,见下)。
- 5 个事件入口(onRecordCreate/Update/FieldChange/StateChange/BpmEvent)+ scheduler 经此汇聚,无需逐个改。

## 阶段

1. **首切片(垂直打通)**:trigger-record-create → 单 action-send-notification → endEvent。新 `AutomationActionServiceTaskDelegate` + `AutomationFlowCompiler`(仅 start/serviceTask/end)+ deploy + MEMORY startProcess + splice。**end-to-end IT:建记录→触发→断言通知真发出 + log success**(对应反转 P0-1 特征测试)。
2. **条件分支**:control-condition→exclusiveGateway + 出边 condition。IT:condition=false 下游被跳过(反转 P0-2 特征测试)。
3. **循环**:control-loop→multi-instance。
4. **Cutover(干净替换)**:删 `executeAutomation` 扁平 actions 循环 + `ControlNodeExecutor` no-op 分支/循环,不留垫片(dev 阶段);测试 fixture 改为新模型。
5. **delay/timer**:挂起待 SmartEngine timer。

## 测试(红线:新 Service 必带 IT)
- `JsonToBpmnConverter` 形状契约单测:automation 节点 emit 的 serviceTask(className)/exclusiveGateway+条件出边 能正确转 BPMN。
- `AutomationFlowCompiler` 单测:flowConfig→designerJson 映射(各节点类型 + 网关出边 condition 必填校验)。
- `AutomationActionServiceTaskDelegate` 单测:从 `_automation_actions` 取配置 + 调 mock ActionExecutor。
- **end-to-end IT**(Spring ctx + MEMORY 模式):每切片一条真实触发→断言动作发生/分支生效。声明完成前跑 `/e2e-truth`。
- 因 ≥2 worktree:IT 走 isolated docker PG(deploy 需 repository;MEMORY 只免实例落库,仍需 Spring+PG 起栈)。

## 开放点(需 owner 拍)
1. ★ **桥接 delegate vs 复用 BPM delegate**(本 plan 推荐桥接)。
2. **部署时机**:save/enable 时 eager 编译部署 vs 首次触发懒部署。(倾向 enable 时 eager,触发路径更快、错误更早暴露)
3. **过渡期**:首切片阶段是否保留旧 actions 路径并行(倾向保留到 cutover 阶段,降风险),还是一步到位替换。
4. action 配置传递:`_automation_actions` process variable(本 plan 选)vs 扩展 converter 写任意 smart:*(改 converter,不推荐)。

## 不做
- 不改 `JsonToBpmnConverter`(靠 className + 变量传配置规避)。
- 不在 cutover 前删旧路径(降风险)。
- 不碰前端 track(T3/T4 并行)。
