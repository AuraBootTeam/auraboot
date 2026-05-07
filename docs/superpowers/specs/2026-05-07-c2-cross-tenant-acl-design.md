# 2026-05-07 C.2 Cross-Tenant Sub-Agent ACL — 设计

> **状态:owner 已 lock 全部默认选项(Q1=A+C kill-switch / Q2=仅 platform_admin / Q3=不需双签 / Q4=租户对粒度 / Q5=每 spawn 一行 audit / Q6=Phase 1 admin 页 / Q7=新拒旧续 / Q8=直接 throw / Q9=SYSTEM_TENANT 也需 grant / Q10=ParentJoinService 同步放开 / Q11=delegate_task 走同 ACL / Q12=C.1 joinChildRun 一并放开)2026-05-07。**
> **类型:一次性设计稿。** 实施完成后,长期跟踪进 `docs/backlog/2026-05-06-acp-p0-p1-followups.md`。

## 背景

Backlog 引用:`docs/backlog/2026-05-06-acp-p0-p1-followups.md` §C.2

> SubAgentRunner 当前 strict refuse cross-tenant spawn(IllegalStateException)。但企业内可能有"跨租户 supervisor agent"场景(平台租户的 system agent 派子 run 给业务租户的 agent)。
> 加 cross-tenant ACL 表(默认 deny);只有显式 grant 的 (parent_tenant, child_tenant) 对才允许。

## 当前状态(2026-05-07 探源)

**SubAgentRunner.spawn**(`agent/service/SubAgentRunner.java:178-180`):
```java
if (!tenantId.equals(parentTenant)) {
    throw new IllegalStateException(
        "Parent run tenant " + parentTenant + " does not match caller tenant " + tenantId);
}
```

硬拒绝。无 ACL 查询、无审计、无开关。

**ParentJoinService.onSessionEnded**(`agent/service/ParentJoinService.java:69-72`):
跨租户 ChildRunCompletedEvent 已 silently 丢弃(memory `feedback_subagent_worktree_verify` 红线)。本设计需协调:ACL 通过的事件不再丢弃。

**已有 ACL/permission 表**(供参考):`ab_permission` / `ab_role_permission` / `ab_user_role` —— **不适合复用**,这些是"用户能做什么",不是"租户对租户的派子 run 关系"。新表更清晰。

## 候选方案

### 方案 A — 单向 grant 表(推荐)

**Schema**:
```sql
CREATE TABLE ab_cross_tenant_grant (
    id BIGSERIAL PRIMARY KEY,
    parent_tenant_id BIGINT NOT NULL,        -- 主调
    child_tenant_id  BIGINT NOT NULL,        -- 被调
    grant_type       VARCHAR(20) NOT NULL,   -- "spawn_sub_agent" (扩展性)
    granted_by       BIGINT NOT NULL,        -- platform admin user id
    granted_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,            -- nullable = 永久
    revoked_at       TIMESTAMPTZ,            -- nullable = 有效
    note             TEXT
);
-- 关键:partial unique index — 仅约束"未撤销"的行,
-- 允许 revoke 后再 grant 同对租户(老 row 留下作 audit trail,新 row 进表)。
CREATE UNIQUE INDEX idx_cross_tenant_grant_active_unique
    ON ab_cross_tenant_grant (parent_tenant_id, child_tenant_id, grant_type)
    WHERE revoked_at IS NULL;
```

**审计表**(因 `ab_audit_log` 通用表不存在,仓内现有审计是 scoped 表如 `ab_query_audit_log` / `ab_command_audit_log`,这里同样建独立表):
```sql
CREATE TABLE ab_cross_tenant_spawn_audit (
    id BIGSERIAL PRIMARY KEY,
    grant_id          BIGINT REFERENCES ab_cross_tenant_grant(id),
    parent_tenant_id  BIGINT NOT NULL,
    child_tenant_id   BIGINT NOT NULL,
    parent_run_pid    VARCHAR(26) NOT NULL,
    child_run_pid     VARCHAR(26),            -- nullable: spawn 失败时为 null
    decision          VARCHAR(20) NOT NULL,    -- "allowed" / "denied_no_grant" / "denied_expired" / "denied_revoked"
    spawn_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message     TEXT
);
CREATE INDEX ix_xtg_audit_parent_time ON ab_cross_tenant_spawn_audit(parent_tenant_id, spawn_at);
CREATE INDEX ix_xtg_audit_child_time  ON ab_cross_tenant_spawn_audit(child_tenant_id,  spawn_at);
```

**逻辑**:
1. SubAgentRunner.spawn 在 throw IllegalStateException 之前先查 ACL:
   ```sql
   SELECT id FROM ab_cross_tenant_grant
   WHERE parent_tenant_id = ? AND child_tenant_id = ?
     AND grant_type = 'spawn_sub_agent'
     AND revoked_at IS NULL
     AND (expires_at IS NULL OR expires_at > now())
   LIMIT 1;
   ```
2. ACL 通过 → 正常 spawn,写一行 `ab_cross_tenant_spawn_audit (decision='allowed', grant_id=X)`
3. 拒绝 → 仍 throw `IllegalStateException`,error 区分 `denied_no_grant` / `denied_expired` / `denied_revoked`,各写一行 audit;消息改进为"cross-tenant spawn requires explicit grant (parent_tenant=A → child_tenant=B)"

**ACL 缓存**:
- Spring `@Cacheable` 不用——粒度难控。手写 `Caffeine`,`maximumSize=10_000`,`expireAfterWrite=10s`(短 TTL 折中:revoke/expire 最多 10 秒生效,不引入显式失效路径)
- 若 owner 要 "revoke 立即生效",升级为 listener-based invalidation(grant/revoke 后 publish event → listener `cache.invalidate(key)`),但这是后续优化,Phase 1 不做

**Pro**:
- 单向 = 显式,不会"双方有一方授权 = 全通"
- 默认 deny = 失败开放,符合产品安全直觉
- 表独立 = 不污染 ab_permission 模型
- 单 query 决策(latency 可忽略)

**Con**:
- 一对租户需要显式 grant 单方向;若双向 supervisor 场景要 2 行(可接受)

**估时**:5-7 天(schema + migration + ACL service + SubAgentRunner 接通 + 审计 + admin grant 管理 UI + 全套测试)

---

### 方案 B — 角色式(reuse `ab_permission`)

把"跨租户派子 run"建成一个 `ab_permission` 项,通过 role 绑定。

**Pro**:复用既有权限模型

**Con**:
- ab_permission 的语义是"用户能做什么 within tenant",硬塞"租户对租户"会让模型混乱
- 跨租户语义在 user-role-perm 链路里没有自然位置
- 估时反而更长:需要扩 `ab_permission` 列或加新中间表

**判定:不取**

---

### 方案 C — feature flag 全局放行

`agent.cross-tenant-spawn.enabled: false`(默认),true 时全部允许。

**Pro**:实现 1 小时

**Con**:粒度太粗,生产不可用,只是 escape hatch。**判定:不取,但可作为 ACL 临时打开调试用的二级开关。**

## 推荐

**方案 A**(单向 grant 表)+ feature flag(方案 C)作为顶层 kill-switch。

steel-man 反方:做完整 ACL + UI 是 1-2 周工作,占用人力。回应:跨租户子 run 是高敏感能力(supervisor agent 在 X 租户的数据上越权),没有审计 + ACL 直接放开等于裸奔;memory `feedback_no_fake_100_percent_claim` + 安全 review 历史(`project_security_review_2026_04`)说明本项目对租户隔离有红线纪律,不可短打。

## 开放问题(需 owner 决策)

| ID | 问题 | 默认选项 |
|---|---|---|
| Q1 | 选 **A / B / C**? | **A**(+ C 作 kill-switch) |
| Q2 | grant 谁能颁发? | **仅 platform admin**(`platform_admin` role,memory `project_platform_admin_guard`)。tenant admin 不能跨租户授权 |
| Q3 | grant 是否需要 child_tenant **同意**(双签)? | **暂不需**——平台 admin 一方授权即生效;若需要 child 同意改造再加,简化优先 |
| Q4 | grant 粒度:**(parent_tenant, child_tenant)** 还是 **(parent_tenant, child_tenant, parent_agent_code)**? | **租户对** —— Phase 1 简化;agent 粒度若需要,后续加列 |
| Q5 | 审计颗粒度:**spawn 时一行**(child run 创建即写)还是**用量聚合**? | **每次 spawn 一行 audit_log**——可追溯,memory `project_security_review_2026_04` 思路 |
| Q6 | grant 管理 UI:**Phase 1 = DB-only / CLI** vs **完整 admin 页**? | **Phase 1 admin 页(只读 + grant/revoke 表单)**——和 D.5 ShadowRun 同级简洁 |
| Q7 | revoke 行为:**已运行的子 run 终止** vs **新 spawn 拒绝,旧 run 继续** | **新拒旧续**——简化 + 不 surprise 用户;若需"硬止血",加单独 force-revoke 命令 |
| Q8 | grant **过期** 行为:expires_at 到期后 spawn 直接 throw,**还是**有 grace period? | **直接 throw**——不引入 grace 复杂度 |
| Q9 | platform admin 自己派子 run 给业务租户(SYSTEM_TENANT_ID → 业务租户)是否需要 grant? | **需要**——同样规则,no-implicit-platform-bypass(memory `project_platform_admin_guard` 思路)。若要例外,显式 seed `(SYSTEM_TENANT, X, ...)` 行 |
| Q10 | `ParentJoinService.onSessionEnded` 现在 silently 丢弃跨租户事件(memory `feedback_subagent_worktree_verify` 红线)。本 PR 的策略:**ACL 通过的 child→parent 完成事件不再丢弃,正常 emit ChildRunCompletedEvent**;ACL 不通过的还是丢。同意吗? | **同意,默认配套放开** |
| Q11 | `platform.delegate_task` LLM 工具(commit `3057ec40`)如果 LLM 想跨租户派,**走同一 ACL** vs **完全禁止**? | **走同一 ACL**——工具层不应有比直接 spawn 更宽的语义;失败时工具返回结构化错误而非 throw,LLM 看到原因可调整 |
| Q12 | C.1(blocking joinChildRun)PR 已 push,跨租户 join 已抛 `IllegalStateException`。本 PR 是否一并放宽?| **一并放宽**——C.1 + C.2 在合并时 join 检查也走 ACL,保持语义对称(若 C.2 后合,需 rebase 调一行) |

## 验收(方案 A)

- 新表 `ab_cross_tenant_grant` schema.sql + idempotent migration
- `CrossTenantAclService` 接口 + impl(`allows(parent, child, type)`,带缓存,e.g. 60s TTL)
- `SubAgentRunner.spawn` 接通 ACL 检查;失败信息改进
- `ParentJoinService.onSessionEnded` 同步松绑(若 ACL 通过则不再丢事件)
- 审计:每次跨租户 spawn 写一行 `ab_cross_tenant_spawn_audit`(本 PR 新建表;仓内无通用 `ab_audit_log`,同 `ab_query_audit_log` / `ab_command_audit_log` 模式)
- admin 页 `/admin/cross-tenant-grants`:列出 grant + grant 表单 + revoke 按钮
- `platform_admin` role 校验所有 ACL 写操作
- 测试:
  - `CrossTenantAclServiceIntegrationTest`:grant / revoke / expires / no-grant 4 case
  - `SubAgentRunnerCrossTenantIntegrationTest`:no grant → throw / valid grant → spawn / expired grant → throw / revoked grant → throw
  - `ParentJoinServiceCrossTenantIntegrationTest`:grant 通过的事件正常派发
  - 审计日志写入 IT
  - admin 页 vitest + E2E
  - **regression**:同租户 spawn 路径不退化

## Out of scope

- agent-code 粒度 grant(Q4 推迟)
- child_tenant 同意流(Q3 推迟)
- 已运行子 run 强制终止(Q7 推迟)
- grace period(Q8 推迟)
- 跨租户审计 dashboard(独立设计)
- 跨租户 supervisor agent UI 引导/工作流模板(产品设计)

## References

- Backlog: `docs/backlog/2026-05-06-acp-p0-p1-followups.md` §C.2
- SubAgentRunner: `platform/src/main/java/com/auraboot/framework/agent/service/SubAgentRunner.java:178-180`
- ParentJoinService: `platform/src/main/java/com/auraboot/framework/agent/service/ParentJoinService.java:69-72`
- ab_permission schema:`platform/src/main/resources/database/schema.sql:185-220`
- 安全 review 历史:memory `project_security_review_2026_04`
- platform_admin role 模式:memory `project_platform_admin_guard`
