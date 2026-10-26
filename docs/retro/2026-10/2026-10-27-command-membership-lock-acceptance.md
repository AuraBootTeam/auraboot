---
type: retro
status: active
created: 2026-10-27
---

# Core命令身份锁与公告组合定向验收

browser E2E did_not_run (executed=0)。本轮是Core组件的公开SPI、真实PG事务和构件验证，不是船舶绑定／申请／资源页面的最终验收。公开契约只维护于[命令事务](../../system-reference/command-transaction-isolation.md#exact-tenant-identity-locks)，[机器证据](command-membership-lock-evidence.json)保留逐case名称、JUnit／日志SHA、构件与受控反例；全项目待办仍归Anchorage交付摘要。

## 版本、环境与执行

任务沿用maritime-parallel-integration的唯一Core树，branch codex/maritime-parallel-integration，原base87d2cc。新实现首版4575ba722，最终代码／测试／配套构件producer31caa1b0b728ec787b1d4182c92c6cf513d49eff；后续文档提交不重标binary producer。稳定组件runtime maritime-parallel-core／slot26／PG auraboot_26，source-set71105dff321d11ebc449e8db814b0d81ab4908a40c2b863391f4ae1769316b5a，实际runtime verify通过、无常驻app。独立component数据库不是产品第三个领域库。未重置原库、未安装本机容器、未改原公告工作树／历史证据。

| 四轴／类 | collected / executed / passed | fail / error / skip |
|---|---|---|
| contract / hermetic / blocking-commit / unit；CommandMembershipLockAccessorTest | 3 / 3 / 3 | 0 / 0 / 0 |
| contract / hermetic / blocking-commit / unit；AnnouncementAcknowledgementAccessorTest | 4 / 4 / 4 | 0 / 0 / 0 |
| service / hermetic / blocking-commit / unit；CommandMembershipLockAccessorImplTest | 21 / 21 / 21 | 0 / 0 / 0 |
| service / hermetic / blocking-commit / unit；AnnouncementAcknowledgementAccessorImplTest | 27 / 27 / 27 | 0 / 0 / 0 |
| service / real-stack / blocking-commit / it；CommandMembershipLockCapabilityIntegrationTest | 13 / 13 / 13 | 0 / 0 / 0 |
| service / real-stack / blocking-commit / it；AnnouncementAcknowledgementCapabilityIntegrationTest | 7 / 7 / 7 | 0 / 0 / 0 |

以上55 hermetic与20真实PG均在31caa实际执行，XML单独按目标类复制，对账参数实例；不是从旧XML或注册数量推导。早期457的55项另保留，不混为一次新执行。Core/TestApplication真实Spring、MyBatis、数据源与PG17；无HTTP、完整RBAC、浏览器或设备声明。Flyway沿用宿主官方显式迁移入口，没有手工建表／repair。

## 已验证的边界

- 当前认证actor必须在1–32完整精确user/member batch中，附加接收者只限当前tenant；重复、旧成员、错误actor、其他tenant／物理datasource、无事务／只读／无同步均拒绝。目录身份不是船舶授权。
- Java层严格按全部user numeric ID、再全部member numeric ID锁定，返回保持输入顺序。真实PG比对字段／updated_at不因持锁改变；同一事务subset复用，不允许后加用户倒置锁顺序。
- 使用真实UserService.update、TenantMemberService.deactivateMember/removeMember/addMember并发写：通过pg_blocking_pids确认实际数据库等待，原事务结束才完成。覆盖当前actor、接收者禁用、成员停用及删除重入；旧memberPID不能转移到新incarnation。
- 在调用者同一事务中禁用actor／接收者、停用成员、写入leave_date或删除重入，beforeCommit全部拒绝并回滚；回滚后原字段、状态与原成员仍准确可锁。
- 同一Core REQUIRES_NEW用独立连接／synchronization的proof，返回后原事务proof正确恢复；不把独立提交称业务原子性。公告原6项PG回归与新增共享锁subset组合均通过。公告仍只证明当前actor，审核员不能冒充申请人。

## 受控反例与构件

仅将本树测试用已编译Proof.beforeCommit五字节方法体临时改为return，不改源码或其他class。八个同事务变更用例全部产生预期失败，errors0；finally逐字节恢复原class，再实际执行55项绿色unit和配套boot/API构建。boot内Proof摘要等于恢复后的原class，外部API等于boot内API，新interface与getter均存在；没有把变异包交给产品。反例原始XML／日志和class前后SHA已保留。

准备反例时，辅助classpath任务未注册而失败，测试执行0；随后采用上述精确compiled-only反例。此失败不算业务通过，也未修改Workspace Gradle入口。报告附原始退出和最终执行，不用skip／retry／放宽阈值掩盖。

Core boot SHA988ff63e3f86d92adf83358737a49470987638377684b7a12867aece5ee858a6，API SHAd7c2abc003f430bbf60f45c04ec3715fbb65c2b39509f05a645dcc1c006ee543。Plugin API与宿主源码的既有CommandHandlerExtension副本有历史差异，本轮只核验新interface与新增getter镜像一致，未伪称整个历史文件逐字节相同。

## 未完成与同步

Anchorage平台catalog／lock与运行包仍需显式准入本producer；A/E/F必须在其真实命令中正确消费，并复验用户禁用并发、对象scope与幂等重放，不从本组件测试推导产品已完成。角色授权变更、资源安全、审批原申请人再验证、严格高保真、现场设备及部署交接均不由本SPI代替。

SOT Updates：共享身份锁顺序／batch／物理事务／提交复验已进入command-transaction-isolation；公告契约链接到该能力。未创建身份表、公告确认表或新BPM引擎。PR仍按实际head审阅，未合main、未发布生产。
