---
type: handover
status: active
created: 2026-06-18
---
<!-- no-precipitation: session handover; code changes merged into PR #820; reusable canonical
     lessons (jsonb custom-mapper typeHandler; secure-by-default authz migration) tracked as
     待固化 in the Reflection section for a separate enterprise-canonical PR. -->

# Session Handover - 2026-06-18

## Session Summary
OSS 全仓深度 code review(五维 + 6 reviewer)→ 修复 verified findings。除修掉一批 P0/P1/P2 外,把根因(`PermissionInterceptor` fail-open)做成 **secure-by-default 的分阶段迁移机制(shadow→deny)**。全部进 PR #820(16 commits,54 测真栈绿,5 本地门禁绿,待 owner merge)。本文核心是 **Next Steps / 后续任务**。

## Tasks Completed(PR #820,16 commits)
- [x] **3 P0 authz/IDOR**:插件装卸/导入开放任意用户(`plugin.plugin.manage`)、SubjectPermission 写方法提权(`meta.permission.update`)、RecordComment 评论编辑/删除跨用户+跨租户 IDOR(`tenant_id+created_by` scope)
- [x] **14 个管理 controller 方法级守护**(4 批):Plugin×2/SubjectPermission/NlModeling/APS/PlatformAi → Org/Team/VersionHistory/ViewShare → Orchestration/Saga → TenantInvite/NotificationTemplate。码按「目标角色已持」选,operator+admin 保留、viewer 受限,GET 读保留
- [x] **默认-deny 迁移机制(战略根因修复)**:`PermissionInterceptor` 加 `aura.security.authz.unannotated-mode`(allow/shadow/deny,默认 shadow)+ `@AuthenticatedAccess` 标记 + 6 个 self-scoped controller 已标记
- [x] **perm-005 防回归门禁** `scripts/check-controller-authz.mjs`(baseline 75→62,新增 fail-open 写 controller 即 fail;识别 `@RequirePermission` + `@AuthenticatedAccess`)
- [x] **其他**:RolePermission.batchInsert jsonb conditions / NotificationRuleService 启动期 DDL 删除 / BpmNotify senderUserId 伪造+getReceived IDOR / ExtensionValidator inline bindingRules warning / oss-golden-stack boundary 修复
- [x] **测试**:DeepReviewControllerGuardTest(15)+ PermissionInterceptorUnannotatedModeTest(4)+ RecordComment IDOR(CMT-07/08)+ jsonb batchInsert IT + BpmNotify identity(2)+ ExtensionValidator(2);现有 PermissionInterceptorTest/TenantBootstrapServiceTest 无回归
- [x] **文档**:`docs/backlog/2026-06-18-oss-deep-review-plan.md`(计划+findings+统计)+ `2026-06-18-fail-open-controller-triage.md`(75 controller RBAC triage spec + 迁移路径)

## Tasks In Progress / 后续任务(核心)
> 安全/授权轨道已推进到「不依赖真实流量数据、不冒角色回归风险下能安全完成」的边界。再往下卡在 ops/产品侧,且有非安全轨道。下面是完整后续清单。

### S — 安全 default-deny 迁移收尾(承接本会话,顺序)
- [ ] **S0(owner)merge PR #820**。base=main 已漂移 19 commit,合前确认无冲突(改动文件集中,预期干净)。
- [ ] **S1(ops)** 把 `AURA_AUTHZ_UNANNOTATED_MODE=shadow`(默认即是)部署 staging/prod 跑 1-2 周 → grep 日志 `[authz-shadow]` 得「端点 × 真实调用方 userId/角色」覆盖表。这是替代「猜 operator 该不该做 X」的数据来源。
- [ ] **S2(产品/安全)** 用 shadow 覆盖表 + triage spec 敲定**角色-权限矩阵**(每操作 → 所需码 → 哪些角色持有)。这是唯一真正需要产品拍板的工件。
- [ ] **S3(eng,拿到 S2 后)** 按矩阵给剩余 ~50 端点标 `@RequirePermission(码)` 或 `@AuthenticatedAccess`;needs-new-code 的批量注册(MetaPermission + default-bootstrap),按职责授 operator/viewer。每批配 guard 单测 + `check-controller-authz.mjs --write-baseline` 收缩 baseline。
- [ ] **S4(ops)** 全覆盖(shadow 日志清零)后翻 `AURA_AUTHZ_UNANNOTATED_MODE=deny` → secure-by-default 终态。
- [ ] **S5(可选,不等 shadow)** 把 triage 中已高置信 SELF 的剩余 controller(Im×3/Inbox/Watch/Review/AuraBot×3/Notification/Engagement)预标 `@AuthenticatedAccess`(零访问变更),进一步收缩 shadow 噪声。

### B — triage 中发现的独立 bug(非 fail-open)
- [ ] **AsyncTaskController.listTasks** 跨租户/跨用户枚举:`ab_async_task` 在 `MybatisPlusConfig.ignoreTable`(线程池无 MetaContext),list 不自动加 tenant/userId。需产品定「admin 全量 vs user 自有」后手动 scope。
- [x] BpmNotify senderUserId 伪造 — 已修(commit a2c52f3d6)。
- (已甄别为误报:Saga/Email「跨租户」实为 TenantLineInnerInterceptor 已覆盖)

### 非安全轨道(各自独立,排期)
- [ ] **i18n sweep**:后端 user-facing 中文(TenantApplication/TenantMember Excel)+ ~7 前端框架组件(NotificationRuleBuilder/ChartWrapper/TenantSelection/QrCodeScanner/PermissionGuard/Header)
- [ ] **21 个 new-surface 测试 gap**(R6,~42h):17 后端 Service/Controller + 4 前端组件零测;详见 plan doc R6 段
- [x] ~~**project-management pm_* 幽灵路由**~~ ❌ **误报(2026-06-19 核验,禁删)**:R5 称死路由应删,但 `oss-scope.json:154-160` 明确 pm-* = `[A] 企业完整 PM 插件(非 OSS tpm 模板)`;路由已注册(route-manifest:79-80)+ executive-dashboard 8 链接 + 4 E2E spec。`pm_*` model 后端在私有/vertical 仓(OSS+enterprise 都没有)→ **有意的企业 PM 脚手架,删=破外部功能**。详 plan doc D5-frontend-001
- [ ] **§4.1 启动写库**:SystemTaskInitializer @PostConstruct insert(11 sys task)+ SkillBootstrapRunner per-tenant upsert → 迁 seed,需 fresh-DB 验证调度仍注册(有破坏风险)
- [ ] **P2**:RestRoute.of() readOnlyTx=false 默认(GET 路由可写库)/ AutomationTrigger 条件求值静默 false 无 AutomationLog

## Key Decisions
| Decision | Chosen Approach | Rationale | Alternatives |
|---|---|---|---|
| fail-open 根因 | shadow→deny 分阶段迁移机制 | 逐个守护治标(下个新 controller 仍敞开);default-deny 是根因。安全翻转用 shadow 先收数据(OWASP/WAF detection→block 范式) | 直接翻 deny(炸未注解端点);只逐个守护(治标) |
| 守护粒度 | 方法级(写方法),非类级 | 类级会连 GET 读一起锁,viewer 持 `.read` 码会丢读 | 类级(over-restrict) |
| 守护选码 | 用「目标角色已持的注册码」 | 不锁死 operator/viewer(实证:`meta.query.read` 守护 query 端点会废看板;`notification_rule_manage` 未注册会锁死所有人) | reviewer 建议码盲用(会锁死) |
| 残余 ~50 controller | 不盲守护,产 triage spec + shadow 收数据 | 是产品 RBAC 决策(operator 该做哪些?),盲守护有角色回归风险 | 逐个猜角色守护(高风险) |
| query 端点 | 走 model-level ABAC(DataPermissionEngine),不做 controller 守护 | RBAC 管 action、ABAC 管 data;粗守护废看板 | controller 粗守护 |

## Files Changed(本会话,均在 PR #820)
### Backend(授权)
- `permission/interceptor/PermissionInterceptor.java` — unannotated-mode(allow/shadow/deny)+ @AuthenticatedAccess 识别 + 去重 shadow 日志
- `permission/annotation/AuthenticatedAccess.java` — 新标记注解
- `permission/controller/SubjectPermissionController.java`(4 写方法)、`plugin/controller/{PluginPackage,PluginTransactionalImport}Controller.java`、`agent/.../NlModelingController.java`、`agent/controller/{ApsScheduling,PlatformAi}Controller.java`、`organization/controller/{Org,Team}Controller.java`、`versioning/.../VersionHistoryController.java`、`view/.../ViewShareController.java`、`bpm/controller/{Orchestration,Saga,BpmNotify}Controller.java`、`tenant/.../TenantInviteController.java`、`notification/.../NotificationTemplateController.java` — @RequirePermission 守护
- `{user,workbench,notification,agent}/.../{UserPreference,UserProfile,Session,UserNote,DeviceToken,UserSoulProfile}Controller.java` — @AuthenticatedAccess 标记
- `permission/constants/MetaPermission.java` + `tenant-templates/default-bootstrap.json` — 注册 4 新码(manufacturing.aps/ai.scoring.run/org.tenant.invite.manage/notification.template.manage)+ 授 operator
- `rbac/mapper/RolePermissionMapper.java`(jsonb cast)、`meta/service/RecordCommentService.java`(IDOR)、`notification/service/NotificationRuleService.java`(删启动 DDL)、`plugin/validation/ExtensionValidator.java`(inline bindingRules warning)
### Config / Scripts / Docs
- `application.yml` — authz.unannotated-mode 配置文档
- `scripts/check-controller-authz.mjs` + `controller-authz-baseline.json`(62)、`scripts/oss-golden-stack.sh`(boundary)
- `docs/backlog/2026-06-18-oss-deep-review-plan.md` + `2026-06-18-fail-open-controller-triage.md`
### Tests
- `DeepReviewControllerGuardTest` / `PermissionInterceptorUnannotatedModeTest` / `RolePermissionMapperJsonbBatchInsertTest` / `BpmNotifyControllerSenderIdentityTest` / `ExtensionValidatorInlineBindingTest`(新);`RecordCommentServiceIntegrationTest` / `NotificationRuleServiceTest`(改)

## Pitfalls & Workarounds
1. **jsonb batchInsert 失败 ≠ 仅缺 ::jsonb cast**:根因是自定义 `@Insert` 的 `#{binding.conditions}` **不继承** 实体 `@TableField` 的 JacksonTypeHandler → Map 落到默认 handler 报 `No hstore extension installed`。修法:`#{binding.conditions,typeHandler=...JacksonTypeHandler}::jsonb`。**只真栈 IT(写非 null 值)暴露**。
2. **RecordComment `created_by` 是 varchar**(存 user id 的文本),不是 bigint;WHERE 绑 Long → `operator does not exist: varchar = bigint`。绑 `String.valueOf(userId)`。
3. **`notification_rule_manage` 未注册为权限码**(只在 operator binding 引用,permissions[] 无定义)→ 用它守护会 `resolvePermissionId→null→deny 所有人`。用码前必 grep permissions[] 确认注册。
4. **AnnouncementController IT 用 `webAppContextSetup`**(拦截器生效)→ 守护会破坏其 IT(testUser 无码)→ defer。
5. **误报甄别(verify-before-flag 价值)**:Saga/Email「跨租户 list」是误报(`ab_saga`/`ab_email_account` 走 TenantLineInnerInterceptor)。核验前差点白修。

## Lessons Learned
- 授权红线的治本是 **secure-by-default**;逐个补注解只是治标,且每个补丁都是角色决策。shadow 模式把决策变成数据驱动。
- 守护选码必须对照**角色矩阵**(谁持有),不能只看语义;fail-open→fail-closed 翻转影响所有非超管角色。
- jsonb / varchar 列类型 / 权限码注册 这三类**只真栈/真 grep 暴露**,static/compile/单测全绿掩盖。

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工
1. **jsonb 修复第一版只加 `::jsonb`,真栈 IT 报 hstore** — 代价:1 轮返工 — 本可更早避免:知道「自定义 @Insert 不继承 @TableField typeHandler」 — 根因:`D 验证`(幸亏真栈 IT 抓到,没盲信 static)+ 可固化的新陷阱
2. **守护选码差点用 reviewer 建议的 `meta.query.read`/`notification_rule_manage`** — 代价:核验角色矩阵约 1 轮 — 本可更早避免:守护前先查 operator/viewer 持码 + permissions[] 注册 — 根因:`D 验证`(verify-before-flag 救回)
3. 其余顺畅:并行 reviewer + 主对话核验 + 真栈 IT 的节奏有效,无重大弯路

### 为什么会发生(根因小结)
主要是 `D 验证纪律`——但都被**真栈 IT + verify-before-flag 当场抓住**(jsonb 真根因、created_by 类型、误报甄别、未注册码),没有 ship 出错。这正是红线 §14/§15「jsonb/IDOR 必真栈 IT」「继承结论重新实测」的价值兑现。

### 应该有哪些改进
- 固化「自定义 @Insert/@Update 的 `#{}` 不继承 @TableField typeHandler,Map→hstore;须 inline typeHandler + ::jsonb」到 jsonb 陷阱 canonical
- 固化「fail-open authz 根因修复 = shadow→deny 分阶段 + 角色矩阵 + @AuthenticatedAccess」为可复用安全迁移 pattern

### 已固化 / 待固化(更新文档)
- [x] 已写入 `auraboot/docs/backlog/2026-06-18-fail-open-controller-triage.md`:默认-deny 迁移机制 + 完整路径 + 角色矩阵 spec + 误报甄别
- [x] 已写入 `auraboot/docs/backlog/2026-06-18-oss-deep-review-plan.md`:findings + jsonb/created_by 真根因 + 统计
- [ ] 待固化 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md`(另起 enterprise PR):「自定义 @Insert/@Update `#{}` 不继承 @TableField JacksonTypeHandler → Map 报 No hstore extension;须 `#{x,typeHandler=...JacksonTypeHandler}::jsonb`;只真栈写非 null 暴露」(关键字:jsonb/hstore/自定义 mapper/typeHandler)
- [ ] 待固化 `auraboot-enterprise/docs/agent-rules/security-review-discipline.md` 或新 agent-rule(另起 enterprise PR):「authz fail-open 根因修复走 secure-by-default 分阶段:shadow(log-only 收真实流量)→ 角色矩阵 → 注解全覆盖(@RequirePermission/@AuthenticatedAccess)→ 翻 deny;守护选码必对照角色持码矩阵,禁盲用语义码」
- (memory MEMORY.md 已近 22.5KB 硬上限,新 pointer 暂不加;上述两条 enterprise canonical 固化留作独立 follow-up PR,不混入本 OSS PR)

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支**:`feat/oss-deep-review-2026-06-18`(base `main`;origin/main 自分支点已前进 19 commit,本分支 16 commit)
- **Worktree**:`/Users/ghj/work/auraboot/auraboot-deep-review`(本会话新建,off origin/main)
- **本会话关键 commit**:首 `7f8b50c16` → 末 `e10d2f906`(16 个)
- **PR**:`#820 · OPEN · head e10d2f906 · base main`;未自动合并(access-control 敏感,留 owner)。merge=UNKNOWN(无 CI checks,Actions 关闭)
- **未提交改动**:无(工作区干净,全部已 commit+push)

### Runtime / 端口
- **未分配 dev.sh runtime**。本会话的 IT 走 worktree 内 `./gradlew :test`(platform 子目录)直连**共享 host PostgreSQL `aura_boot:5432`**(`application-integration-test` profile,ddl-auto:none,用现有 schema)。
- ⚠️ 共享 aura_boot 可能被并发会话 reset → IT 偶发 `relation does not exist`(env-invalid 非代码 bug,见 memory `feedback-shared-aura-boot-it-db-reset-flakiness`)。重跑前确认 schema 在。
- 零 docker。无常驻后端进程(只跑 gradle test 即起即停 Spring context)。

### Database / Seed
- 用现有共享 aura_boot schema;新加的 4 个权限码 + 2 个 operator 绑定只在 `default-bootstrap.json`(模板),不影响已 seed 的库;guard 单测是纯 Mockito 不依赖 DB;jsonb/IDOR IT 自建 test-scoped 行。接手无需 reset。

## Next Steps
见上「Tasks In Progress / 后续任务」。最高优先:**S0 owner merge #820** → **S1 ops 跑 shadow 收数据**(解锁 S2/S3/S4)。其余轨道(i18n / 测试 gap / 幽灵路由)独立可并行排期。

## Context for Next Session
- 起点文档:`auraboot/docs/backlog/2026-06-18-oss-deep-review-plan.md`(全景)+ `2026-06-18-fail-open-controller-triage.md`(75 controller 分类 + 迁移路径 + 角色矩阵草稿)
- 防回归门禁跑法:`node scripts/check-controller-authz.mjs`(根目录);权限码门禁 `node scripts/validate-permission-codes.mjs`
- 续做 S3(注解剩余 controller)前:先 `git fetch` + 基于最新 origin/main 开新 worktree(本分支若已 merge);用 triage spec 的 NEEDS-GUARD 表 + shadow 日志驱动
- guard 单测范式:`DeepReviewControllerGuardTest`(真实 controller 方法构造 HandlerMethod + mock hasPermission,deny/allow);新 controller 守护后加一例即可
