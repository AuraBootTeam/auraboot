---
type: backlog
status: in-progress
created: 2026-06-18
owner: diqi
related: 2026-06-18-oss-deep-review-plan.md
---

# Fail-open 写 controller 授权 triage(DR-20260618-D1-perm-004 spec)

> **背景**:`PermissionInterceptor` 对无 `@RequirePermission` 的 handler fail-open(`annotation == null → return true`)。本轮 grep 实测:**178 个有写映射的 controller,75 个无 `@RequirePermission` 且非 `/api/admin/**`** → 任意登录用户可达。
>
> **为什么不能一把梭批量守护**:fail-open→fail-closed 会翻转**所有非 tenant_admin 角色**的行为。默认角色矩阵(`default-bootstrap.json`):`tenant_admin`=`*`(全部)/ `operator`=22 码 / `viewer`=9 码。给某 controller 加 `@RequirePermission(X)` 后,只有持 X 的角色能用。**reviewer 建议的多数码 operator/viewer 并不持有**(如 `meta.query.read`/`sys.scheduler.update`/`meta.field.update`/`acp.runtime.manage`/`bpm.process.admin`),盲目守护会锁死 operator/viewer 的合法功能(尤其 query 端点用 `meta.query.read` 会废掉所有非 admin 看板)。
>
> 因此每个 controller 的守护码 = **「哪个角色应做此操作」× 「该角色持有的码」** 的产品 RBAC 决策。本表是决策 spec;**surface 增长已被 `check-controller-authz.mjs` 门禁封住**;PR #820 已守护影响最大的 6 个(见下 ✅)。

## operator 持有的码(守护选码时优先用这些以不锁死 operator)
`dashboard.manage` `dashboards` `query_builder` `bpm_management` `bpm_task_center` `bpm_monitor` `bpm.process.execute` `bpm.task.read` `bpm.task.manage` `bpm.form.manage` `org_management` `member_management` `org_teams` `org.team.read` `org.team.manage` `notification.view` `notification_rule_manage` `sys.file.upload` `meta.changelog.read` `meta.filter.manage` `meta.chatbi.use` `dashboard_mgmt`

## 分类(75 个;来源:4 个并行 reviewer 逐个读源核验)

### ✅ 已守护(PR #820)
PluginPackageController / PluginTransactionalImportController(`plugin.plugin.manage`)、SubjectPermissionController 写方法(`meta.permission.update`)、NlModelingController(`meta.model.update`)、ApsSchedulingController(`meta.manufacturing.aps`)、PlatformAiController(`ai.scoring.run`)。

### EXEMPT — 合法豁免,保留 baseline(无需守护)
| Controller | 豁免类别 | 证据 |
|---|---|---|
| AuthController / VerifyCodeController / DeactivationController | AUTH | pre-auth 流,JWT 白名单,登录/注册/验证码/注销自身 |
| TestSeedController / TestFixtureController | TEST | `@Profile("test")` + testWhiteList |
| BootstrapController | AUTH | `/api/bootstrap/**` 白名单(pre-tenant)+ `isInitialized()` 幂等 |
| TenantSelectionController | AUTH | 登录后 pre-tenant-context 选/建/加入空间,select 校验 active member |
| AutomationWebhookController / AirflowWebhookController / MarketplaceStripeWebhookController | WEBHOOK | 自身 HMAC-SHA256 签名校验 + fail-closed(无 secret 拒绝) |
| UserSoulProfileController / SessionController / UserPreferenceController / UserProfileController / UserNoteController / DeviceTokenController / NotificationController / DeactivationController / UserEngagementController / TenantPreferenceController(self 部分) | SELF | 写操作经 `MetaContext.getCurrentUserId()` 只动调用者自有数据(均有 `user_id=?` scope 证据) |
| AuraBotController / AuraBotConversationController / ConversationTurnController | SELF | 经 ConversationTurnService 写自己的会话/turn;cancel 校验 initiator |
| ImConversationController / ImMessageController / ImNotificationPreferenceController / InboxController | SELF | `isMember()` / `getCurrentUserId()` + tenantId scope |
| WatchController / ReviewController | SELF | watch/review/vote 绑当前用户 |
| BatchQueryController / ChartDataController | SELF(POST-as-query) | 只读聚合查询无写库/无副作用 |

### NEEDS-GUARD — 真需守护,待按角色矩阵决策守护码
> ⚠️ 选码须确认目标角色持有(否则锁死)。`operator-held` = operator 已有该码,守护后 operator+admin 保留、viewer 受限(多为正确)。`needs-new-code` = 须先注册码。`role-decision` = 需产品定哪个角色该做。

| Controller | 写操作 | 建议码 | 备注 |
|---|---|---|---|
| OrgController | dept/employee CRUD + 转岗 | `org.team.manage` | operator-held;**方法级守护写方法**(类级会连 GET 读一起锁,viewer 持 org.team.read 应保留读) |
| TeamController | team CRUD + member | `org.team.manage` | operator-held;方法级 |
| TenantMemberController | 审批/状态/删除/导入 | `org.team.manage` | operator-held;**有现存 IT,改时同步 fixture 角色** |
| VersionHistoryController | rollback dashboard | `dashboard.manage` | operator-held;方法级(GET 列版本保留) |
| ViewShareController | 创建/撤销分享链接 | `dashboard.manage` | operator-held;方法级(GET /shared 白名单保留) |
| NotificationRuleController | 规则 CRUD | `notification_rule_manage` | operator-held(注意是下划线码) |
| NotificationTemplateController | 模板 CRUD | needs-new-code `notification.template.manage` | role-decision(operator?) |
| CallbackController | BPM 回调推进节点 | `bpm.process.execute` | operator-held;**确认是否系统回调**(若系统-to-系统则改签名校验而非用户权限) |
| OrchestrationController / SagaController | 流程执行 暂停/取消/重试 | `bpm.process.execute`(operator-held)或 `bpm.process.admin`(operator 无) | role-decision:operator 能否管编排 |
| TriggerController | 触发器 CRUD + fire | CRUD→role-decision;fire/webhook→`bpm.process.execute` 或签名校验 | webhook 仅当配 secret 才校验,无 secret 不算豁免 |
| BpmAiController / BpmNotifyController | AI 建议/生成 + CC/催办 | `bpm.task.manage`(operator-held) | BpmNotify 另有 `senderUserId` 取自 body 可伪造的**独立 bug** |
| ReportScheduleController | 调度 CRUD + test-send | `sys.scheduler.update`(operator 无)或 `dashboard.manage`(operator 有) | role-decision |
| AsyncTaskController | cancel/delete + list 全租户 | `sys.async_task.update`(operator 无) | list 缺 userId 过滤是独立越权 bug |
| MetaFieldOrchestratorController / RollUpController | 字段编排 / 重算 | `meta.field.update`(operator 无) | role-decision |
| RecordShareController / UserProjectBindingController | 授予他人访问 / 绑成员 | needs-new-code `record.share.manage` / `project.member.manage` | 授予他人 = 非 self |
| AnnouncementController | 公告 CRUD | needs-new-code `org.announcement.manage` | role-decision |
| TenantInviteController(generate/revoke) | 生成/吊销邀请码 | needs-new-code `org.tenant.invite.manage` | use/validate 保持开放 |
| SemanticController(publish) | 发布语义目录 | needs-new-code `meta.semantic.publish` | query/sql 用 `query_builder`(operator-held);validate 纯内存豁免 |
| BiTemporalController / MrpController | 双时态写 / MRP 运算 | needs-new-code `data.bitemporal.write` / `meta.manufacturing.mrp` | aps 同系列 |
| McpAuditController / ActivityController / WdLeaveAiController | 审计/活动写 / leave-ai | needs-new-code | WdLeaveAi 无 @Profile,生产可达 |
| AiActionAuditController / AiScoringController / InterruptController / AuraBotSkillController | agent 写/中断/skill 执行 | `acp.runtime.manage`(operator 无) | role-decision:agent 是否用户功能 |
| AiFieldController / AiModelSuggestionController / IntentController | LLM 生成(成本) | needs-new-code `ai.field.generate` 等 | 按 `ai.scoring.run` 样式 |
| EmailAccountController / EmailMessageController / EmailSequenceController | 邮件账户/消息/序列 | needs-new-code `email.*` | requireAccount 仅存在性检查无 tenant 归属是独立 bug |
| InboundEmailController | 入站邮件 webhook | needs-new-code 或加 webhook secret | 白名单 `/api/crm/inbound/**` 不覆盖 `/inbound-email/` |
| DataSyncController / DslCompilerController / FormulaController / PivotQueryController / QueryBuilderController | 订阅/编译/公式/透视/查询 | role-decision(多为 query;查询端点优先 ABAC 而非粗粒度 controller 守护) | **禁用 operator/viewer 不持有的码守护 query 端点**(会废看板) |

### 顺手发现的独立 bug(非 fail-open,单独修)
- `BpmNotifyController` cc/urge 的 `senderUserId` 取自 request body → 可伪造冒充他人(应取 MetaContext)。
- `SagaController.listSagas` / `AsyncTaskController.listTasks` 无 tenant/userId 过滤 → 跨租户/跨用户枚举。
- `EmailAccountController.requireAccount(id)` 只查存在性不校验 tenant 归属 → 跨租户操作他人邮箱账户。

## 落地建议
1. 按上表逐 controller 走方法级守护(写方法),码按「目标角色 × 已持码」选;needs-new-code 的批量注册(MetaPermission + default-bootstrap）。
2. operator/viewer 应保留的操作:把对应码加进其 bootstrap binding。
3. 每批配 `DeepReviewControllerGuardTest` 风格单测 + 守护后 `node scripts/check-controller-authz.mjs --write-baseline` 收缩 baseline。
4. 独立 bug 单独 PR 修(含 IT)。
