---
type: retro
status: closed
created: 2026-10-15
distilled_to: /Users/ghj/work/auraboot/aura-bpm/docs/README.md
---

# S5 既有 BPM 设计器 JSON 往返候选

allowed_claim: browser E2E did_not_run（executed=0）；仅已收集 1 条候选测试。没有新增 BPM runtime 能力或页面，也没有修改 BPM 产品仓。

```text
acceptance_report: this file
claim_level: plan-only executable candidate
current_sot: aura-bpm/docs/README.md; packages/aura-bpm-web/auraboot.contribution.json; plugin/config/pages.json; test-fixtures/workflow-demo/config/processes.json
business_scope: existing BPM designer JSON import/refine/save/reopen/export/copy; no deployment/rule/SLA/business execution
integration_tests: did_not_run
integration_coverage: coverage_not_measured
e2e_specs: web-admin/tests/e2e/designer/customer-bpm-roundtrip.spec.ts
e2e_collection: 1 test collected with PW_PROFILE=full --project=chromium --no-deps --list
e2e_execution: executed=0; did_not_run pending root Enterprise runtime
feature_action_matrix: menu/create, real fixture import, process title and existing manager-node edit, save dialog and POST payload, backend readback, menu-driven reopen, parsed export semantics, invalid file no graph mutation, identity-stripped copy import/save/reload, original record unchanged; all execution pending
browser_evidence: none; screenshot and actual file attachments declared in candidate
backend_evidence: source contracts read only; no backend requests executed
artifact_evidence: fixture source path/hash attached at execution; actual downloads pending
permission_negative: did_not_run; this slice adds no roles or permissions
visual_feedback: candidate screenshots declared; original screenshot review did_not_run
skip_fixme_threshold_retry_audit: no mocks, retries, skips, internal-store mutation, API save fallback or afterAll deletion
remaining_blockers: runtime-bound AURA_BPM_PROJECT_ROOT; menu/permission import; real browser and saved graph readback; screenshots
allowed_claim: collected candidate only
```

测试入口固定为 `tests/e2e/designer/customer-bpm-roundtrip.spec.ts`，供主会话单一 catalog 登记。执行时必须设置 `AURA_BPM_PROJECT_ROOT` 指向 **runtime 已冻结的 aura-bpm checkout**；无 canonical 浮动路径 fallback。候选从该目录读取真实贡献清单和流程夹具，记录夹具 SHA256，并复用 `wd_leave_approval` 的 13 节点、13 连线设计图。仅给新草稿指定唯一 key/name，不启动该请假流程，也不宣称客户审批业务已经实现。

已读源码的 BPM 基线是 `67287e8c808064c27dc8add593dc6a4dbf13190c`。真实注册入口是 `/bpm/designer`；菜单为流程管理→流程定义→新建。持久化入口是 `POST /api/bpm/process-definitions`，响应 PID 来自 `data.pid`，设计图读回 `data.designerJson`。导出是 `${processKey}.json`，不是 XML。

导出对象包含已保存 identity，复制测试明确剥离 `id` 后再导入，避免将“副本”误保存到原定义；保存两个新草稿后反查原名称未变。无效文件用当前导入器明确拒绝的非法结构，校验错误反馈及导出图未改变。当前非法结构错误为英文源码字串，中文视觉验收时应单独记录，不将功能拒绝等同于错误提示体验通过。

命令（仅收集，不启动服务）：

```bash
cd web-admin
PW_PROFILE=full ./node_modules/.bin/playwright test tests/e2e/designer/customer-bpm-roundtrip.spec.ts --project=chromium --no-deps --list
```
