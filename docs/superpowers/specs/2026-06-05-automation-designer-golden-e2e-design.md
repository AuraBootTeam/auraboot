# Automation 设计器端到端黄金 E2E — 设计

> 日期:2026-06-05 · 状态:设计已与 owner 讨论并批准,待转 writing-plans
> 范围:**automation 设计器先做全**(happy/sad/edge/corner + 全节点类型),沉淀拖拽 helper + 金标 harness;**bpmn 设计器为后续独立 slice**,复用同套 harness。

---

## 1. 背景与现状(实证)

两个设计器的重构与前后端联动状态(本设计前已 grep/读码/真栈 IT 实证):

- **Automation 设计器**(flow-designer-sdk):✅ 重构完成(T2 flowConfig→SmartEngine 编译/桥接/派生/运行全链路 merged;SDK 增强 G1/G2/G4/G5/G6/G7/G8 已落,G3 延后)。✅ 前后端端到端联动(UI 存 flowConfig → `AutomationFlowTriggerDeriver` 派生 triggerType/modelCode/triggerConfig → `enable` 编译部署 SmartEngine → 事件命中 → `executeAutomation`→`AutomationProcessRuntime.run`→delegate→action;真栈 IT `AutomationFlowConfigDerivationIntegrationTest` 已验设计器式创建可落库+可触发)。
- **BPMN 设计器**(bpmn-designer):✅ B2c 迁移完成(7/7 消费方迁至 `useBpmFlowStore`,`af3813efe`#347 在 origin/main;B2d 删旧 store 未做但旧 store 不被生产引用)。✅ 联动(designerJson → `/api/bpm/process-definitions` → `ProcessDeploymentService.deploy` → `JsonToBpmnConverter` → `smartEngine.getRepositoryCommandService().deploy()` 真接引擎非桩 → startProcess → userTask → 任务中心)。

**关键缺口(本设计针对)**:两个设计器各有一个 golden spec(`automation-golden.spec.ts` / `bpm/ui-full-stack-lifecycle.spec.ts`),但**都是 API 建图驱动,不是真浏览器拖拽设计器**。红线 §2.2 + dnd-conventions:**交互层(拖拽/连边/canvas)必须真实指针 E2E,API setup 不算 UI golden**。本设计补的就是**真用户从设计器 UI 拖出来 → 配置 → 保存 → 部署/启用 → 触发/启动 → 断言副作用+节点状态**这条链,并做到**全节点类型 + happy/sad/edge/corner 详尽覆盖**。

---

## 2. 目标与范围

- **目标**:automation 设计器的真用户旅程端到端黄金 E2E,详尽覆盖 happy / sad / edge / corner,且**全部 18 个节点类型**(7 触发 + 8 动作 + 3 控制)front+back 双验。
- **范围内**:automation 设计器(flow-designer-sdk / @xyflow 画布)。
- **范围外(后续 slice)**:bpmn 设计器黄金(复用本 slice 沉淀的 harness);`action-start-process` 启动的 BPM 流程的**下游**完整执行(本 slice 只断言「流程实例已启动」,流程内部执行归 bpmn slice)。
- **非目标 / 延后**:`control-delay` 运行时(SmartEngine timer 挂起,roadmap #8)→ 诚实 skip;`trigger-scheduled` cron 实时触发(重型)→ IT 级 / 诚实 skip。

---

## 3. §A 测试架构(分层金标)

- **Layer A — 真用户旅程 golden**(新 `automation-designer-golden.spec.ts`):真浏览器、真指针拖拽从零搭流程,覆盖 happy + **UI 层** sad/edge(required 拦截、错误态、重配、reload 重渲)。这是「真用户旅程」主体。
- **Layer B — 运行时行为矩阵**(扩 `automation-golden.spec.ts`):验**后端行为**的 sad/edge/corner(condition gating / action fail / loop / 各触发 fire / 各动作副作用 / 双 trigger 拒绝 / 租户隔离 / 并发);setup 用 API 建 flowConfig + **真触发真断言**(验的是后端行为不是拖拽机制)。
- **诚实边界**:Layer B 明确标注「behavioral matrix(非 UI-golden)」;skip 项(delay-runtime / scheduled-realtime)记录真实原因,**计 skip 不计 pass**(红线 §2 反假通过)。

**为什么分层**:真拖拽保真在该保真处(交互层一次彻底);行为矩阵不被 18+ 条慢拖拽拖垮(@xyflow 多步指针手势慢且易 flaky,且多数 case 验的是后端行为、拖拽部分纯重复)。两层都真实、不假通过,§2.2 合规(交互层有真指针覆盖)。

---

## 4. §B 用户旅程(happy 主线,真拖拽)

菜单 → Automations 列表 → 新建 → 拖 `trigger-record-create`(配 modelCode=`e2et_order`)→ 拖 `control-condition`(配 `amount > 1000`)→ 拖 `action-update-record`(true 分支)→ 连边(trigger→condition→action)+ 设 true/false 分支条件 → required 填齐 → **保存**(断言 `POST /api/automations` body 含 `flowConfig{nodes:3, edges}`)→ **离开路由再返回**(断言画布重渲 3 节点 2 边)→ **启用**(后端编译+部署 SmartEngine)→ **触发**(经 command 建一条 `amount=2000` 的 `e2et_order` record)→ 轮询 `/api/automation/executions/by-log/{logId}/node-statuses` 直到 endEvent completed → 断言 trigger=completed → condition=completed(走 true)→ action=completed、无 failed → **断言副作用**(该 record 目标字段被改)+ UI 节点状态徽章(G5 overlay)。

---

## 5. §C 行为矩阵(happy/sad/edge/corner)

| 类 | case | 层 | 断言要点 |
|---|---|---|---|
| happy | H1 全旅程(见 §B) | A 真拖拽 | 副作用 + 节点状态流转 |
| | H2 保存→reload 重渲 | A | 画布持久化往返 |
| | H3 节点状态徽章渲染 | A | G5 overlay |
| sad | S1 required 空提交 | A | Save 拦截 + 字段级错误(G4),非泛 toast |
| | S2 非法/超长 SpEL 条件 | A | SpelSafetyGuard 拒绝 |
| | S3 action 运行时失败 | B | 节点 failed + 错误暴露 + AutomationLog FAILED |
| | S4 无 trigger / 双 trigger | B | deriver 抛 ValidationException,拒绝 |
| | S5 condition=false | B | 下游 action 不执行(P0-2 gating) |
| edge | E1 多 action 顺序 | B | 按序全执行 |
| | E2 true+false 双分支 | B | 仅匹配分支执行 |
| | E3 loop for-each | B | body 跑 N 次(每元素绑定 itemVariable) |
| | E4 bpm-event + eventTypes 过滤 | B | 仅匹配 eventType 触发 |
| | E5 更新已有(重派生重部署) | A/B | 新行为生效 |
| | E6 disable→不触发→re-enable→触发 | B | 状态切换 |
| corner | C1 租户隔离(IDOR) | B/IT | B 租户看不到/触发不了 A 的 |
| | C2 并发触发信号量 | B | 不线程耗尽(per-rule semaphore) |
| | C3 空流程 | B | 拒绝/no-op 不崩 |
| | C4 delay/timer | **skip** | 挂起(roadmap #8),记原因 |
| | C5 i18n 无 raw code | A | palette/标签/错误态本地化 spot-check |
| | C6 scheduled cron 实时 | **skip/IT** | 重型,IT 级覆盖 |

---

## 6. §C2 全节点类型覆盖矩阵(18 类,每类 front + back 双验)

> 实证清单(grep `nodes/*.ts`):**7 触发 + 8 动作 + 3 控制 = 18**。(注:本数比早期 Explore 报告精确——动作第 8 个是 `action-start-process` 非 composite;控制有 3 个含 `control-delay`。)

**触发(7)** — front=palette 出现 + configSchema + 属性面板;back=对应事件真触发命中
| 节点 | back 行为断言 | 层 |
|---|---|---|
| trigger-record-create | 建 record → 触发 | A(主旅程)+B |
| trigger-record-update | 改 watched 字段 → 触发;非 watched → 不触发 | B |
| trigger-field-change | 字段 from→to → 触发 | B |
| trigger-state-change | 状态迁移 → 触发 | B |
| trigger-webhook | 合法签名 POST → 触发;坏签名 → 拒(接 #415 HMAC) | B |
| trigger-bpm-event | 匹配 eventType → 触发;不匹配 → 不触发 | B |
| trigger-scheduled | cron 实时重型 → **IT 级 / 诚实 skip**(记原因) | IT/skip |

**动作(8)** — front 同上;back=各自特定副作用(actionType→executor)
| 节点 (actionType) | back 副作用断言 | 层 |
|---|---|---|
| action-update-record (update_record) | 目标 record 字段被改 | A+B |
| action-create-record (create_record) | 新 record 行生成 | B |
| action-send-notification (send_notification) | 通知行 / 已发 | B |
| action-execute-command (execute_command) | command pipeline 执行 | B |
| action-call-api (call_api) | 出站 HTTP 被调(intercept/mock) | B |
| action-send-webhook (send_webhook) | 出站 webhook 发出 | B |
| action-start-process (start_process) | **BPM 流程实例启动**(仅断言已启动;下游执行归 bpmn slice) | B |
| action-llm-call (llm_call) | LLM 被调(mock provider / stub-llm) | B |

**控制(3)**
| 节点 | back | 层 |
|---|---|---|
| control-condition | gate true/false 分支(P0-2) | A+B |
| control-loop | delegate for-each body 跑 N 次 | B |
| control-delay | **runtime 诚实 skip**(timer 挂起 roadmap #8);front 仍验渲染 | skip(front 验) |

**如何不被 18 条慢拖拽拖垮**:
- **front 全 18 类**:① 1 个 palette 枚举测试(断言 18 类全在、category/i18n key/configSchema 形状)② 属性面板各字段类型渲染(component 测,部分已存,如 `actions.llm-vision.test`、`triggers.bpm-event.test`)③ Layer A 真拖拽**代表性几类**(model-select 触发 / json 动作 / process-select)验拖拽+配置机制(机制统一,不需 18 类全真拖)。
- **back 全 18 类**:Layer B 行为——7 触发各 fire 测、8 动作各副作用测、3 控制各行为测(API-setup + 真触发真断言;复用现有 executor 单测 + `AutomationTriggerServiceImplTest`,golden 补端到端可达性)。
- **结论**:18 个节点类型全部 front+back 验证(无渲染不接线 / 无接线无 UI),且不靠 18 条慢拖拽。

---

## 7. §D 基础设施 & Phase-0 闸门(§2.1)

- **隔离栈**(§11):`COMPOSE_PROJECT_NAME=auraboot-autogolden` + 端口偏移,或复用 GA E2E docker 栈模式(`up → bootstrap → down`,见 `feedback_check_docs_on_task_type_switch`:Web E2E 用 GA E2E 栈,**别**沿用 start-isolated)。backend + frontend(overlay build)+ PG + redis + SmartEngine。
- **Phase-0 gate(先验,后建 case;红线 §2.1)**:clean 冷 reset + seed →
  1. **`e2et_order` model 存在**——已实证它**不在** platform schema(`rg e2et_order platform/src/main/resources` 空),须先定位它由谁撒种(e2e bootstrap / `test-fixtures` 插件 / GA E2E setup),**确保隔离栈起栈后该 model + create command(`e2eto:create_e2et_order`)可用**;不可达就先修 infra,**禁止先建 case 到 golden 阶段才撞**。
  2. 设计器路由 `/automation/new` + `/automations` 可达,登录通,`/actuator/health` UP,BFF proxy 通。
  3. node-statuses endpoint 可达。
- **拖拽机制先实测(spec 第一步钉死)**:flow-designer-sdk(@xyflow)palette→canvas 落点是 HTML5 `dataTransfer` drop 事件,还是 @dnd-kit 指针?决定指针手势写法(红线 §20:单步 `dragTo` 可能失效,@dnd-kit 需多步 pointerdown/move/up)。先读 `flow-designer-sdk` 的 palette + canvas onDrop 源码确认,再写 helper。

---

## 8. §E Harness & 约定(为 bpmn slice 复用)

沉淀可复用 helper(放置于 e2e 公共目录,bpmn slice 直接复用):
- `dragNodeToCanvas(paletteType, position)` —— 按 §D 实测的机制(@xyflow onDrop vs @dnd-kit 多步指针)。
- `connectEdge(sourceNodeHandle, targetNodeHandle)` —— 从源节点 handle 拖到目标节点 handle。
- `fillNodeConfig(nodeId, configFields)` —— 属性面板填充(各字段类型:model-select / field-select / expression / json / select / multiselect / process-select / command-select / number / boolean / text / textarea)。
- `pollNodeStatuses(logId, timeout)` —— 已存在于 `automation-golden.spec.ts`,抽公共。
- `fireTriggerAndAssertSideEffect(...)` —— 触发 + 断言对应副作用。
- **真 `data.testid` 实查不猜(§2.2)**:先验设计器节点/handle/属性面板/save 按钮是否渲染稳定可测的 `data-testid`;缺则**补 testid 作为前置任务**(写进 plan 的 Phase-0)。

---

## 9. §F 验收闸门(§2.2 + /e2e-truth)

- **覆盖矩阵**:每 case 三列——执行状态(executed / skip+原因 / did-not-run / env-invalid)/ 证据链 / pass-or-fail;未执行行禁 silent 折叠。
- **断言状态迁移非存在性**:行出现 / 徽章→completed / 副作用字段真变 / 行消失;不接受「打开了表单」「HTTP 200」「toast 出现」当通过。
- **无 skip 包装产品缺口**:delay-runtime / scheduled-realtime skip 必须记真实原因(挂起/重型),**计 skip 不计 pass**;不得用 skip 掩盖坏功能。
- **真拖拽旅程导出截图人工复核**(§2.3 同理:眼看真实 UI 状态)。
- **声称达成前跑 `/e2e-truth` 自审**(5 维评分 + 反 4 类假通过)。
- **真拖拽 spec ≥3× 连续稳定**(flaky 特征化)。
- **环境守护(§2.1)**:full 跑中出现 `ECONNREFUSED` / 服务退出 / DB 断连 → 立即停、归类 `environment-invalid`、先修可重启性,不把级联失败当产品失败。

---

## 10. 实施切分(供 writing-plans 细化)

- **Phase 0 — infra preflight + 拖拽机制 + testid**:起隔离栈、验 `e2et_order` 可达、实测 @xyflow 落点机制、补缺失 `data-testid`、写 harness 骨架。**这是 gate,infra 不可达先修再继续。**
- **Phase 1 — Layer A 真拖拽 happy 旅程**(H1–H3)+ UI 层 sad/edge(S1/S2/E5)。
- **Phase 2 — Layer B 行为矩阵**:S3/S4/S5、E1/E2/E3/E4/E6、C1/C2/C3。
- **Phase 3 — 全节点类型覆盖**:7 触发 fire + 8 动作副作用 + 3 控制(front 枚举 + back 行为),delay/scheduled 诚实 skip。
- **Phase 4 — 验收**:覆盖矩阵 + 截图复核 + `/e2e-truth` + ≥3× 稳定 + 汇报分层(golden UI pass / behavioral pass / skip+原因 / did-not-run)。

---

## 11. 风险

- **拖拽机制不明**:@xyflow 落点若是 HTML5 dataTransfer,Playwright 需 dispatch dragstart/drop 事件;若 @dnd-kit 需多步指针。**Phase-0 先钉死**,否则 Layer A 全卡。
- **`e2et_order` seed 不可达**:golden 前提,Phase-0 gate;不可达则 infra 先修。
- **磁盘/并发栈压力**(§11,已有多个 docker 栈在跑):起隔离栈前查 disk ≥ 30GB、daemon 健康;复用 GA E2E 栈或用完即拆。
- **bpmn slice 复用前提**:harness 设计要从一开始就考虑 @xyflow 通用(automation 与 bpmn 都 @xyflow),避免写死 automation 专属。
