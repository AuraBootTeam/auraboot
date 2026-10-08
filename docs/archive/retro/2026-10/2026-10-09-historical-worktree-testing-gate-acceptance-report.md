---
type: retro
status: closed
created: 2026-10-09
distilled_to:
  - docs/system-reference/external-event-automation-trigger.md
---

# 历史目录漏项定向验收记录

allowed_claim: 定向数据库迁移验证；不宣称事件 HTTP/下游执行验收。browser E2E did_not_run (executed=0)。

范围：历史 Open Platform 目录中遗漏的 external_event CHECK 迁移。仅选择遗漏增量；历史分支的 UI、依赖、权限或快照回退不合入。

实际验证：main 基线完整迁移 118 条；新增迁移升级 1 条；真实 ab_automation 写入 9 种合法值并拒绝 unknown_event；版本 floor 测试 1 项。

文档核验：全仓 strict 检查仍有 1 个既有错误：docs/system-reference/2026-10-02-oss-gate-handover.md 缺 frontmatter。本次两份文档的 scoped strict 检查通过；全仓治理不宣称通过。

## SOT Updates

- `docs/system-reference/external-event-automation-trigger.md`：当前约束/配置契约与验证边界。

## Final Evidence Pack

```text
acceptance_report: docs/archive/retro/2026-10/2026-10-09-historical-worktree-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: docs/system-reference/external-event-automation-trigger.md
business_scope: 历史 Open Platform 目录中遗漏的 external_event CHECK 迁移
integration_tests: PostgreSQL 实表约束；升级迁移
integration_coverage: coverage_not_measured
e2e_specs: did_not_run（无 UI 修改）
e2e_collection: 0
e2e_execution: 0
feature_action_matrix: 本范围为单一数据库约束/环境样例，无 UI 行动点
browser_evidence: did_not_run
backend_evidence: workspace .workspace/historical-worktree-closeout-{baseline,red,upgrade,green}.log；固定提交后再次核验
artifact_evidence: 迁移生成快照，仅保存在 workspace 证据，不手改跟踪 snapshot
permission_negative: did_not_run（未修改权限）
visual_feedback: did_not_run（未修改 UI）
skip_fixme_threshold_retry_audit: 无新增 skip、retry 或阈值修改；旧基线实际拒绝 external_event，修复后非法类型仍拒绝
did_not_run: HTTP 事件入口、下游自动化、浏览器、生产部署、自托管 Linux CI
remaining_blockers: 无本次源码合并阻塞；非完整产品验收
allowed_claim: 定向数据库迁移验证；不宣称事件 HTTP/下游执行验收
```
