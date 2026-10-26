---
type: retro
status: active
created: 2026-10-27
---

# 模型空命令写入白名单定向验收

allowed_claim：源码 producer `ded2865bb5a234b8309c1aeae5b9aa4e8d45c21f` 的18项hermetic契约检查通过；已构建配套runtime，产品原生消费、浏览器及全项目验收未完成。browser E2E did_not_run (executed=0)。契约归[模型写入策略](../../system-reference/model-mutation-policy.md)。

明确空数组表示该操作没有任何合法写入命令，不能用不存在的哨兵命令表达禁止删除。三个键仍全部必需，非数组、非字符串／空白／null元素和额外键仍按畸形策略拒绝。命令范围和精确命令身份同时必需；不可变约束仍独立生效，同事务insert completion不能越过空update白名单。

初始9a1d2a2执行18项、17通过／1失败：有ALL范围但无command code时触发原有List.of.contains(null)空指针。ded2865增加显式null拒绝，保留首轮失败XML及日志；不是环境失败，也未放宽测试。修复后18项通过，无失败／错误／跳过。

反例使用此前已准入49b5 runtime内真实ModelMutationGuard.class，test-only类路径替换且类加载日志核对来源。两个新增行为用例均因旧实现把空数组判为Invalid policy而失败；不是编译错误。移除baseline后重跑18项通过并构建boot。未修改生产源码／构件制造反例。详情和摘要见[机器证据](empty-command-writer-evidence.json)，原件保存在受管maritime-parallel-core/empty-command-writers。

新boot SHA256为af40266d1f1899eb830b20d5554817661768738d5436363bf05b37f900f97f57；与49b5相比仅内部AuraBoot库中的ModelMutationGuard一个class变化，其他库相同。embedded／standalone API仍为66827430dd40e4a7004dea856bb262ed4e8037ce8d51c0d705e7216edebdd099。该构件已供环境K原稳定runtime做消费验证，不能提前写成导入／HTTP已通过。

```text
acceptance_report: this document
claim_level: targeted hermetic contracts and built artifact
current_sot: docs/system-reference/model-mutation-policy.md
business_scope: explicit model-wide create/update/delete command writer allowlists
integration_tests: did_not_run for new runtime; K native consumer pending
integration_coverage: coverage_not_measured for native integration
e2e_specs: no UI changed
e2e_collection: 0
e2e_execution: 0
feature_action_matrix: full maritime denominator unchanged; this is a platform guard contract
browser_evidence: did_not_run
backend_evidence: 18 executed / 18 passed / 0 failed / 0 errors / 0 skipped; prior bytecode 2 expected failures; restored 18 passed
artifact_evidence: clean ded2865 boot; exact one-class delta; API unchanged
permission_negative: exact command, null identity, ALL-only, readonly, insert-completion and malformed-policy hermetic refusals
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no skips, no retries, assertions preserved; initial real failure retained
did_not_run: native HTTP/PG permission consumer, UI, devices, deployment
remaining_blockers: product configuration import and actual command persistence need new-runtime evidence
allowed_claim: targeted hermetic pass and paired artifact built; not full product acceptance
```

SOT Updates：模型策略新增空数组deny-all语义，保留命令身份／不可变／畸形策略约束；产品进度仍只由Anchorage交付摘要维护。
