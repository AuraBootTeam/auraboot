---
type: backlog
status: active
created: 2026-07-01
owner: diqi
---

# OSS 全仓深度 Code Review 计划(2026-07-01)

> 方法论 canonical:`auraboot-enterprise/docs/agent-rules/deep-review-strategy.md`(五维切片)+ `review-baseline.md`(verify-before-flag,历史 FP 30-50%,证据要求严格)。
> 承接上一轮 `2026-06-18-oss-deep-review-plan.md`(anchor `0de947e2e`,已修 10 条,留 backlog)。

## §0 执行前提

1. **目标输出形式**:`review → 直接修复`。verified P0/P1/P2 在隔离 worktree 修掉 + 补测试 + 跑门禁 + PR;按 `severity/effort` 排期。
2. **范围**:仅 OSS 仓(`/Users/ghj/work/auraboot/auraboot`)。三栈:Java(`platform/`+`plugins/`)+ DSL JSON + TS/TSX(`web-admin/app`)。Enterprise 仓不在范围(D4 仅 OSS-boundary lite)。
3. **快照基线**:
   - `review_anchor`: OSS `9ea109ad2`(origin/main HEAD @ 2026-07-01,worktree base)
   - `comparison_base`(D2 用):上一轮 anchor `0de947e2e`(2026-06-18)→ 本轮只看这 ~2 周新增 diff + chokepoint 不限时间窗
4. **并发上限**:read-only reviewer 放宽到 5 个并行;每个显式 `cd` 不互相 chdir。

## ⚠️ 并发会话隔离(§18/§20)

启动时 `git worktree list` 实测:**authz/permission/capability/tenant-controller 面正被 4 个并发会话改写**:

| 分支 | 范围(禁止我方重复/冲突) |
|---|---|
| `fix/tenant-mutation-authz-remaining` | perm-004 remaining:NotificationRuleController / RecordShareController / AnnouncementController 加 `@RequirePermission` + `controller-authz-baseline.json` + MetaPermission + default-bootstrap |
| `fix/rbac-platform-stats-agent-card-tenant-scope` | PlatformStatsController tenant scope |
| `codex/permission-ui-sort` | capability 系统 + DictController / TenantMember / TenantPreference 权限 + capability migration |
| `codex/platform-admin-enterprise-info-name` | 同上 capability + SystemPreferences + PermissionMatrix |

**结论**:authz/RequirePermission 存量分类(perm-004)、capability、tenant/permission controller 一律**避开**,只作 `candidate`(上下文)记录,不进本轮 fix。本轮 fix 聚焦其余维度。

## 维度切片与并行 reviewer(本轮)

| Reviewer | 维度 | 范围 | 重点 |
|---|---|---|---|
| R1 | D1 后端红线(非 authz) | `platform/src/main/java` 除 permission/rbac/tenant-controller/capability;+ `plugins/*/backend` | catch(Exception) 吞异常/rollback-only;自愈 ensure-fallback;启动期写库 @PostConstruct(§4.1);API 参数漂移;大小写比较;后端硬编码中文 |
| R2 | D3 核心链路 | L1 ConversationTurnService / L3 DynamicController·FieldMapPhase(type:delete 对称)/ L4 插件加载·bindingRules / jsonb typeHandler 读写 | 旁路 chokepoint;事务边界;jsonb 读写 PGobject;delete 路由对称 |
| R3 | D2 变更面 | `git log 0de947e2e..9ea109ad2` platform+plugins 高风险 commit(排除 authz churn)+ chokepoint | 回归;breaking change 漏 doc;对照 engineering-gotchas |
| R4 | D1 前端红线 | `web-admin/app` | i18n 硬编码中文(区分 LocalizedText);配置优先豁免;API 参数对齐;raw code 泄漏;catch 吞错;错误态 |
| R5 | D5 测试 gap(lite)+ 插件配置 | Service/Controller/页面 source-vs-test;`plugins/*/config` | new-surface 无测;commands.json inputFields=List<String>;bindingRules 独立文件;DSL 白名单;permission code 编造 |

## §5 合并工序 + 修复

并行回报 → coverage gate → 候选转交 → 去重 → 降级/撤销(未达 baseline 证据契约即撤)→ 编号 `DR-20260701-D<x>-<topic>-NNN` → 优先级重排 → anchor 快照。**每条进入修复的 P0/P1 主对话独立重跑核验。** 修复在 `fix/oss-deep-review-2026-07-01` worktree,每修配测试 + 跑本地门禁。

## Findings(§5 七步合并后,主对话逐条 verify-before-flag 核验)

> `review_anchor: OSS 9ea109ad2`。每条进入修复的 finding 主对话独立 read/grep/真栈测试核验,不信 subagent 自报。

### 已修复(本 PR,均真栈测试或 typecheck 验证)

| ID | sev | file | 问题 | 修复 | 验证 |
|---|---|---|---|---|---|
| DR-20260701-D1-case-001 | **P1** | `plugin/.../PluginPackageServiceImpl.java:1162` | `switch(manifestStrategy.toUpperCase())` 却用小写 case 标签 → manifest `conflictStrategy: skip/overwrite` 静默降级为 ERROR(插件安装 live 路径 installPackage) | 大写 case 标签 | `PluginPackageServiceImplBranchTest#resolveConflictStrategy` 5 例 PASS |
| DR-20260701-D1-case-002 | **P1** | `meta/.../ExportAsyncTaskExecutor.java:92` | 同模式 → `format=json` 导出静默产出 CSV(writeJson 死代码);`excel→CSV` 有意保留 | 大写 case 标签 | `ExportAsyncTaskExecutorTest` 3/3 PASS(json→json 真文件断言) |
| DR-20260701-D1-case-003 | P2 | `meta/entity/QueryOperator.java:146` | 同模式 → `supportsDataType` 所有分支不可达(潜伏,0 live 调用方) | 大写 case 标签 | `QueryOperatorTest` 5/5 PASS |
| DR-20260701-D3-loader-001 | P2(诊断) | `plugin/.../PluginDirectoryLoader.java` | 目录布局资源文件解析失败仅 `log.warn`(易漏,后续变 "Command not found") | warn→error + "skipping" 消息(**保留有意的每文件容错**) | `PluginDirectoryLoaderTest` 13/13 PASS |
| DR-20260701-D5-test-001..004 | P1 | `GenAiUsageController` / `CorrelationController`+`CorrelationQueryService` / `PermissionAuditRecordPidResolver` | new-surface 读路径无测试(§1) | 纯 Mockito 单测(MetaContext 静态上下文) | 7 测试 PASS |
| DR-20260701-D2-fe-001 | P3 | `InboxDropdown.tsx:114` / `InboxWidget.tsx:100` | `sourceRecordPid ?? sourceRecordPid ?? …` copy-paste 死 fallback | 删重复项 | typecheck PASS |
| DR-20260701-D1-i18n-001 | P1 | `tenant/TenantSelection.tsx`(18) / `routes/h5-scan.tsx`(~40) | 用户可见硬编码中文,无 useI18n(§3) | `t(key, undefined, '中文')`(TenantFormFields 模式,fallback 保留现有显示) | `react-router typegen && tsc` PASS |

**合计:11 处修复 + 18 测试新增/扩展;OSS 本地门禁全绿。**

### 重分类 / 驳回(verify-before-flag 生效)

- **R2-F1(PluginDirectoryLoader fail-loud)→ 降级**:reviewer 假设目录路径未测试,提议改 fail-loud。核验推翻——现有测试 `shouldSkipInvalidJsonAndLoadValidOnes` 明确断言目录布局坏文件应跳过、好文件正常加载,紧邻的 `shouldFailLoudWhenSingleResourceFileFailsToParse` 断言单文件严格 → **「单文件严格 / 目录每文件容错」是有意的、有测试的设计对比**。真正引发红线的 incident 是单文件 `commands.json`(已 fail-loud)。fail-loud 会打破现有测试 + 让一个坏辅助文件拖垮整个插件(韧性倒退)。仅保留零风险的日志级诊断改进。
- **R5 注入 candidate(`PermissionAuditRecordPidResolver:39` 拼表名)→ 驳回**:tableName 经 `SqlSafetyUtils.requireIdentifier()` 校验,值参走 `#{params.*}` 绑定,来源是 meta model 非用户输入。非注入。

### 延后(owner 决策 / 需更重验证 —— **非 completionism backlog**)

| ID | sev | 内容 | 为何延后(有价值但需 owner/重验) |
|---|---|---|---|
| DR-20260701-D1-bootstrap-002 | P1 | `SystemTaskInitializer` @PostConstruct insert 11 system task + `SkillBootstrapRunner` ApplicationRunner per-tenant upsert(§4.1 启动期写库) | **与既有平台 seeding 模式一致**:`PlatformSeedRunner`(@Order(1) 无 profile guard)每次启动跑 7 个 `.seed()`(SystemFieldSeeder 等 jdbc INSERT),是长期结构件;`schema.sql` 只有 `ab_scheduled_task` DDL 无 seed 路径。「修复」= 把 seed 迁进 reset/bootstrap 流 + fresh-stack 验证 task/skill 仍注册 → owner 级架构决策,贸然移除会破坏启动(丢 outbox 轮询等)。与上一轮(2026-06-18)同结论刻意延后。R1 亦把 PlatformSeedRunner 列为同类 candidate 待 owner。 |
| DR-20260701-D2-flyway-001 | P2 | baseline `V20260618000000` 被就地改(加 record_pid 列)→ 改了已应用 migration 的 checksum;forward migration(V20260622005000/V20260624021000)已覆盖同变更(冗余 + deploy checksum mismatch 风险,无 flyway repair 步骤) | 「修复」= revert 3 hunks + 重生 snapshot,**但依赖部署状态**(已应用新 baseline 的 staging 环境 revert 后会 checksum mismatch)+ **schema 文件正被并发会话 churn**(permission-ui-sort 加了 V20260627090000)。需 owner 定 baseline 是否 regenerable + 是否加 flyway repair 步骤。 |

### Candidates(上下文 / owner 判断,未升 finding)

- **R2-C1**:`CustomerServiceAgentListener` → `AgentRunService.executeTask`(非 `runTurn`)—— 疑似 by-design(邮件事件触发的后台 agent 任务,非用户对话回合,不 persist im_message/不发 SSE)。
- **R3-C1**:`AsyncTaskStartupRecoveryRunner` 无条件跨租户 `markRunningTasksFailedOnStartup` —— defensible crash-recovery,但若 async worker 横向扩容则 multi-instance-unsafe(新实例会把别的活实例的 running 任务标 failed)。
- **R1-cand**:`PlatformSeedRunner`(同启动期写,平台 reference seed);28 处后端 `throw new *Exception("中文")` —— §3 强制目标是 UI 文本非服务端异常消息(catch-exception-pattern 容忍日志/异常中文),批量 i18n-debt 而非红线。
- **R4-C1**:`AuthHeader.tsx` `t('key') || '中文'` fallback(6 处,极小)。
- **R4-C2**:`ComponentConfigs.ts` ~527 处单 locale 中文设计器属性标签 —— 系统性 designer-i18n 治理专项(独立 effort,与上一轮处理方式一致)。

### 排除(并发 authz 会话正在改,只作上下文)

perm-004 存量 controller 分类、capability 系统、tenant/permission controller、PlatformStatsController —— 被 `fix/tenant-mutation-authz-remaining`、`codex/permission-ui-sort`、`codex/platform-admin-enterprise-info-name`、`fix/rbac-platform-stats-*` 四个并发分支改写中。

## Wave 2 — 扩大覆盖面(安全 / 性能 / 跨仓一致性)

> owner 选「扩大 review 覆盖面」后追加。3 个新维度 reviewer(W1 安全 / W2 性能 / W3 跨仓),对比当前 main `cbb158a42`(期间被并发 authz 会话推进 2 commit:authz-gating 落地 + CI fix,均**未碰**本轮修复文件)。

### 已修复(本 PR,真栈单测验证)

| ID | sev | file | 问题 | 修复 | 验证 |
|---|---|---|---|---|---|
| DR-20260701-W1-sec-001 | **P1** | `agent/service/InterruptDispatcher.java` cancelRun | 跨租户 agent-run 取消 IDOR:raw JdbcTemplate `UPDATE ab_agent_run WHERE pid=?` **无 tenant_id**(该表不在 ignoreTable,raw JdbcTemplate 绕拦截器)→ 任一登录用户可取消他租户在飞 run(跨租户写/DoS);姊妹 INSERT_SUBTASK 路径有 tenantId+ACL | plumb tenantId + `AND tenant_id=?`(UPDATE+SELECT) | `InterruptDispatcherTenantScopeTest` PASS |
| DR-20260701-W1-sec-002 | **P1** | `meta/.../DynamicDataServiceImpl.java` applyFieldPermissionFilter{,Single} | field-permission 评估失败 catch→log.warn→**返回未脱敏记录**(隐藏字段泄漏);同 class 的 row-ACL/policy-mask/config-mask 全 fail-closed(23 处 throw),唯这 2 处 fail-open | 2 catch 改 fail-closed throw MetaServiceException | `DynamicDataServiceImplGetByIdFailSecureTest` +1(共 7)PASS |
| DR-20260701-W1-sec-003 | P2 | `meta/.../DataPermissionEngineImpl.java` dataScopeConditionToSql | config-boundary SQLi 防御纵深:SELF/DEPT 的 `ownerField`/`deptField` 标识符拼接 SQL **未过 `SqlSafetyUtils.isValidIdentifier`**(姊妹 row-policy builder 过了);值侧安全(Long owner id / 引号转义 dept pids),仅字段标识符是注入面 | 两处加 `isValidIdentifier` guard,非法→`log.warn`+`return "1 = 0"`(对齐姊妹 fail-secure) | `DataPermissionEngineImplDataScopeTest` +3(共 6)PASS(注入 field→1=0 / 合法→正常 SQL) |

### 延后(evidenced backlog,已价值分层——非闷头清)

| ID | sev | 内容 | 为何延后 |
|---|---|---|---|
| DR-20260701-W2-perf-001 | P1 | OEE fleet 大屏 N+1(`OeeFleetService` 1+3N,50 机 =151 查/次) | perf 非 correctness bug;需新建 port batch fetch + adapter grouped query + **真 DB IT** 验查询数下降+输出一致(6-8h) |
| DR-20260701-W2-perf-002 | P1 | IM 会话列表 N+1(`ImConversationServiceImpl.listByUser` 1+4N) | 触 chat 路径(L1 chokepoint 邻域,需谨慎);4 batched mapper+XML+真 DB IT(6-8h) |
| DR-20260701-W2-perf-003 | P2 | 未读汇总 N+1(`getUnreadSummary` 1+2N,badge 轮询放大) | 单 join 重写 + 真 DB IT(3-4h) |
| DR-20260701-W2-perf-cand | P2/P3 | 5 candidate:OrgController N+1 / RollUp 全表扫 / ExportAsyncTaskExecutor 无界物化(potential OOM)/ CapabilitySync N+1 / CascadeDelete loop | 多为 admin/maintenance 低频路径或需确认上游 cap;各 2-6h |
| DR-20260701-W3-xrepo-001 | P2 | `e2et_*` 全局 model code 在 OSS/EE test-fixtures 分歧定义(双 SoT,仅靠 reset-init 名字特判防撞) | **test-only**(`AURA_ENV=test` 才导入),当前 guard 有效未撞;修复跨 OSS+EE 双仓;low blast radius |
| DR-20260701-W1-sec-cand | P2/P3 | 3 admin-gated SSRF(Embedding/LLM/SaaS `baseUrl` 无 SsrfValidator)+ 1 不可达 SQLi(`QueryBuilderServiceImpl` 无 controller wire) | SSRF 各 ~0.5d 且 **admin-gated 非请求输入**;不可达 SQLi 现网无请求路径。防御性硬化,非紧急。(config-boundary SQLi 已修,见 W1-sec-003)|

### Candidate 门禁硬化(可选)
- `validate-permission-codes.mjs` 不扫 EE 侧 Java `@RequirePermission` 字面量(当前 0 drift)——未来 EE inline 权限码无注册会漏过 CI。扩展 validator 引用扫描面。

### wave-2 净结果
**2 个 P1 安全 bug 已修 + 验证**(都是 clean same-class residual)。其余为 perf(重活,需真 DB IT)/ 跨仓(test-only,contained)/ SSRF(admin-gated)的 evidenced backlog,已分层。平台整体工程纪律**强**:W1 确认 SQLi/path-traversal/deser/secret/zip-slip 守卫全面齐全(2 confirmed 都只是某路径漏了同 class 其它路径都有的守卫);W2 确认 `selectByQuery` 188 callsite 多为小表已 bounded、无 loop-HTTP/loop-external-IO;W3 确认 permission/schema/API-param/i18n **跨仓 0 drift**(strict validator 335 码)。

## 统计

| 指标 | 值 |
|---|---|
| reviewer | 5(wave1:R1 后端红线 / R2 链路 / R3 变更面 / R4 前端 / R5 测试+插件配置)+ 3(wave2:W1 安全 / W2 性能 / W3 跨仓) |
| raw findings | wave1 ~15 + 4 测试 gap;wave2 2 P1 安全 confirmed + 3 perf N+1 + 1 跨仓 + 若干 candidate |
| accepted & 已修 | **9 finding 组 = 13 处修复**(wave1:3 §9 + 1 loader 诊断 + 4 测试 gap + 1 inbox + 2 i18n;wave2:2 P1 安全),**20 测试新增/扩展** |
| 重分类/驳回 | 2(F1 fail-loud→仅诊断;R5 注入 candidate 驳回);wave2 W1 亦驳回多处(SQLi sink 逐片段过 SqlSafetyUtils、path-traversal 有 startsWith 守卫、无原生反序列化) |
| 延后(owner/重验/低频,已分层非 completionism) | wave1:2(bootstrap-002 §4.1 / flyway-001 baseline);wave2:3 perf N+1(需真 DB IT)+ 1 跨仓 test-only + SSRF/config-SQLi candidate |
| 本地门禁 | oss-boundary / reset-init / jsonb / public-record-id / version-sync 全 ✅ |
| 前端 typecheck | react-router typegen && tsc ✅ |

status: 两轮 review(5+3 维)完成。全部**可安全修复且不与并发会话冲突**的 verified finding 已闭环 + 真栈/typecheck 验证(13 处修复 / 20 测试,含 wave2 两个 P1 安全 bug);延后项(bootstrap-002 / flyway-001 / 3 perf N+1 / 跨仓 test-only / SSRF candidate)均**显式价值分层**——需 owner 决策、真 DB IT 重验、或低频/admin-gated,非闷头清的待办 backlog。交付于 PR #1126。
