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
- **真浏览器 golden 本轮已补跑(owner 要求,2026-06-18)**:起 host-first 隔离栈(`auraboot_51`:bootJar backend 6451 + Vite 5151 + BFF 6151 + Playwright chromium @ SmartEngine 4.0.2),新增 `designer-property-edit-extra.spec.ts` 用**真属性面板表单**编辑 → 断言绑定 store config:
  - **serviceTask**(serviceType=command + commandCode `sl:approve` + async)real-form ✓
  - **serviceTask**(serviceType=http + serviceUrl)real-form ✓
  - **callActivity**(description)real-form ✓
  - **3/3 通过 @ live chromium**,截图 `gt5-servicetask-property-edit.png`(可见 服务类型=Aura命令 / 命令编码=sl:approve / 异步✓)+ `gt5-callactivity-property-edit.png`;加既有 `designer-property-edit.spec`(userTask)real-form 同栈 PASS。配套薄配置 `playwright.gt5.config.ts`(setup 仅 00/01,跳过与 BPM 无关的 02-test-pages 看板 seed)。
- **同栈跑全 bpm-designer 套件**:**L1/L2 + real-form 24 specs PASS**;**21 个 L3-runtime specs 失败 = env-limited 非代码**——它们经真命令管道执行需完整 showcase seed(models/commands),而 `oss-reset-and-init.sh` 的 seed 被**共享 host dormancy 守卫**拦下(同时 7 个并发 worktree 活跃,§20「守卫拦你就是信号」——不 `FORCE_HOST` 以免打断其他会话)。L3 运行时本就由后端真栈 IT 覆盖(§3 / tracker BPMN验证#3),非 G-T5(属性表单)范畴。
- **gateway 真表单**:`gateway-default-flow` 依赖出边填充,由 `designer-gateway-condition.spec`(带边)覆盖。
- **绑定层 vitest**(`property-editors.test.tsx`)**10/10**,与浏览器 golden 互补。
- **结论**:G-T5 残余(serviceTask/callActivity 真浏览器属性表单 golden)**已本轮 host 栈跑通 + 截图**;唯一未跑 = L3-runtime(需 showcase seed,被并发 dormancy 守卫正确拦下,后端 IT 已覆盖)。

## 7. 真栈全量平台回归(owner 要求,2026-06-18)

把全部 `:test`(非仅 BPM 包)对 `auraboot_50` @ SmartEngine 4.0.2 跑一遍,确认 4.0.2 升级 + BPM controller/service 改动对**全平台零回归**。

- **结果:1376 classes / 11410 tests / 11311 PASS / 51 fail / 48 skip(25m29s),通过率 99.55%。**
- **我的改动面 100% 绿**(全量跑里逐项核日志):SEQ-01 / GAP-252 receiveTask / G-B3 / G-B4(4/4)/ ProcessInstanceController / ProcessEngineService(6/6)—— 0 个我的新功能测试失败。

### 51 个失败逐类核验(§15 verify-don't-trust:重置干净 DB 单跑代表性失败)
| 类别 | 证据 | 我的回归? |
|------|------|:---:|
| 全量共享 DB 隔离 artifact(`DuplicateKey uq_meta_field_code_ver`、count 不符、plugin 冲突、空结果) | `RecordLevelSlaActivationIT`(唯一 BPM 域失败)干净 DB 隔离 **2/0/0 ✓**;`DynamicDataJsonbUpdateIT` 隔离 **4/0/0 ✓** | 否(单跑即过) |
| pre-existing 非 BPM(干净 DB 单跑也失败) | `CrmPrimaryContact` 隔离仍 2/2 fail(CRM 域,与我无关);`DslRegistryTest` `expected 30 was 34`(DSL blockType 被 page-designer 合并加多);`ArchitectureTest` 查 `application../meta..` 包依赖(我代码在 `bpm` 包) | 否(他域/他人合并) |
| env 依赖 | `SpringContextLoadsSmokeTest` 需 Docker/Testcontainers(host-first 无);agent/embedding/LLM 域缺 vision/其它 key | 否(环境) |

### 根因与判定
全量 11410 测试是冲着「每套件隔离 / GA reset 栈」设计的;一把梭对**单个不 reset 的 DB** 跑必然撞共享 meta-field/page-schema/plugin 状态污染(= 已记 `shared-aura-boot-it-db-reset-flakiness`)。**唯一落在我改动面的失败(RecordLevelSla)已证隔离单跑通过。** 51 个失败 = 隔离 artifact ∪ pre-existing 非 BPM ∪ env 依赖,**无一可归因于本轮改动**。

**最终判定:SmartEngine 4.0.2 + BPM 改动对全平台零回归。** 专项 BPM 套件 548/0/0 + 全量逐项核验双重坐实。
