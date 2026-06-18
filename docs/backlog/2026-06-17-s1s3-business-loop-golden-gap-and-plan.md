---
type: backlog
status: active
created: 2026-06-17
owner: agent-quality-campaign
related:
  - docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
  - docs/handover/HANDOVER-2026-06-17-agent-quality-verification-campaign.md
---

# S1/S3 业务闭环金标 — 取证后的 gap 纠正 + 切片计划

> 「平台能力大图」campaign 的 P1 轨道。本文件是该轨道的 SOT。**取证优先(§15)**:下方所有「现状」均以 grep/psql/读测试源码取证,推翻了上游设计文档对 S3 的若干乐观标注。

## 0. 触发与范围

- 上游设计文档 `2026-06-17-platform-capability-map-and-test-scenario-design.md` §C 把 S1(智能客服闭环)/ S3(质量自动 CAPA)列为 **P1 业务闭环金标(T4 真栈 IT + T6 引擎运行时 成对)**。
- owner 在 campaign 续作中选定本轨道。范围 = S1/S3 **业务链端到端真栈 golden**(非 S6 浏览器 golden,非 build dashboard)。

## 1. 取证纠正:引擎级 ✅ ≠ 业务链级 ✅(F1)

设计文档 S3 行把 `automation/EventPolicy 触发`、`BPMN startProcess`、`SLA 激活` 标 `✅verified`。**取证后**:这些 ✅ 只对**引擎机制**成立,不对 **S3 业务链**成立。

| 引擎机制(真有真栈 IT) | 证据 |
|---|---|
| Automation CRUD | `AutomationServiceIntegrationTest`(只 CRUD,显式注释「triggerManually 不测」) |
| Automation 执行(SmartEngine 流程) | `AutomationProcessRuntimeIntegrationTest`(marker executor,无业务 model) |
| SLA 激活 + 升级 | `SlaDecisionE2EIntegrationTest`(BPM node task_assigned 驱动) |
| EventPolicy 评估 | `EventPolicyRuntimeIntegrationTest` |
| Decision 运行时 | `DecisionRuntimeIntegrationTest` / `DecisionTableIntegrationTest` |
| 命令管道(create + sideEffect) | `CommandCreateRecordSideEffectIT` |
| BPMN(SmartEngine 4.0) | `BpmFormServiceIntegrationTest` 等 |

**但没有任何 IT 把 `record.create → automation.condition → execute_command → 跨模型行 + audit log` 这条业务链串起来。** 这就是 S1/S3 的真 gap —— 引擎都强,组装没验。

## 2. 取证发现的真问题(findings)

### F2 — 投诉模型 3 套字段命名 drift + quality 模型未物化
- `mt_crm_complaint` 在共享 `aura_boot` 的真实列 = `crm_cp_title/description/status/priority/customer_email` —— 来源是 `platform/src/main/resources/database/schema.sql` 里 **hardcode 的平台 demo 表**。
- CRM 插件配置(`plugins/crm/config/`)的真模型用 `crm_cmp_account_id/severity/...`(完全不同的富模型)。
- `CustomerServiceAgentIntegrationTest` 系统提示词又让 agent 填 `crm_complaint_subject/...`(第三套)。
- 三者对不上,且该 IT 对「投诉是否真创建成功」**无硬断言(宽松)** → 很可能 green-but-broken。
- `mt_qc_capa` 在共享 DB **根本不存在**(quality 插件未导入物化)。
- **影响**:S1/S3 真栈 golden 撞 §2.1 Phase-0 infra gate —— 真插件模型未在可运行 DB 物化。**对策**:本轨道用 `CommandCreateRecordSideEffectIT` 同款 harness,在测试内 publish 与插件同形的 synthetic 模型(物化真表),验证**链路机制可达**;「真插件 import 物化可达」是更重的 host-first runtime 项,单列、不在本轨道默认范围内静默冒充。

### F3 — SLA 引擎是 BPM-node 耦合,裸 record 不触发
- `SlaActivationListener` 消费 `task_assigned` BpmEvent 激活 SLA;SLA config 绑 `NODE`+`activityId`。
- **裸 record(投诉/CAPA)创建不会激活 SLA**。设计文档「投诉 create 即激活响应 SLA」需要额外接线:要么把实体纳入 BPMN 流程(产生 task_assigned),要么补 record 级 SLA 激活路径(当前无)。
- 因此 S3 的 SLA 腿与 BPMN 腿天然耦合(defect → automation → BPMN userTask〔task_assigned→SLA〕→ approve → create_capa),这正是设计文档 S3 的流程顺序。

## 3. 切片计划(按依赖/ROI;每刀真栈 IT + 验收报告)

| 切片 | 证明什么 | 类型 | 状态 |
|---|---|---|---|
| **S3-1** | defect.create → automation(SmartEngine)→ `execute_command` → create_capa 真命令管道 → CAPA 行(字段从 defect 映射 + AutoSet code/status)+ `ab_automation_log` success;SpEL 条件负向 gating | T4+T6 | ✅ tested(`QualityAutoCapaChainGoldenIT` 2/2,commit `a2a3f0f20`;验收报告 `docs/retro/2026-06-17-s1s3-business-loop-golden-testing-gate-acceptance-report.md`) |
| **S3-2** | defect → automation → BPMN startProcess + userTask(task_assigned)→ SLA 激活(deadline)+ scheduler 标 overdue + 升级通知 | T6 | ✅ tested(`QualityCapaBpmnSlaChainGoldenIT` 2/2;**实测确认真流程起 userTask 自动发 task_assigned → SLA 同步激活**,无产品 bug) |
| **S3-3** | 全链组装:defect → automation → BPMN approve(task complete → on_bpm_event)→ create_capa | T4+T6 | ✅ tested(`QualityCapaFullAssemblyGoldenIT` 1/1;**实测确认 task_completed→EventBus→bridge→on_bpm_event automation(DB 查持久化规则)→create_capa 异步全通**,无产品 bug)|
| **S1-1** | `crm:create_complaint` 真栈命令 golden(用真 CRM 模型形状)+ 记录 F2 drift | T4 | ⬜ 计划 |
| **S1-2** | 投诉 create → automation 自动指派 + `ab_automation_log` | T6 | ⬜ 计划 |
| **S1-3** | 邮件 → 投诉字段抽取 live IT(真 DeepSeek) | T3 | ✅ tested(`CsComplaintEmailExtractionLiveIT` 1/1;真 DeepSeek:5/5 邮件 called+required complete,**字段准确 100%**〔含 severity 自然语言推断 high/critical/low/medium〕,0 幻觉字段;E6 模糊邮件不瞎编 account/severity = safe。key 跑后 redact 零残留)|

> SLA 在 S1/S3 都受 F3 约束:只在实体进 BPMN 节点时验证激活(S3-2),裸 record SLA 列为 finding。

## 4. 验证纪律
- harness:`BaseIntegrationTest`(integration-test profile → 共享 `aura_boot`,`@Rollback`/或 NOT_SUPPORTED + 自清 suffix 表,非破坏)。
- 模型:测试内 publish 与插件同形的 synthetic 模型(物化真表),不依赖插件 import。
- 真栈:真 SmartEngine 流程 + 真命令管道 + 真 DB 行断言 + 真 audit log,**禁** mock bridge。
- 跑完出**验收报告**(testing-gate §7),区分 tested / partial / did-not-run / 真插件 reachability 残留。
- 真插件 import 物化可达(host-first runtime)= 单列 follow-up,不在本轨道静默冒充。
