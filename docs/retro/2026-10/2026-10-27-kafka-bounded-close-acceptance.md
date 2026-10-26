---
type: retro
status: active
created: 2026-10-27
---

# Kafka受限发送与可观测关闭候选

browser E2E did_not_run (executed=0)。allowed_claim：45项hermetic定向回归与配套构件资格；新候选真实broker／故障进程／完整native仍待B和Root验证，不能声明整个可靠链或生产关闭已验收。稳定API职责见[模块契约](../../../platform/platform-mq-kafka/README.md#explicit-producer-budgets-and-observed-cleanup)，[机器证据](kafka-bounded-close-evidence.json)保留准确producer及逐case。

沿用maritime-parallel-integration唯一Core树与PR#2441；代码／构件producer `f548ca48abb9de26ac22767919eb421fdbf7d3ca`，组件runtime maritime-parallel-core／slot26，无常驻app。14项新bounds、6项原provider、19项可靠消息、6项DLQ，共45项实际执行，failure／error／skip均0。未改身份锁、对象scope、公告事实或Plugin API；独立API与boot内API逐字节相同，Provider／ProducerTimeouts／CloseStatus class与compiled字节相同。boot SHA `ff16239649eb2fe1e0f7f8f3d91fbe2c8f7cc5cde690f245b3b47a57af0eac0e`，API SHA `66827430dd40e4a7004dea856bb262ed4e8037ce8d51c0d705e7216edebdd099`。

原Core88d真实探针已发现同步producer.send在metadata等待时2436ms仍不返回stage，且已中断destroy吞掉关闭错误。第一版typed预算／直接严格close的候选bae40d有44项mock与配套构建，但真实固定Kafka3.9.2的公开API回调探针显示close(200ms)实际2010ms且正常返回，明确拒绝晋升。Root独立javap确认forceClose后存在无参ioThread.join，以及remainingMs耗尽时join(0)风险；不能把传入Duration的mock当底层实际退出证明。

当前候选将SDK.close放在明确拥有、可观测的closer中，caller按整体deadline等待，超时／中断必失败，句柄与终态保留；不协作的SDK残留须由上层持续拥有并处置进程，不能藏进Future或报告成功。新增不响应interrupt的真实Java线程夹具验证caller不会无限等待、thread ID／存活状态可见，迟到退出不改变原失败；同时覆盖锁准入、原始与suppressed cause、中断保持、callback自关闭拒绝及原provider可靠性回归。真实SDK新候选探针仍待执行，未把旧反例改成通过。

初轮19项unit有两条夹具失败：activeSubscriptionCount只计running而非所有owned stopping线程，Kafka InterruptException的构造本身会设置构造线程中断；修正测试依据后复验44项，再因真实SDK反例修改实现并运行45项。原日志与反例原件保留，没有skip、放宽deadline或后台遗漏线程。Boot/API仅冻结到独立候选catalog，未替换仍保留的native703软件包。新broker、B正式provider与产品停止／恢复必须继续验证。

SOT Updates：typed预算、原构造边界、strict close／callback／诊断和失败资源职责进入模块README；没有新增通用MQ、设备协议或业务确认语义。未合main、未发布生产。

```text
acceptance_report: docs/retro/2026-10/2026-10-27-kafka-bounded-close-acceptance.md
claim_level: targeted-tested
current_sot: platform/platform-mq-kafka/README.md
business_scope: bounded producer admission and observable cleanup ownership
integration_tests: 45 hermetic; new real-broker execution pending
integration_coverage: coverage_not_measured
e2e_specs: did_not_run
e2e_collection: 0 browser
e2e_execution: 0 browser
feature_action_matrix: 45 component case names in linked evidence; product denominator remains open
browser_evidence: did_not_run
backend_evidence: old actual broker counterexamples; current hermetic lifecycle/thread assertions
artifact_evidence: paired boot/API and changed nested module class identity
permission_negative: not applicable to MQ capability; domain authorization unchanged
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no skipped cases or deadline relaxation
did_not_run: new real SDK/broker/process qualification, native lifecycle, browser, deployment
remaining_blockers: B real candidate qualification and Root product assembly
allowed_claim: targeted component candidate only
```
