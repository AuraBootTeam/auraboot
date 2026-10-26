---
type: retro
status: active
created: 2026-10-27
---

# 应用子模块命令注册定向验证

allowed_claim：4c76751a72e91a62f159efb48afd28d2fc009c61组件的10项hermetic检查通过、旧真实module bytecode反例1项期望失败，恢复10项通过并构建boot。browser E2E did_not_run (executed=0)。新的native产品消费尚未验证。稳定契约见[模块命令生命周期](../../system-reference/application-module-command-lifecycle.md)。

K真实cold-start后公开导入发现7项S-EXT-HANDLER：Bridge先运行，子模块后加载。模块启动日志与运行时动态dispatch存在，不能证明import metadata已登记。修复在全部子模块进入live map后、释放module monitor再同步通知host bridge，卸载则仅移除bridge自己仍持有的metadata，保留builtin及新owner。未把handler重复PF4J索引或跳过语义校验。

测试采用真实Spring父／子context和真实registry，manager发现依赖mock；覆盖late-registration、重复register、unload/reload、builtins和新owner保护及原actions。共10项，无失败／error／skip。实际此前ded2865 boot内ApplicationModuleRegistry.class通过test-only classpath加载，新增late-registration用例因import metadata仍不存在而语义失败；类加载来源核验，生产源码和JAR未变异。移除baseline后10项恢复通过。

新boot SHA1c6882bb08c9db36b811b10c7bad6e4c876ae32f052540b57287ed41e4d71500；API仍66827430dd40e4a7004dea856bb262ed4e8037ce8d51c0d705e7216edebdd099。相对ded只有内部AuraBoot库变化，3个主要class和4个相关record／内部class变化，其他库未变。组件[摘要](module-import-bridge-evidence.json)保留producer，受管原件maritime-parallel-core/module-import-bridge。已给K确切候选，但不提前称真实导入通过。

```text
acceptance_report: this document
claim_level: targeted hermetic checks and paired boot built
current_sot: docs/system-reference/application-module-command-lifecycle.md
business_scope: live module-owned command dispatch/import metadata synchronization
integration_tests: native consumer pending; Spring context hermetic checks only
integration_coverage: coverage_not_measured
e2e_specs: none; no UI changes
e2e_collection: browser0
e2e_execution: browser0
feature_action_matrix: platform seam only; full maritime scope unchanged
browser_evidence: did_not_run
backend_evidence: 10 executed/passed; old bytecode1 expected semantic failure; restored10 passed
artifact_evidence: exact boot/API and bounded nested-library delta
permission_negative: builtin/new-owner retained; semantic registry does not grant command permission
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: zero skips/retries; prior class-load origin proven
did_not_run: actual native product parse/import and authorized HTTP, real devices/UI/deployment
remaining_blockers: consumer native requalification and fresh environment migration preflight
allowed_claim: component targeted pass only
```

SOT Updates：新增模块命令生命周期SoT与目录入口；产品完整进度仍归Anchorage交付摘要。
