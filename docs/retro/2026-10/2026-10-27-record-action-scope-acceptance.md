---
type: retro
status: active
created: 2026-10-27
---

# 命令对象动作范围定向验收

browser E2E did_not_run (executed=0)。allowed_claim：Core公开契约／服务的定向验证；不能据此宣布Anchorage资源、申请、父对象创建门禁或严格高保真已验收。行为权威为[命令事务契约](../../system-reference/command-transaction-isolation.md#explicit-record-action-scopes)，[机器证据](record-action-scope-evidence.json)包含逐case、日志与JUnit摘要、反向回归和配套构件指纹。

同一任务maritime-parallel-integration、唯一Core实现树、分支codex/maritime-parallel-integration；代码／测试／构件producer `88d3ea33eb060f80bab1d90127708bdc98f60f93`。组件runtime沿用maritime-parallel-core／slot26、实际PG17.6数据库auraboot_26，不是产品第三个业务库。Flyway显式验证156项既有Core迁移，新增0；夹具模型通过元数据mapper和SchemaManagementService创建，本轮独有模型／字段和业务行保留，未手工repair环境或改其他owner数据。应用服务未常驻。

| 四轴／类 | collected / executed / passed | failure / error / skip |
|---|---|---|
| contract / hermetic / blocking-commit / unit；新RecordActionScopeAccessorTest | 3 / 3 / 3 | 0 / 0 / 0 |
| service / hermetic / blocking-commit / unit；新FactoryTest | 21 / 21 / 21 | 0 / 0 / 0 |
| service / hermetic / blocking-commit / unit；CommandTargetScopePhaseTest | 35 / 35 / 35 | 0 / 0 / 0 |
| service / hermetic / blocking-commit / unit；CommandInvocationAuthorizationPhaseTest | 8 / 8 / 8 | 0 / 0 / 0 |
| contract、service / hermetic / blocking-commit / unit；既有身份锁与公告四类回归 | 55 / 55 / 55 | 0 / 0 / 0 |
| service / real-stack / blocking-commit / it；CommandRecordActionScopeCapabilityIntegrationTest | 6 / 6 / 6 | 0 / 0 / 0 |

122项hermetic及6项真实PG来自本producer实际执行的原始XML；没有从collection、编译、旧测试或其他窗口推导。真实PG正反例覆盖read all／update none、update all但无generic model update能力、read共享与update共享分别生效、同请求授权撤销后拒绝、旧事务内读到独立提交的scope变化、handler bridge在原事务／REQUIRES_NEW／后续事务的生命周期，以及数字别名／删除／actor租户变化。真实服务测试显式构造已授权命令上下文，不是实际HTTP命令功能授权或浏览器旅程；实际缓存和规则／共享服务未mock。

四条旧实现反向回归在 `e29bbd12de4ea83a7e88ba5d9ed9e9dc4b416a2c` 产生4个预期failure、error0，分别覆盖读范围误当写范围、不可用scope、read共享升级update、update共享升级delete。修复后同语义断言转绿；不可用scope夹具后补真实MetaContext以明确裁决该分支。早期编译失败分别为handler私有方法缺pipeline上下文、夹具误用不可变上下文setter／不存在的command字段，均保留原日志；其未执行测试不计通过。首个完整单元轮还暴露一条未初始化身份夹具，修正后重跑122项。无skip、重试或降低断言门槛。

配套boot/API已构建；boot内API与独立API逐字节相同，新interface及两个getter和旧八参构造均由javap核对。boot内5个变更host class与本轮compiled class逐字节相同。runtime SHA `f9afa1d3d41bf1ddcbab6b0e2e0cab5b14cc06619304eb9b232e3b4347c7601c`；API SHA `66827430dd40e4a7004dea856bb262ed4e8037ce8d51c0d705e7216edebdd099`。后续文档提交不重标二进制producer。

SOT Updates：实际target action检查、共享不可升级、scalar SPI／身份和事务生命周期／创建引用对象准入已进入命令事务契约。旧身份锁及公告证明职责保持独立；本轮未创建新权限表、船舶绑定表或审批引擎。Anchorage catalog／产品构件与D/E实际命令消费仍须独立准入和验证，不能由本组件结果关闭R-D10。未合main、未发布生产。

```text
acceptance_report: docs/retro/2026-10/2026-10-27-record-action-scope-acceptance.md
claim_level: targeted-tested
current_sot: docs/system-reference/command-transaction-isolation.md
business_scope: human command record action scopes and bounded scalar host capability
integration_tests: 6 real-PG service cases; 122 hermetic contract/service cases
integration_coverage: coverage_not_measured
e2e_specs: did_not_run
e2e_collection: 0 browser
e2e_execution: 0 browser
feature_action_matrix: component case names in linked evidence; product denominator remains open
browser_evidence: did_not_run
backend_evidence: linked original JUnit and logs with SHA256
artifact_evidence: paired boot/API and exact nested-class identity
permission_negative: actual read/update/delete scopes, sharing, revocation and lifecycle
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: 0 skipped; no retries or threshold reduction
did_not_run: product native HTTP commands, browser, real devices, deployment
remaining_blockers: consumer admission and actual resource/dispatch journeys; global project backlog unchanged
allowed_claim: targeted Core component verification only
```
