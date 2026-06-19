---
type: backlog
status: active
created: 2026-06-18
owner: diqi
---

# OSS 全仓深度 Code Review 计划(2026-06-18)

> 方法论 canonical:`auraboot-enterprise/docs/agent-rules/deep-review-strategy.md`(五维切片)+ `review-baseline.md`(verify-before-flag,历史 FP 30-50%,证据要求严格)。

## §0 执行前提

1. **目标输出形式**:`review → 直接修复`。本轮不只产 backlog,而是把 verified P0/P1/P2 在隔离 worktree 修掉 + 补测试 + 跑门禁 + PR。因此 finding 聚焦**真实且可修**,按 `severity / effort` 排期。
2. **范围**:仅 OSS 仓(`/Users/ghj/work/auraboot/auraboot`)。三栈全纳入:Java(`platform/` + `plugins/`)+ DSL JSON(`plugins/*/config`)+ TS/TSX(`web-admin/app`)。`plugins/test-fixtures` 默认不导入,纳入 review 但违规优先级降级。Enterprise 仓不在范围(D4 仅做 OSS-boundary lite)。
3. **快照基线**:
   - `review_anchor`: OSS `0de947e2e`(origin/main HEAD @ 2026-06-18,worktree base)
   - `comparison_base`(D2 用):近 30 天窗口起点 `2026-05-19`(D2 仅看本窗口 diff + chokepoint 文件不限时间窗)
4. **并发上限**:read-only reviewer 不跑测试/DB/服务,放宽到 6 个并行;每个显式 `cd` 不互相 chdir;一旦任一 reviewer 需要起栈,暂停由主对话重规划。

## 代码面(摸底实证)

| 栈 | 规模 |
|---|---|
| platform Java 源 | 3322 文件;60+ framework 子域 + module |
| platform Java 测试 | 1280 文件 |
| plugins | 24 个(含 test-fixtures) |
| web-admin ts/tsx | 2239 文件 |
| 近 30 天 commit | 698(web-admin 2245 文件次 / platform 2191 / plugins 294) |

静态门禁基线(本轮启动时全绿):oss-boundary ✅ / reset-init-contracts ✅(8 pass)/ jsonb-typehandler ✅(38 处 String→jsonb 受保护)/ schema-sql(需 docker,跳过)。

## 维度切片与并行 reviewer

| Reviewer | 维度 | 范围 | 重点 |
|---|---|---|---|
| R1 | D1 后端红线 | `platform/src/main/java` + `plugins/*/backend` | catch(Exception) 吞异常/rollback-only;自愈 ensure-fallback;API 参数漂移;i18n 硬编码中文(区分 LocalizedText);大小写比较 |
| R2 | D1+L2 权限/安全 | Controller + permission 注册源 + SQL | permission code drift;@RequirePermission 覆盖;Admin guard URL-prefix;SQL 注入(逐片段 trace);getById/list fail-open;document_flow sideEffect |
| R3 | D3 核心链路 | L1 对话回合 / L3 DynamicController·FieldMapPhase / L4 插件加载 | 旁路 chokepoint;事务边界;jsonb typeHandler 读写;type:delete 路由对称 |
| R4 | D2 变更面 | 近 30 天 platform+plugins 高风险 commit + chokepoint | 回归风险;breaking change 漏 doc;对照 engineering-gotchas 已记陷阱 |
| R5 | 前端红线 | `web-admin/app` | i18n 硬编码;配置优先(豁免清单);API 参数名对齐后端;错误态/raw code 泄漏;catch 吞错 |
| R6 | D5 测试 gap(lite) | Service/Controller/页面 source-vs-test 双向存在 | 新增 public surface 无测试;关键链路无 IT/E2E;只产 backlog 不跑测试 |

## §5 合并工序(主对话)

并行回报后做 7 步:coverage gate → 候选转交 → 去重 → 降级/撤销(未达 baseline 证据契约立即撤)→ 编号 `DR-20260618-D<x>-<topic>-NNN` → 优先级重排(severity/effort 分开)→ anchor 到快照。**每条进入修复的 P0/P1 主对话独立重跑核验,不信 subagent 自报。**

## 修复阶段

按 `severity/effort` 在本 worktree(`feat/oss-deep-review-2026-06-18`)逐条修;每修配测试;跑相关本地门禁;分主题 commit;最终 PR。撞需起栈验证的 finding 转 backlog 标 owner。

## 合并后 accepted findings(主对话 verify-before-flag 二次核验)

> 每条都经主对话独立 grep/read/psql 核验,不信 subagent 自报。`review_anchor: OSS 0de947e2e`。

### 已修复(本 PR,均真栈测试验证)

| ID | severity | file | 问题 | 修复 | 验证 |
|---|---|---|---|---|---|
| DR-20260618-D1-perm-001 | **P0** | `plugin/controller/PluginPackageController.java` `PluginTransactionalImportController.java` | 任意登录用户可装/卸/回滚插件 + 导入配置(无 `@RequirePermission`,非 `/api/admin/**`,PermissionInterceptor fail-open) | 类级 `@RequirePermission("plugin.plugin.manage")`(与姊妹 PluginImportController/MarketplaceInstallController 同码) | DeepReviewControllerGuardTest deny/allow PASS |
| DR-20260618-D1-perm-002 | **P0** | `permission/controller/SubjectPermissionController.java` | 任意用户可增删权限声明(权限模型篡改) | 4 个写方法 `@RequirePermission(PERMISSION_MANAGE)`(eval/list 保持开放不破坏 UI 可见性) | guard test PASS(add/removeAll) |
| DR-20260618-D1-perm-003 | P1 | `agent/nlmodeling/controller/NlModelingController.java` | 任意用户可用 LLM 生成并 apply(OVERWRITE+autoPublish)schema 到租户 | 类级 `@RequirePermission(MODEL_MANAGE)` | guard test PASS |
| DR-20260618-D2-idor-001 | **P0** | `meta/service/RecordCommentService.java` | edit/delete 评论 WHERE 仅 `id`,JdbcTemplate 绕过租户拦截器 → 跨用户+跨租户 IDOR | edit/delete 加 `tenant_id + created_by` 限定(created_by 经核验是 **varchar**,绑 `String.valueOf(userId)`);list 加 tenant_id | RecordCommentServiceIntegrationTest CMT-07/08 PASS(真栈) |
| DR-20260618-D3-jsonb-001 | P1 | `rbac/mapper/RolePermissionMapper.java` | `batchInsert` 写 jsonb `conditions`:自定义 @Insert 未用 @TableField 的 JacksonTypeHandler(Map→hstore 报错)+ 无 `::jsonb` cast(stringtype 非 unspecified) | `#{binding.conditions,typeHandler=...JacksonTypeHandler}::jsonb` | RolePermissionMapperJsonbBatchInsertTest PASS(真栈,非平凡值) |
| DR-20260618-D1-bootstrap-001 | P1 | `notification/service/NotificationRuleService.java` | `@PostConstruct initSchema()` 启动期 CREATE TABLE+索引+swallow catch(§4.1) | 删除整个 @PostConstruct(表已由 schema.sql:5283 拥有)+ 连带清理 dataSource 字段/import | compile + reset-init-contracts gate ✅;schema.sql:5283 已含同表 |
| DR-20260618-D2-gate-001 | P1 | `scripts/oss-golden-stack.sh` | PR #816 引入注释含 `auraboot-enterprise` 路径 → **origin/main 当前 check-oss-boundary 失败**(近期 PR 破坏本地门禁) | 注释去掉字面路径前缀 | check-oss-boundary ✅ |
| DR-20260618-D4-dx-001 | P2 | `plugin/validation/ExtensionValidator.java` | inline bindingRules 仅 log.warn 不进 validator result(import 仍 success:true,规则静默 drop,§6 footgun) | 加 `S-EXT-INLINE-BINDING` validation warning | ExtensionValidatorInlineBindingTest PASS(emit + no-emit) |
| DR-20260618-D1-perm-006 | P1 | `agent/controller/ApsSchedulingController.java` `PlatformAiController.java` | 任意用户可触发全租户 APS 排程(compute)/ LLM 记录评分(成本+写任意 model 字段) | 注册新码 `meta.manufacturing.aps` / `ai.scoring.run`(default-bootstrap + MetaPermission)+ 加 `@RequirePermission` | guard test 2 例 deny/allow PASS;validate-permission-codes drift 0(321→323);TenantBootstrapServiceTest PASS |
| DR-20260618-D1-perm-005 | P2 | `scripts/check-controller-authz.mjs` + `controller-authz-baseline.json` | 无门禁防止**新增** fail-open 写 controller(perm-004 surface 会静默增长) | 新本地门禁:枚举有写映射 / 无 @RequirePermission / 非 /api/admin 的 controller,baseline 现状 75 个,只对**新增**报错 | baseline=75 准确;compare PASS;负向测试(模拟新增)→ exit 1 named;6 个已修 controller 不在 baseline |

**真栈验证反哺(两处比 reviewer 静态判断更深,印证「jsonb/IDOR 必真栈 IT」红线):**
1. jsonb 不止「缺 ::jsonb cast」——根因是自定义 @Insert 不继承 @TableField typeHandler,Map 落到默认 handler 当 hstore;只有真 insert 非 null 值才暴露。
2. RecordComment `created_by` 是 **varchar**(addComment 把 Long 赋值期强转字符串存),WHERE 必须绑 String;静态看以为是 bigint。

### Backlog(已 verify、本 PR 未修,留后续 — 多为大 sweep 或需独立验证)

| ID | severity | 来源 | 内容 | 不在本 PR 的原因 |
|---|---|---|---|---|
| DR-20260618-D1-perm-004 | P1 | R2 F-03 | PermissionInterceptor fail-open。**精确清单(本轮 grep 实测,replaces 估计 ~116)**:178 个有写映射的 controller,其中 **75 个无 `@RequirePermission` 且非 `/api/admin`**。**须逐个人工分类,禁盲目批量守护**:① 合法豁免(auth flow `AuthController`/`VerifyCodeController`/`DeactivationController`、`@Profile(test)` `TestSeed`/`TestFixture`、签名 webhook `MarketplaceStripeWebhook`、self-scoped `TenantSelection`/`TenantPreference`/`Notification`(本人)/`AuraBot*` 会话)② 真需守护(`OrgController`/`TeamController` 组织架构、`DataSyncController`、`NotificationTemplateController`/`NotificationRuleController` 配置、`QueryBuilderController` 等)| 系统性工程:75 条逐个判定 self-scoped vs admin(需领域知识,盲目守护会锁死 auth/test/self 流)。本 PR 已修其中影响面最大的 6 个(perm-001/002/003/006);余 ~69 待分类。**surface 增长已被 perm-005 门禁(`check-controller-authz.mjs`)封住**,只剩存量分类 |
| DR-20260618-D1-bootstrap-002 | P1 | R1 F2/F3 | SystemTaskInitializer @PostConstruct insert(11 sys task)+ SkillBootstrapRunner per-tenant upsert(§4.1) | 需把 seed 迁到 reset-init + 真栈验证 fresh DB 仍注册任务/skill,改错破坏调度 |
| DR-20260618-D1-i18n-001 | P1 | R1 F4/F5 + R5 P1-001..007 | 后端 user-facing 中文(TenantApplication/TenantMember Excel)+ 前端 ~7 处框架组件硬编码中文(NotificationRuleBuilder/ChartWrapper/TenantSelection/QrCodeScanner/PermissionGuard/Header) | i18n key 基建 sweep,独立任务 |
| ~~DR-20260618-D5-frontend-001~~ ❌**误报(2026-06-19 核验)** | — | R5 P1-007 | R5 称 `routes/project-management/` TSX 调「幽灵」`pm_*` model 是**死路由,应删**。**核验推翻**:① `oss-scope.json:154-160` 明确标 pm-* 套件 = `[A] full project-management plugin (enterprise; not the OSS tpm template)`——OSS 的 `tpm_*` 是轻量 DSL 模板,`pm_*` 完整 PM 插件(富 Gantt/看板/成员/评论 + pm_* models)是**企业/外部插件**,被刻意 scope 出 OSS;② 路由在 `route-manifest.ts:79-80` 注册、executive-dashboard 8 处链接、有 4+ E2E spec scaffolding(pm-project-crud 等);③ pm_* models OSS+enterprise 两仓都没有 → 后端在私有/vertical/planned 仓。**结论:这是有意的企业 PM 脚手架,非冗余/死代码,禁删**(删=移除外部功能脚手架+破 E2E)。OSS-only 部署里它 404 是设计如此。残留真问题仅:OSS-only 下 executive-dashboard deep-link 404(低,非菜单)+ 236 处硬编码中文(归 i18n-001) |
| DR-20260618-D3-obs-001 | P2 | R4 F-04 | AutomationTriggerServiceImpl.evaluateCondition swallow SpEL 异常静默 false 无 AutomationLog | 观测性增强,需补 AutomationLog 失败路径 + IT |
| DR-20260618-D3-spi-001 | P2 | R4 F-05 | RestRoute.of() readOnlyTx=false 默认,GET 路由可写库 | SPI 默认值改动影响所有插件,需 registry 校验 + 文档 |
| DR-20260618-D5-test-* | P1/P2 | R6 | 17 后端 new-surface Service/Controller + 4 前端组件完全无测(~42h) | 大补测 sweep,独立排期 |

## 统计

| 指标 | 值 |
|---|---|
| reviewer | 6(R1 后端红线 / R2 权限安全 / R3 链路 / R4 变更面 / R5 前端 / R6 测试 gap) |
| raw findings(各 reviewer) | ~40 findings + ~21 测试 gap |
| accepted(主对话核验) | 16(7 已修 + 9 backlog 条目,部分合并) |
| revoked / 误报 | reviewer 自身已大量排除(LocalizedText / partial-success catch / 拦截器自动 tenant_id / Admin Guard URL-prefix / getById 已修);主对话核验阶段无新增撤销,但 2 处「修法」被真栈测试推翻重做(jsonb / created_by) |
| 已修 + 真栈验证 | 7 finding,14 测试全绿(guard 5 + RecordComment 8 + jsonb 1) |
| 本地门禁 | jsonb ✅ / oss-boundary ✅ / reset-init ✅ |

status: 第一批高价值 verified 修复已闭环(全 P0 + 高价值 P1);backlog 余项留后续排期。
