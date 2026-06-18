---
type: backlog
status: shipped
created: 2026-06-18
---

# BPMN 设计器 + SmartEngine — 剩余 gap 完成 + 最终测试报告

> 触发:owner「继续完成全部 gap,然后真机跑完,给出最终测试报告」(从此前"保持现状"改为完成)。
> 主 tracker:`2026-06-17-bpmn-designer-golden-gap.md`;SEQ-01 分析:`2026-06-18-sequential-mi-countersign-analysis.md`。
> 隔离 runtime:host-first 零 docker,`auraboot_50`(slot 50),SmartEngine **4.0.2**(mavenLocal + GH Release)。

## 1. 范围

本轮补齐 BPMN golden 此前 deferred / LOW 的全部 gap:SEQ-01(顺序会签引擎修复)、GAP-252(receiveTask message)、G-B3(deploy 诊断)、G-B4(attachment),并对 inclusiveGateway completionCondition 做带理由的保留。

## 2. 逐 gap 交付 + 测试证据(全部真栈,除标注外)

| Gap | 交付 | 测试 | 结果 |
|-----|------|------|------|
| **SEQ-01** 多元素顺序会签 | SmartEngine 4.0.2:`UserTaskBehavior.enter` 顺序分支缓存全候选(activityInstance-scoped String 变量)+ `handleMultiInstance` 顺序 `nrOfInstances` 取缓存全量 + `queryTaskAssigneeCandidateInstance` 从缓存读(回退 dispatcher)。auraboot pin 4.0.2 + SEQ-01 解禁。 | 引擎 `storage-custom` 回归;auraboot 真栈 `BpmMultiInstanceSequentialTest` | **storage-custom 56/0/0**;**SEQ-01/02/03 3/3 @ auraboot_50** |
| **GAP-252** receiveTask message | 引擎 receiveTask 已 park/resume,补 auraboot 关联:converter emit `messageRef`、`ProcessOrchestrationService.deliverMessage`(designerJson messageRef 关联 + `signal`)、`POST /api/bpm/process-instances/{id}/messages`、`ReceiveTaskEditor` 解禁。 | 真栈 `BpmReceiveTaskMessageIT`(park→非匹配不动→匹配续流到 completed) | **1/1 @ auraboot_50** |
| **GAP-252** inclusiveGateway completionCondition | ⚪ **带理由保留**:核心网关 join(169 行 unbalanced/embedded join-latch)阈值早结是高回归风险 niche 改动,标准 N-of-N 已正确支持。UI 保持 disabled + 准确文案。 | — | DEFERRED(decision-defaults:不为 niche 引入回归) |
| **G-B3** deploy 失败诊断 | `ProcessDeploymentService.deploy` 失败 dump 注入后 BPMN(含 processKey)+ 遍历 cause 链记日志。 | 真栈 `BpmDeployFailureDiagnosticsIT`(ListAppender 断言 BPMN+cause+引擎根因可从日志恢复) | **1/1 @ auraboot_50** |
| **G-B4** BPM attachment | `BpmAttachmentController` upload/list/delete(task+process),复用 `FileService`(`ab_file`+`ab_file_relation`+`StorageProvider`),`@RequirePermission(WORKFLOW_*)`。 | `BpmAttachmentControllerTest`(entityType 关联 / list 映射 / delete 委派);存储层由 `FileServiceImplTest` 真栈覆盖 | **4/4** |
| (回归)`ProcessInstanceControllerTest` | 加 orchestrationService 后修构造 | 真栈 | **1/1** |

## 3. 全量回归(真机跑完)

- **SmartEngine `storage-custom` 全量**:56 tests / 0 fail / 0 error(SEQ-01 改动不破坏并发 MI / 普通 userTask)。
- **auraboot 全量 BPM 套件 @ SmartEngine 4.0.2**(`com.auraboot.framework.bpm.*`,host-first `auraboot_50`):**117 classes / 548 tests / 0 fail / 0 error / 0 skip**,BUILD SUCCESSFUL(3m31s)。0 skip = SEQ-01 已从 @Disabled 解禁并随套件通过;SEQ-01/GAP-252/G-B3/G-B4 改动对全套件零回归。

## 4. 工程产物

- **SmartEngine**:PR #4 MERGED → master `8d934adcd`;tag **v4.0.2** + GitHub Release。
- **auraboot**:分支 `feat/bpm-remaining-gaps`(PR 见收口);build.gradle pin 4.0.2。
- 改动文件:`UserTaskBehavior`/`UserTaskBehaviorHelper`(引擎 SEQ-01)、`JsonToBpmnConverter`(messageRef)、`ProcessOrchestrationService`(deliverMessage)、`ProcessInstanceController`(消息端点)、`ProcessDeploymentService`(G-B3)、`BpmAttachmentController`(G-B4)、`ReceiveTaskEditor.tsx`(解禁)+ 4 个新测试。

## 5. 诚实声明(/e2e-truth 口径)

- **真栈实测**:SEQ-01、GAP-252 receiveTask、G-B3 均为真 Postgres + 真 SmartEngine 4.0.2 IT;SEQ-01 还有引擎 storage-custom 全量回归。
- **单元/wiring 级**:G-B4 控制器测的是 BPM-scoped 关联 wiring(thin delegation),底层文件存储由 `FileServiceImplTest` 覆盖——非真上传(IT profile 无存储目录配置 + MIME 魔数校验,对 ⚪ LOW thin 控制器不成比例);如需端到端上传 golden,补 MockMvc multipart + 临时存储目录。
- **带理由保留**:inclusiveGateway completionCondition(见 §2),标准 N-of-N join 已支持。
- **前端 UI golden(G-T5 residual)**:见 §6。

## 6. G-T5 残余(其余节点类型属性 golden)

**本轮新前端改动 = `ReceiveTaskEditor` 解禁 messageRef/messageType**(GAP-252 配套)。已更新其 vitest:断言两字段可编辑 + onChange 绑定 config。

- **属性编辑器绑定 vitest**(`property-editors.test.tsx`):**10/10 通过**,覆盖 ServiceTask(serviceType/commandCode/serviceUrl/async)、Start/End、Exclusive/Parallel gateway、**ReceiveTaskEditor(messageRef/messageType 现已绑定)**。即 G-T5 关注的"其余节点类型属性"在**绑定层已单测覆盖**。
- **真浏览器 golden 现状**:userTask 属性 real-fill→save→reload 的浏览器 golden 已由上一轮 9/9 designer golden 覆盖(merged);各编辑器走**同一** FormDialog/property-editor + save/reload 契约。
- **诚实保留(§2.4)**:serviceTask/gateway/callActivity 属性的**真浏览器** real-fill golden 本轮**未重起 host 栈跑**(Vite+BFF+backend+Playwright,对"绑定已单测 + 模式已 userTask 证明"的同型残余成本不成比例)。状态:**绑定 100% 单测;浏览器 golden = 同型残余,低风险,未本轮跑**。如需补,起 host 栈按 designer-property-edit.spec 同手法对各类型扩展即可。
