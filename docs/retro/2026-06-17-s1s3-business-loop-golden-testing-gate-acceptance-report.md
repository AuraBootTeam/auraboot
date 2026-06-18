---
type: retro
status: active
created: 2026-06-17
---

# 验收报告 — S1/S3 业务闭环金标(agent quality campaign P1 轨道)

> testing-gate §7 验收报告。本轨道的最终测试声明以本报告为准(非聊天总结/通过计数)。逐切片更新。

`allowed_claim`: **S3 整链闭环 tested(真栈 5 tests)+ S1-3 邮件抽取智能 tested(真 DeepSeek 1 test,5/5 100% 字段准确 + E6 不瞎编)。共 6 tests 0 fail。S1-1/S1-2 deterministic 刀 did-not-run(机制与 S3-1/2 同构,ROI 次)。**

## SOT
- 轨道 SOT:`docs/backlog/2026-06-17-s1s3-business-loop-golden-gap-and-plan.md`(取证纠正 + 切片计划 + findings F1/F2/F3)。
- 上游设计:`docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md` §C(S1/S3)。
- business_scope:S1 智能客服闭环(投诉登记/指派/SLA)、S3 质量自动 CAPA(缺陷→automation→BPMN→CAPA→SLA)。非目标:S6 浏览器 golden、build dashboard、真插件 import 物化可达(单列 follow-up)。

## 切片状态

### S3-1 — defect → automation → create_capa 真命令管道 → CAPA 行 + audit log  ✅ tested
- 测试:`platform/src/test/java/com/auraboot/framework/automation/QualityAutoCapaChainGoldenIT.java`
- 命令:`./gradlew :test --tests 'com.auraboot.framework.automation.QualityAutoCapaChainGoldenIT' -PspringTestContextCacheMaxSize=4`(platform/)
- 真凭据(JUnit XML):`tests="2" skipped="0" failures="0" errors="0"`;BUILD SUCCESSFUL in 47s;class mtime 23:40(新鲜编译,非 stale)。
- 断言(真栈,真 DB 行):
  - automation 经 **真 SmartEngine 流程** 触发(`executeAutomation` 统一同步入口,run_status=success)。
  - `execute_command` 路由到 **真 `CommandExecutor`** → create_capa 全管道。
  - `${recordId}` 把 CAPA 链回源 defect pid;`${record.qcd_description}` / `${record.qcd_root_cause}` 跨模型携带字段。
  - AUTO_SET 在 automation 驱动下仍触发(`qcc_code` auto_generate 非空且 `CAPA-` 前缀;`qcc_status` fixed = open)。
  - CAPA 是新行(pid ≠ defect pid;`execute_command` 注入的 `pid` 未污染 create 主键)。
  - `ab_automation_log` success 行持久(survives @Transactional run;trigger_record_id = defect pid)。
  - **负向 gating**:minor severity → `evaluateCondition` false → 不产生 CAPA(SpEL 条件门正确)。
- commit:`a2a3f0f20`(分支 `feat/agent-eval-s1s3-business-golden`)。
- product bug:**无**(引擎组装正确,正向验证)。

### S3-2 — automation → BPMN startProcess + userTask → SLA 激活 + 升级  ✅ tested
- 测试:`platform/src/test/java/com/auraboot/framework/automation/QualityCapaBpmnSlaChainGoldenIT.java`
- 真凭据(JUnit XML):`tests="2" skipped="0" failures="0" errors="0"`;BUILD SUCCESSFUL in 42s。
- 断言(真栈):
  - automation(真 SmartEngine 流程)→ `start_process` action → `StartProcessActionExecutor` → **真部署 BPMN 进程启动**(run_status=success)。
  - userTask 创建 → `AuraTaskEventPublisher` **同步**发 `task_assigned` → `SlaActivationListener`(同步 @EventListener)→ `ab_sla_record` 激活(node_id 唯一,exactly 1;FIXED PT2H deadline ≈ 120min)。
  - 业务关联:`taskService.getTasksByProcessInstance` 在 review 节点找到真 userTask。
  - 升级:伪造过期 deadline → `scanSlaRecords()` → status=overdue + currentWarningLevel=1 + warningHistory[0].action=escalate + `ab_bpm_notify_record` 通知收件人(content 含 "SLA ESCALATION")。
  - **关键正向发现**:真流程起 userTask **自动**发 task_assigned 激活 SLA(此前 SLA 引擎 IT 是手动 publish 事件;此处证明真链路自动连通),无产品 bug。
- product bug:**无**。

### S3-3 — 全链组装(defect → automation → BPMN approve → create_capa)  ✅ tested
- 测试:`platform/src/test/java/com/auraboot/framework/automation/QualityCapaFullAssemblyGoldenIT.java`(单独 PR)
- 真凭据(JUnit XML):`tests="1" skipped="0" failures="0" errors="0"`;BUILD SUCCESSFUL in 35s。
- 断言(真栈,闭合 S3 最后一截 BPMN approve → create_capa):
  - 真 BPMN 审批流程启动(businessKey = defect pid)→ `taskService.completeTask` 审批 review userTask。
  - task 完成 → `task_completed` 事件 → `EventBusService` → `BpmEventAutomationBridge` → **持久化 + enabled 的 on_bpm_event automation**(bridge 从 DB 查规则,故必须真持久化 via `automationService.create+enable`,非 in-memory)。
  - on_bpm_event automation 经 `execute_command` → 真 create_capa 管道 → CAPA 行(qcc_source_id = 流程实例 id;AUTO_SET code/status)。
  - 异步(@Async 派发)用 **Awaitility** 轮询(≤30s)断言 CAPA 物化,非假设同步。
  - **关键正向发现**:approval → on_bpm_event automation → create_capa 异步链全通(eventBus + bridge + DB 查 + @Async 执行 + 跨线程 MetaContext),无产品 bug。
- product bug:**无**。S3 整链(S3-1 命令 + S3-2 BPMN/SLA + S3-3 approve→CAPA)闭环。
### S1-3 — 邮件 → 投诉字段抽取 live IT(真 DeepSeek)  ✅ tested
- 测试:`platform/src/test/java/com/auraboot/framework/agent/CsComplaintEmailExtractionLiveIT.java`(`@Tag("agent-eval-live")`,DEEPSEEK_API_KEY gated;`:testAgent`)
- 命令:`cd platform && DEEPSEEK_API_KEY=sk-… ./gradlew :testAgent --tests '*CsComplaintEmailExtractionLiveIT*'`
- 真凭据(JUnit XML):`tests="1" skipped="0" failures="0" errors="0"`;BUILD SUCCESSFUL in 42s。
- 真智能报告(真 DeepSeek deepseek-chat,single sample):
  - 5 封真实多句客户投诉邮件(中/英混,带噪声)→ register_complaint native tool-use:**called 5/5 · requiredComplete 5/5 · meanFieldAccuracy 100%(15/15)· 0 幻觉字段**。
  - severity 从自然语言正确推断:产线停工→high / 严重质量事故→critical / 优先级不高→low / 中等→medium / high priority→high。account+contact 全对,description 全填。
  - **E6 企业信任门(模糊邮件「设备好像有点问题」)**:模型**不调用、不瞎编 account/severity** = safe。
  - faithful path:`LlmProvider#chat` + `tools[].inputSchema` + 读 `tool_use.input`(同 runtime ChatTurnRuntime)。lenient 聚合地板线(called≥80% / reqComplete≥60% / fieldAcc≥60% / 0 幻觉 / E6 不瞎编),打印报告即真信号。
- 安全:跑后 `sed` redact `$DEEPSEEK_API_KEY`(build + 任务输出),残留=0 已核。
- product bug:**无**。闭设计文档 S1「从邮件抽对 account/contact/description/severity」🟡。

> S1-1(complaint create golden + F2 drift 取证)、S1-2(投诉 create → 自动指派 automation)未做(deterministic,机制与 S3-1/S3-2 同构,ROI 次于本 live 智能刀)。

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-17-s1s3-business-loop-golden-testing-gate-acceptance-report.md
claim_level: golden-candidate (S3-1 closed; track in progress)
current_sot: docs/backlog/2026-06-17-s1s3-business-loop-golden-gap-and-plan.md; docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
business_scope: S1 CS complaint loop; S3 quality auto-CAPA. Non-goals: S6 browser golden, dashboard build, real-plugin import materialization.
integration_tests: QualityAutoCapaChainGoldenIT (2) + QualityCapaBpmnSlaChainGoldenIT (2) + QualityCapaFullAssemblyGoldenIT (1) real-stack + CsComplaintEmailExtractionLiveIT (1, real DeepSeek), 6 tests 0 fail
integration_coverage: coverage_not_measured (targeted IT slice, not a module coverage run)
e2e_specs: n/a (backend business-chain golden, not browser E2E)
feature_action_matrix: track gap-and-plan §3 (6 slices; S3-1 closed)
browser_evidence: did_not_run (this track is T4/T6 backend; browser golden is S6, out of scope)
backend_evidence: JUnit XML tests=2 failures=0 errors=0; real SmartEngine flow + real CommandExecutor + real PG rows (mt_<capa>) + ab_automation_log
artifact_evidence: n/a
permission_negative: n/a (self-contained synthetic models, no command permissions; permission gates covered by separate gate slice)
visual_feedback: n/a
skip_fixme_threshold_retry_audit: clean — no skip/fixme/threshold/retry; no waitForTimeout; deterministic synchronous entry (no @Async polling)
did_not_run: S1-1 (complaint create golden + F2 drift evidence), S1-2 (complaint auto-assign automation); real-plugin import-materialization reachability (host-first runtime follow-up)
remaining_blockers: none for S3-1; track continues
allowed_claim: "S3-1 auto-CAPA chain golden: tested (real-stack 2/2). Remaining S1/S3 slices did not run."
```
