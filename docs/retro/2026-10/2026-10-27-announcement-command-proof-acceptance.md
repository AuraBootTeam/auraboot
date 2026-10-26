---
type: retro
status: active
created: 2026-10-27
---

# 业务命令的指定版本知悉证明：定向验证

allowed_claim: targeted-tested；browser E2E did_not_run (executed=0)。当前仅证明 Core 公开能力的服务／事务行为，不证明 Anchorage 申请／船方菜单旅程已接通。

契约见[公告管理§19](../../system-reference/announcement-management.md#19-command-scoped-exact-version-acknowledgement-proof)。完整病例、原始JUnit摘要、运行身份、失败历史及构件见[机器证据](announcement-command-proof-evidence.json)。仅复用官方公告版本、受众与知悉事实，不建平行确认表，不修改 BPM 引擎或流程政策。

## 身份与真实执行

任务沿用 maritime-parallel-integration，新增实际修改的 OSS checkout：`.workspace/worktrees/maritime-parallel-integration/auraboot`，分支 codex/maritime-parallel-integration，冻结 base87d2cc。独立 Core provider 栈 maritime-parallel-core／slot26、DB auraboot_26，明确登记独立栈原因；Anchorage产品slot17保持暂停，其私有设置与业务状态不借用。本库不是生产架构中的第三个业务数据库。无本机容器、生产动作或原公告树改写。

最终真实PG和构件 producer 为65eaa208431094adaf70356f67683dd4fde91c6a，source-set 556faead17d675497bfc78672527b735d1d8d1cc0349a20020ec50f929057c0f。宿主官方 Flyway CLI 在本任务空库执行已冻结 Core 迁移，重跑保持历史；没有手工建表或repair。SQL字段另与本会话自有slot17既有Core实际schema核对，仅取列定义，无业务数据或凭据输出。

| 四轴／命令 | 实际执行／通过 | 失败／跳过 | 来源与边界 |
|---|---:|---:|---|
| contract／hermetic／blocking-commit／unit；API test | 4／4 | 0／0 | producer cac499fb2；上下文缺bridge、伪造Map、PID／hash和SATISFIED不变量 |
| service／hermetic／blocking-commit／unit；root test | 27／27 | 0／0 | 同一cac producer；认证、事务、成员、版本、可见性、知悉、批量锁序、提交再校验 |
| service／real-stack／blocking-commit／it；app test | 6／6 | 0／0 | producer65eaa；实际Spring/MyBatis/PG，精确DB断言，不是HTTP或浏览器 |
| browser／产品命令／现场 | 0／0 | 未执行 | 保留为产品接线与最终验收，不与上述相加冒充全覆盖 |

31项hermetic没有重标为65eaa的一次新执行：API／service／相应用例源字节与cac冻结版本逐一一致，复用证明单列。Mapper SQL和IT发生变化，真实PG六例在65eaa重新执行。代码覆盖率未测量。

## 已证明的行为

1. 未知悉、仅阅读均不能通过；只有官方read／acknowledge写入的当前成员、指定生效版本和同一hash形成SATISFIED。没有事务时实际MANDATORY代理拒绝。
2. 当前业务事务拿到证明后调用官方撤回，提交再校验拒绝，并回滚撤回／版本变更；正常版本及知悉事实仍保留。
3. 在本测试版本控制行注入过期后，提交拒绝并回滚；没有用固定等待或测试时间伪装生产时钟。
4. 原成员离开／重新加入后，旧知悉不转给新incarnation；旧成员要求被拒绝，新成员没有旧封存受众资格。该测试变更只在本测试事务内回滚。
5. 并发官方撤回在真实PG阻塞；通过pg_blocking_pids确认实际等待，当前证明事务结束后才完成，后续证明明确WITHDRAWN。不是仅观察线程未结束。
6. 两个必读版本的返回顺序和证明均准确；其中任一个在同事务失效，整批证明阻止提交，两个版本恢复为原状态。

Root与用户／成员锁保持到外层事务结束；最终再次验证当前成员、根／版本、可见性、真实知悉和数据库时间，涵盖调用者自己的事务内修改。不能承诺数据库提交检查之后的绝对墙钟期限，也不能把过程通过当成船舶资格、调度安全或实际占用。

## 失败与更正保留

- 首次把library单测放到app task，产生No matching tests；随后使用正确root task，没有计作通过。
- 早期服务24项中缺失MetaContext产生错误类型，实际23通过／1失败；补认证前置检查后28项定向通过。
- 新增三个同事务失效反例在旧服务实际全失败；补完整提交再校验后通过，不以单次初始SATISFIED放行。
- 第一轮真PG五例均被MyBatis解析器拒绝FOR SHARE OF；改用框架接受的FOR UPDATE，仍持用户／成员锁，未关闭拦截器或放松租户条件。
- 下一轮真PG4／5通过；未读受众的三个可空投影让MyBatis将真实行映射为null。增加非空版本身份投影，未改全局null映射配置；最终六例正常通过。

原始退出码、失败JUnit和日志保留在受管evidence；没有retry、skip、放宽阈值或吞异常。报告不把这些失败改写成一次全绿。

## 构件与剩余边界

已从冻结65eaa构建Core boot与Plugin API；外部API JAR与boot内嵌API逐字节一致，新增provider、提交校验与Mapper类实际存在。完整摘要在机器证据，不把后续文档提交当binary producer。尚未升级Anchorage platform.lock，也未在申请／船方运行栈调用新桥。

桥只证明当前认证actor；审核员不能用自己的知悉替代原申请人。后续审核／分配对原申请人的再验证须在领域契约中明确，不能冒充其MetaContext或读Core内部表。消费者须使用包含该API的配套准入runtime/API；nullable getter只描述当前上下文缺bridge，不证明不含此方法的旧宿主二进制兼容。

```text
acceptance_report: docs/retro/2026-10/2026-10-27-announcement-command-proof-acceptance.md
claim_level: targeted-tested
current_sot: docs/system-reference/announcement-management.md section19
business_scope: current-actor exact-version acknowledgement in caller transaction
integration_tests: 6 real PG service/transaction cases
integration_coverage: coverage_not_measured
e2e_specs: none
e2e_collection: 0
e2e_execution: 0
feature_action_matrix: exact API4/service27/PG6 case ledger; no product coverage claim
browser_evidence: did_not_run
backend_evidence: frozen source, actual PG, blocking-pid and rollback assertions
artifact_evidence: producer65eaa Core/API hashes and nested API equality
permission_negative: no-context, member incarnation, audience, snapshot and transaction refusal; no full product RBAC acceptance
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no selected skips, retries or relaxed thresholds; initial failures retained
did_not_run: product command HTTP journey, browser, staff recheck of other applicant, device, capacity, production
remaining_blockers: paired product admission, consumer command/UI integration, full product acceptance
allowed_claim: targeted current-actor provider; no full Anchorage delivery
```

SOT Updates：公开API、上下文安装与隔离、原公告事实和提交边界已同步公告契约§19；Anchorage全项目状态仍由其唯一交付摘要维护，不在本报告另建项目待办。
