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

## 统计

| 指标 | 值 |
|---|---|
| reviewer | 5(R1 后端红线 / R2 链路 / R3 变更面 / R4 前端 / R5 测试+插件配置) |
| raw findings | ~15 findings + 4 测试 gap + 若干 candidate |
| accepted & 已修 | 7 finding 组 = 11 处修复(3 §9 + 1 loader 诊断 + 4 测试 gap + 1 inbox + 2 i18n 文件),18 测试新增/扩展 |
| 重分类/驳回 | 2(F1 fail-loud→仅诊断;注入 candidate 驳回) |
| 延后(owner/重验,已分层非 completionism) | 2(bootstrap-002 §4.1 / flyway-001 baseline) |
| 本地门禁 | oss-boundary / reset-init / jsonb / public-record-id / version-sync 全 ✅ |
| 前端 typecheck | react-router typegen && tsc ✅ |

status: 全部**可修且不与并发会话冲突**的 verified finding 已闭环 + 真栈/typecheck 验证;2 个延后项已显式分层(有价值但需 owner 决策/更重验证),非闷头清的待办 backlog。
