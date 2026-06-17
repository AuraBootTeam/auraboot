---
type: retro
status: active
created: 2026-06-17
---

# 验收报告 — S1/S3 业务闭环金标(agent quality campaign P1 轨道)

> testing-gate §7 验收报告。本轨道的最终测试声明以本报告为准(非聊天总结/通过计数)。逐切片更新。

`allowed_claim`: **S3-1(自动 CAPA 链核心)= golden tested(真栈,2/2,0 fail);其余切片 did-not-run / planned。整轨道未完成。**

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

### S3-2 — automation → BPMN startProcess + userTask → SLA 激活 + 升级  🔄 next(已 ground-truth 可行)
### S3-3 — 全链组装(defect → automation → BPMN approve → create_capa)  ⬜ planned
### S1-1 — crm:create_complaint 真栈命令 golden + F2 drift 记录  ⬜ planned
### S1-2 — 投诉 create → automation 自动指派 + log  ⬜ planned
### S1-3 — 邮件 → 投诉字段抽取 live IT(真 DeepSeek)  ⬜ planned

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-17-s1s3-business-loop-golden-testing-gate-acceptance-report.md
claim_level: golden-candidate (S3-1 closed; track in progress)
current_sot: docs/backlog/2026-06-17-s1s3-business-loop-golden-gap-and-plan.md; docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
business_scope: S1 CS complaint loop; S3 quality auto-CAPA. Non-goals: S6 browser golden, dashboard build, real-plugin import materialization.
integration_tests: QualityAutoCapaChainGoldenIT (2 tests, real-stack)
integration_coverage: coverage_not_measured (targeted IT slice, not a module coverage run)
e2e_specs: n/a (backend business-chain golden, not browser E2E)
feature_action_matrix: track gap-and-plan §3 (6 slices; S3-1 closed)
browser_evidence: did_not_run (this track is T4/T6 backend; browser golden is S6, out of scope)
backend_evidence: JUnit XML tests=2 failures=0 errors=0; real SmartEngine flow + real CommandExecutor + real PG rows (mt_<capa>) + ab_automation_log
artifact_evidence: n/a
permission_negative: n/a (self-contained synthetic models, no command permissions; permission gates covered by separate gate slice)
visual_feedback: n/a
skip_fixme_threshold_retry_audit: clean — no skip/fixme/threshold/retry; no waitForTimeout; deterministic synchronous entry (no @Async polling)
did_not_run: S3-2, S3-3, S1-1, S1-2, S1-3; real-plugin import-materialization reachability (host-first runtime follow-up)
remaining_blockers: none for S3-1; track continues
allowed_claim: "S3-1 auto-CAPA chain golden: tested (real-stack 2/2). Remaining S1/S3 slices did not run."
```
