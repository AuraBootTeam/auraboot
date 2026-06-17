---
type: backlog
status: active
created: 2026-06-17
---

# BPMN 流程设计器 + 后端 SmartEngine 联动 — 完整方案与 Gap(黄金交付)

> 目标(用户 goal):针对 BPMN 设计器与后端 SmartEngine 联动,分析现有**测试覆盖度**与**页面 UX 交互性**(每个组件 / 每个属性 / 每个行动点 / 每个视觉反馈),输出完整方案与 gap,然后修复全部 gap 到黄金交付。
>
> 工作区:`auraboot/.worktrees/bpmn-designer-golden`(分支 `feat/bpmn-designer-golden`,基于 `origin/main` `a46566055`)。
> 本文件是 `/aura-endgame` 流水线的 P1(终局)+ P2(gap)+ P3(一致性)+ P4(覆盖矩阵)合并交付物 + 长存 tracker。

---

## 0. 终局态(P1)

BPMN 流程设计器是 production-ready 的可视化流程编排器,**已具备完整功能纵深**(非 MVP)。终局态 = 在现有功能基础上达到:

1. **每个组件 / 属性 / 行动点 / 视觉反馈都有黄金级测试覆盖**:真浏览器驱动真实交互(palette 拖拽、属性面板表单编辑、toolbar 每个按钮),断言状态变化 + 保存 payload + reload 回显 + 后端部署/运行时,截图复核。
2. **后端真栈测试无假通过**:消除 `assumeTrue(false)` 静默 skip、注释掉的核心服务测试,集成测试 ≥80% 真栈 happy-path。
3. **UX 视觉反馈闭环**:校验错误在画布上节点级高亮;空/加载/错误态、二级入口完整。
4. **设计器↔后端 seam 成对验证**:保存 → 部署 → 运行时每个行动点都有 browser evidence + backend evidence。

### 系统架构终局(已实测确认,见 §1)

- 前端:`web-admin/app/plugins/core-designer/components/bpmn-designer/`(ReactFlow/@xyflow legacy 内核)+ 共享 `flow-designer-sdk` store(经 `useBpmFlowStore` adapter)。
- 后端:SmartEngine 4.0.0 AuraBoot fork 真引擎直连;`ProcessEngineService`(运行时)/ `ProcessDeploymentService`(部署)/ `TaskService`(任务)。
- 契约:设计器存 **JSON DSL(designerJson)**,后端 `JsonToBpmnConverter` 编译成 BPMN 2.0 XML;回显靠存储的 designerJson(非 XML 反解,`BpmnToJsonConverter` 是 test-only 孤儿)。
- 持久化:`ab_bpm_*`(定义/钩子/审计/规则)+ SmartEngine `se_*`(实例/任务/执行/变量,**非** Activiti `act_`)。

---

## 1. 现状穷举清单(P1 盘自家底 — 取证)

### 1.1 前端组件树
| 组件 | 职责 | 文件 |
|------|------|------|
| BPMNDesigner | 入口 `/bpmn-designer` | `bpmn-designer/BPMNDesigner.tsx:43` |
| BPMNToolbar | name/key + 状态徽章 + 11 行动点 | `components/BPMNToolbar.tsx:30` |
| BPMNPalette | 9 节点拖入,3 分组 | `components/BPMNPalette.tsx:29` |
| BPMNCanvas | @xyflow 画布,HTML5 DnD | `components/BPMNCanvas.tsx:69` |
| BPMNPropertyPanel | 属性面板 dispatcher | `components/BPMNPropertyPanel.tsx:56` |
| SaveDialog | 保存对话框 + 字段校验 | `components/SaveDialog.tsx:42` |
| ProcessStatusBadge | draft/published/suspended 徽章 | `components/ProcessStatusBadge.tsx:43` |
| ProcessMetadataPanel | 流程级元数据(withdraw/cc policy) | `property-editors/ProcessMetadataPanel.tsx:16` |
| ProcessStatusViewer | 独立运行态查看器 | `components/ProcessStatusViewer.tsx:68` |

### 1.2 节点类型(canvas 注册 12 type / 9 组件)
palette 9 项:startEvent / endEvent / userTask / serviceTask / receiveTask / exclusiveGateway / parallelGateway / inclusiveGateway / callActivity。
import-only 3 委派 type(无 palette、无专属 editor,复用 ServiceTaskNode):rule-task / notification-task / record-update-task。
边:`conditional`(ConditionalEdge)。

### 1.3 属性编辑器(每节点每属性)
- ProcessMetadataPanel:name / processKey / description / category(datalist)/ withdrawPolicy / ccPolicy
- StartEventEditor:description / initiator / formKey
- EndEventEditor:description / terminateAll
- UserTaskEditor(最复杂):description / assignee.type(user/role/dept/starter/expression)+ AssigneePicker(远程多选)/ assigneeMode / assignmentRuleBinding(RuleCenterBindingSection)/ priority / skipable / dueDate / MultiInstanceSection / FormBindingSection / HookConfigSection / requiredPermissions / ccPolicyOverride
- ServiceTaskEditor:serviceType(http/java/script/command)条件字段 + async + HookConfigSection
- ReceiveTaskEditor:description + messageRef/messageType(**🔒 GAP-252 disabled readOnly**)
- ExclusiveGatewayEditor:defaultFlow + 出边条件汇总 + RuleCenterBindingSection
- InclusiveGatewayEditor:defaultFlow + completionCondition(**🔒 GAP-252 disabled readOnly**)+ RuleCenterBindingSection
- ParallelGatewayEditor:description only
- EdgeEditor:label + ConditionExpressionEditor(simple 9 操作符 + AND/OR / advanced MVEL/JUEL/script)+ isDefault
- CallActivityEditor:calledProcessKey(ProcessPicker)+ version + 变量映射
- 折叠 section:MultiInstanceSection / FormBindingSection(PagePicker + VariableMapping + FieldPermission)/ HookConfigSection(http/script/command 三子配置)

### 1.4 Toolbar 行动点(11)
name / key 输入 · Undo · Redo · Save(→ 校验 → SaveDialog)· Validate · Import(file → JSON)· Export(Blob 下载,纯前端)· Version History · Deploy · Monitor 切换。键盘:Ctrl+Z/Y/S;Canvas Delete/Backspace。

### 1.5 视觉反馈
ProcessStatusBadge · dirty/saving 标 · 校验红 banner(前 3 条 error)· 历史版本黄 banner · 监控 indigo 条 · 节点监控高亮(active 蓝 pulse / completed 绿 ✓ / idle opacity-50)· 空/加载/错误态 · 拖放 ring 反馈。

### 1.6 后端 seam 端点(全 ✅,`bpmnService.ts` + `ProcessDefinitionController`/`ProcessInstanceController`/`TaskController`)
列表/读取/创建/更新/删除/部署/挂起/恢复/取 BPMN XML/实例状态/任务全套。

---

## 2. Gap 清单(P2 — 优先级 + 验收方式)

> 图例:🔴 HIGH / 🟡 MED / ⚪ LOW · [FE]前端 [BE]后端 [E2E]测试 [SEAM]联动

### A. 测试覆盖 gap(用户核心 ask)

| ID | 优先级 | 域 | gap | 证据 | 验收 |
|----|--------|----|-----|------|------|
| G-T1 | 🔴 | [E2E] | `bpm-designer/` golden 全走 `__bpmDesigner` 钩子构图,绕过**真实 palette 拖拽 + 真实属性面板表单编辑**(仅 BD-005/BD-020 smoke) | `helpers/designer-dsl.ts:162-203` | 新增真 UI 交互 golden:真拖拽 9 节点 + 真属性面板编辑 → 保存 payload 含配置 → reload 回显;截图 |
| G-T2 | 🔴 | [E2E] | Import / Export 按钮**前后端零测试** | toolbar 有按钮,无 spec | golden:Export 下载文件名 `<key>.json` + 内容含 nodes/edges;Import 回填画布 |
| G-T3 | 🔴 | [BE] | **40 处 `assumeTrue(false, "SmartEngine not available")` catch-块静默 skip** + 2 个核心服务测试整文件注释 | `BpmTaskOperationTest`(18)/`ProcessOrchestrationServiceExtTest`(9)/`TriggerServiceTest`/`CallbackServiceTest`/`BpmTaskActionsFallbackTest`;`ProcessEngineServiceTest.java`、`TenantAwareProcessEngineServiceTest.java` 整文件注释 | 移除静默 skip → 真栈断言;恢复 2 个服务测试或说明替代覆盖 |
| G-T4 | 🟡 | [E2E] | Validate / Undo / Redo / Monitor **无真 UI E2E** | 仅 store 单测 + BD-010 smoke | golden:非法图 → Validate → 红 banner + error 数;Undo/Redo 状态回退;Monitor 切换 |
| G-T5 | 🟡 | [E2E] | 每属性编辑器真表单编辑 → 保存 payload → reload 回显(目前仅 node-property-matrix 部分真 fill) | `designer-node-property-matrix.spec.ts` | 扩展属性面板真 fill 矩阵 |
| G-T6 | ⚪ | [BE] | 顺序多实例真 happy-path `@Disabled`(上游 fork 缺陷) | `BpmMultiInstanceSequentialTest:133,220` | BLOCKED-UPSTREAM,记录;不阻断 |

### B. UX 交互 / 视觉反馈 gap

| ID | 优先级 | 域 | gap | 证据 | 验收 |
|----|--------|----|-----|------|------|
| G-U1 | 🟡 | [FE] | **校验错误无画布节点级高亮**(error 携带 nodeId 但仅红 banner + toast) | `BPMNDesigner.tsx:469`;`runBpmnValidation` error 带 nodeId | 校验失败时画布对应节点描红/高亮 + 单测 + golden 截图 |
| G-U2 | ⚪ | [FE] | service-delegate 节点(rule/notification/record-update)无 palette 入口 + 无专属 editor | canvas 注册了 type 但无 UI 路径 | 决策:补 palette+editor 或文档化"仅 import" |
| G-U3 | ⚪ | [FE] | 版本回滚 no-op(VersionHistoryPanel 回滚对 BPMN 无效) | `BPMNDesigner.tsx:80` | UX:回滚按钮对 BPMN 禁用/隐藏 + 说明,或实现 |
| G-U4 | ⚪ | [FE] | 校验 error/warning 区分不足;无"跳到错误节点" | 红 banner 仅文字 | banner error 可点击定位节点(配合 G-U1) |

### C. 后端联动 gap

| ID | 优先级 | 域 | gap | 证据 | 验收 |
|----|--------|----|-----|------|------|
| G-B1 | 🟡 | [SEAM] | **无服务端 designerJson 预校验/preview 端点**;校验只在 deploy 时隐式发生,root cause 易被引擎吞 | grep 确认无 `/validate`(BPMN);`ProcessDeploymentService:491` 包 BusinessException | 加 `POST /api/bpm/process-definitions/validate`:编译 designerJson 返结构化错误(不部署)+ 前端 Validate 接它 |
| G-B2 | 🟡 | [BE] | `JsonToBpmnConverter` 未知节点类型**静默 skip + log.warn** → designerJson 含不支持节点被默默丢弃 | `JsonToBpmnConverter.java:322-324` | 未知 type 抛 `BpmnConversionException`(带 nodeId)+ 单测 |
| G-B3 | ⚪ | [BE] | deploy 失败 root cause 被 SmartEngine 吞,`BusinessException` 仅透传 message | `ProcessDeploymentService:491` | 部署失败时记录注入后 BPMN + cause 链到日志 |
| G-B4 | ⚪ | [BE] | `BpmAttachmentController` 是 stub | 类注释 stub | 文档化/backlog,不阻断 |
| **G-B5** | ✅ **FIXED** | [BE] | (原)rollback / add-sign / remove-sign 在真栈下 100% 失败:引擎从 `taskInstance.getClaimUserId()` 取 operator,任务**已分派未认领**时 claimUserId=null → `se_process_rollback_record`/`se_assignee_operation_record` 的 `operator_user_id`(NOT NULL)约束违反 → 命令 500;曾被 `assumeTrue(false)` 静默 skip 掩盖。 | 引擎实际从 claimUserId 取 operator(`DefaultTaskCommandService` rollback:375/addSign:406/removeSign:437),未认领即 null | **已修复**:SmartEngine fork 加显式 `operatorUserId` 重载(`rollbackTask`/`add|removeTaskAssigneeCandidateWithReason`,旧签名委托回退 claimUserId;**PR #2 merged**),auraboot `TaskService` 三处传 `getCurrentUserId()`(真操作人)。真栈验证:`BpmTaskOperationTest` **18 pass / 0 skip / 0 fail**(3 个原 @Disabled 解禁后真通过)。 |

---

## 3. 覆盖矩阵(P4 — 行动点 → 当前覆盖 → 目标)

| 能力维度 | 当前 | 目标(本轮) | 实现路线 |
|---------|------|-----------|---------|
| Palette 真拖拽(9 节点) | smoke only | 真 UI golden 每类型 | E2E real drag |
| 属性面板真表单编辑(每编辑器) | 部分真 fill | 真 fill 矩阵 + 回显 | E2E real fill |
| Save 按钮 | 真 + IT | 保持 | — |
| Deploy 按钮 | 真 + IT | 保持 | — |
| Validate 按钮 | smoke | golden(非法/合法)+ 节点高亮 + 服务端端点 | FE+BE+E2E |
| Import / Export | ❌ 零测试 | golden(文件名+内容+回填) | E2E |
| Undo / Redo | store 单测 | 真 UI golden | E2E |
| Monitor 模式 | hook 单测 | 真实例监控 golden | E2E |
| 校验视觉反馈(节点高亮) | ❌ | 实现 + 单测 + 截图 | FE |
| 后端任务流转真栈 | 40 处假 skip | 真断言无静默 skip | BE |
| 未知节点处理 | 静默丢弃 | 抛错 + 单测 | BE |
| 服务端预校验 | ❌ | `/validate` 端点 + IT | BE+SEAM |

---

## 4. 执行计划(P5 — slice 化,TDD + golden)

- **S0 infra Phase 0 gate**:host-first 起 OSS backend(dev.sh runtime,独立 slot)+ Vite/BFF + Playwright(零 docker)。验证 `/actuator/health`、seed JWT、`/bpmn-designer` 可达。
- **S1 [BE] 假通过清理(G-T3)**:消除 40 处 `assumeTrue(false)` 静默 skip → 真断言;恢复/说明 2 个注释服务测试。跑真栈验证。
- **S2 [BE] 转换器健壮性(G-B2)+ 服务端预校验端点(G-B1)**:未知节点抛错 + `/validate` 端点。单测 + IT。
- **S3 [FE] 校验视觉反馈(G-U1/G-U4)**:画布节点级高亮 + banner 可点击定位 + 前端 Validate 接 `/validate` 端点。vitest + golden 截图。
- **S4 [E2E] 真 UI golden(G-T1/G-T2/G-T4/G-T5)**:palette 真拖拽 + 属性面板真表单 + Import/Export + Validate/Undo/Redo/Monitor。截图复核。
- **S5 决策项(G-U2/G-U3)**:service-delegate 节点 palette/editor 或文档化;版本回滚 UX。
- **RV 完成前全量复核**:`/e2e-feature-coverage` + `/e2e-truth` + 五项证据 + 截图。

### 已知 BLOCKED(记录,不阻断流水线)
- G-T6 顺序多实例:上游 SmartEngine fork 缺陷(`@Disabled` SEQ-MI-GAP-1/2),标 BLOCKED-UPSTREAM。
- GAP-252 receiveTask message / inclusiveGateway completionCondition:SmartEngine 无 parser,UI 已 disabled readOnly + 文案,保持。

---

## 进度

- [x] P0 上下文校准 + worktree 隔离
- [x] P1 终局对齐 + 盘自家底(本文件 §0/§1)
- [x] P2 gap 分析(本文件 §2)
- [x] P3 一致性核对(终局/UX/gap 自洽,无悬空引用)
- [x] P4 覆盖矩阵(本文件 §3)
- [x] S0 infra Phase 0 gate(isolated runtime `bpmn-designer-golden-42`,slot 42;auraboot_42 schema applied 307 表;IT 真栈可达,verified BpmTaskOperationTest 15 pass)
- [x] S1 BE 假通过清理(G-T3)— **全部 40 处 `assumeTrue(false)` 清零**(#714 BpmTaskOperationTest 18〔15 pass/3 @Disabled〕+ #718 BpmTaskActionsFallbackTest 3 / TriggerServiceTest 3 / CallbackServiceTest 3 / ProcessOrchestrationServiceTest 4 / ProcessOrchestrationServiceExtTest 9,后 5 文件 32 tests 全真通过 verified auraboot_42)。**scope-out(非迭代,理由记录)**:2 个整文件注释的 `ProcessEngineServiceTest`(23)/`TenantAwareProcessEngineServiceTest`(14)是 **Mockito mock-engine 单元测试**(`@Mock SmartEngine`+`@InjectMocks`),因 ProcessEngineService API 漂移而注释;红线偏好真栈 > mock,且 ProcessEngineService 真栈覆盖已由 orchestration/task IT 提供。**决策:不恢复 mock 版;若要补应写真栈 IT**(类似 ProcessDesignerJsonValidationIT)。
- [x] S2a BE 转换器健壮(G-B2)— DONE(`6510264e5`:未知节点抛错,50/50 converter test)
- [x] S2b BE 预校验端点(G-B1)— DONE(PR #715 `acedc9d66`:`POST /api/bpm/process-definitions/validate` + `validateDesignerJson`,4/4 IT 真栈)
- [x] S3 FE 校验视觉反馈(G-U1)— DONE(`6d2e75ae6`:画布节点级 error/warning 高亮,24/24 vitest);G-U4 banner 可点击定位 pending
- [~] S4 E2E 真 UI golden — **host-first 全栈起栈完成 + 3 个真浏览器 golden spec MERGED + live seam 验证**(auraboot slot 43:bootJar→java -jar backend 6443 + bootstrap + Vite 5143 + BFF 6143;Playwright chromium):
  - **G-U1**(#728)real Validate 按钮 → 错误节点 `ring-red-500` 高亮断言 + 截图(live 1 passed;程序化确认 start1/gw1 红环、task1/end1 无)
  - **G-T1**(#729)real HTML5 palette 拖拽(dispatchEvent 真 onDragStart/onDrop,非测试钩子)→ userTask+exclusiveGateway 落画布(live 1 passed)
  - **G-T2**(#730)real Export 按钮 → 捕获下载 → 文件名 .json + 内容含节点/边(live 1 passed)
  - **live 前后端 seam**(MCP 真浏览器,证据=截图 bpmn-golden-0[1-4].png + DB):真 Save→SaveDialog→后端持久化(Golden Live Process,has_bpmn=t/has_designerJson=t)+ 真 Deploy→status draft→deployed+deployment_id 落库
  - **Group A 续(后续会话 PR #736)**:**G-B1 前端接线**(handleValidate → POST /validate)+ **G-T5** 属性面板真编辑 golden(真拖拽+点选+真 fill description/priority/skipable→store config)+ **G-T4** undo/redo/monitor 真按钮 golden + **import** round-trip golden;全 live 通过(8/8→后含 G-U4 共 9/9)
  - **剩余迭代**:G-T5 其余节点类型属性真 fill 矩阵扩展(userTask 已覆盖;serviceTask/网关/callActivity 可复用同手法)
- [x] S5 决策项(G-U2/G-U3/G-U4)— **Group B(PR #737)**:
  - **G-U4 DONE**:校验 banner 错误项可点击 → `setSelectedNode(nodeId)` 定位节点(配合 G-U1 红环);golden `designer-validation-locate.spec.ts` live 1 passed
  - **G-U3 DONE(决策:反馈而非静默)**:版本回滚对 BPMN 是 no-op,原静默 → 改为 `showWarningToast(bpmn.version.rollback_unsupported)` 给明确反馈 + 引导 preview→Save-as-new;真正 restore(重部署历史 BPMN)= backlog
  - **G-U2 决策:文档化为 import-only 高级类型(不补 palette)**:rule-task/notification-task/record-update-task 是 service-delegation 高级原语,后端 converter + service 校验已全支持;按 §7 config-first,为 3 个 niche 类型加 palette+bespoke editor 不成比例。决策:保持经 designerJson import / 程序化配置(非 palette 拖拽),常用类型(UserTask/ServiceTask/Gateways/CallActivity)在 palette。若产品后续要入 palette,路径=加 palette items + 仿 ServiceTaskEditor 的 property editor
- [ ] RV 完成前全量复核 + 复核文档
- [ ] P6 复盘固化 + 收口 merge

## 本会话已交付(verified)

| commit | gap | 内容 | 验证 |
|--------|-----|------|------|
| `6d2e75ae6` | G-U1 | 画布节点级校验高亮(error 红 / warning 琥珀 / 与 monitor 互斥,precedence monitor>error>warning>selected) | 24/24 vitest |
| `6510264e5` | G-B2 | JsonToBpmnConverter 未知节点抛 BpmnConversionException(非静默丢弃) | 50/50 converter test(2 新 UnknownNodeType) |
| `bacad7cce`(#714) | G-T3 / G-B5 | BpmTaskOperationTest 消除 18 处 `assumeTrue(false)` 假通过;assignee 绑定 actor 使 5 操作真通过;揭露 G-B5 上游 operator_user_id bug(rollback/addSign/removeSign)并诚实 @Disabled | 18 tests:15 pass / 3 @Disabled / 0 fail(真栈 auraboot_42) |
| `acedc9d66`(#715) | G-B1 | 服务端 designerJson 预校验端点 `POST /validate` + `validateDesignerJson` | 4/4 ProcessDesignerJsonValidationIT(真栈) |
| `2c014eafb`(#718) | G-T3 | 清零剩余 5 文件 37 处 `assumeTrue(false)`(fallback/Trigger/Callback/Orchestration/Ext) | 32 tests 全真通过(真栈 auraboot_42) |

> 累计 4 个 PR merged(#714/#715/#716/#718)。**40 处 `assumeTrue(false)` 假通过全部清零**。

## P6 复盘(根因四分类 + 教训)

**弯路/返工**:基本顺畅。一处:首跑 converter 单测用 `./gradlew test`(全子项目)致 `:platform-plugin-api:test` no-test 失败 + `cmd|tail` 吞退出码假绿 → 改 `:test`(root)scope + 不用 `|tail` 取真退出码(印证红线「管道掩盖退出码」)。

**为什么这些问题存在(四分类)**:
- **A 门禁质量**:`assumeTrue(false)` 假通过 + 整文件注释测试能长期存在,因无门禁检测「catch→assumeTrue(false)」反模式;converter 静默丢节点无门禁。→ 建议加 lint/grep 门禁禁 `assumeTrue(false`(已可 grep 检出)。
- **B 输入**:G-B5 上游 operator_user_id 限制此前被假通过掩盖,无人知晓。→ 已 backlog + @Disabled 暴露。
- **C 提示词/编排**:无(本会话取证纪律到位)。
- **D 验证纪律**:本会话坚持真栈 IT(auraboot_42)验证每个改动,未信任 compile/static;移除假通过后逐一真跑暴露 real-pass vs upstream-bug(G-B5)。

**固化建议(未自动执行,记此供下轮)**:① canonical 加红线「测试禁 `catch(Exception)→assumeTrue(false)` 静默 skip;真栈不可用应 fail 或显式 @Disabled+理由」;② `JsonToBpmnConverter` 未知节点抛错已落地,可作 G-B2 类 converter 健壮性范式。

### 本会话未做(诚实声明 /e2e-truth)— 下一轮迭代

- **S4 真 UI golden(G-T1/T2/T4/T5)= 未执行**:real palette 拖拽 / 属性面板真编辑 / import-export / undo-redo / validate / monitor / G-U1 高亮视觉 golden 均需 host-first 全栈(Vite+BFF+backend+bootstrap+seed+Playwright auth)。本会话只起到 backend IT 真栈(auraboot_42 已 schema,可复用);未起前端全栈跑浏览器 golden。**G-U1 仅 vitest 单元级验证,未做真浏览器视觉 golden**。
- **G-T3 剩余 5 文件 + 2 注释服务测试**:`ProcessOrchestrationServiceExtTest`(9)/`ProcessOrchestrationServiceTest`(4)/`TriggerServiceTest`(3)/`CallbackServiceTest`(3)/`BpmTaskActionsFallbackTest`(3)的 `assumeTrue(false)` 假通过 + `ProcessEngineServiceTest`(23)/`TenantAwareProcessEngineServiceTest`(14)整文件注释 — 同 BpmTaskOperationTest 模式逐文件清理,需真栈 IT 验证(可能再揭露 upstream bug)。
- **G-B1 前端接线**:Validate 按钮调 `/validate` 端点 + 真浏览器 golden。
- **G-U4 / G-U2 / G-U3 / G-B3**:banner 可点击定位 / service-delegate palette / 版本回滚 UX / deploy cause 日志。
- **G-B5(上游)**:SmartEngine fork operator_user_id — BLOCKED-UPSTREAM,需引擎改动。

> **隔离 runtime `bpmn-designer-golden-42`(slot 42,auraboot_42 schema applied)保留**(8h TTL)供下一轮 UI golden 续用;收口时 `./dev.sh infra cleanup bpmn-designer-golden-42 --yes` + `runtime destroy`。

**关键洞察**:G-T3 的 `assumeTrue(false)` 假通过模式不是孤立的——它**同时掩盖了**(a)测试 setup 缺陷 与(b)真实上游产品 bug(G-B5)。移除假通过后两者都暴露。这正是「测试缺失/假通过 = 未完成」红线的实证。
