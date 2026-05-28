# Admin Guard v2 与 USP/Memory 后续 backlog 收尾设计（2026-05-07）

## 1. 背景

2026-04-19 Plan C 落地了 `AdminRoleInterceptor`,统一以 `tenant_admin` 拦截 `/api/admin/**`,设计文档（`docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md`）§7 留下 5 个 open question 作为增量。同期 USP / Memory L1L2 / Phase 4 收尾在 `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md` 沉淀了 11 项遗留事项。

本设计针对其中 5 项可独立落地的工作 —— **#2 / #3 / #4 / #5 / #8**,合称"Admin Guard v2 收尾"。其余 6 项被外部触发条件阻塞或暂缓,本 spec 不涵盖。

## 2. 范围

### 2.1 In scope

| backlog | 主题 | 改动面 |
|---|---|---|
| #2 | 引入 `platform_admin` 角色,path-scope 区分跨/单租户 admin 端点 | `AdminRoleInterceptor` + `AdminRoleChecker` + DDL seed |
| #3 | `TimezoneMigrationController` 重命名为 `TenantTimezoneController`,路径迁移 | controller + 测试 + 前端调用 |
| #4 | `AdminRoleChecker` 引入 Caffeine 60s TTL 缓存 | `AdminRoleChecker` 内部包装 |
| #5 | 新增 `ab_admin_action_log` 通用 admin 操作审计表 | DDL + `AdminAuditService` + 拦截器 `afterCompletion` 旁路写 |
| #8 | 修复 `gradle-wrapper.jar` 在 worktree 缺失问题 | `.gitignore` 例外 + `git add` |

### 2.2 Out of scope（本 spec 不涵盖）

- **#1**:USP `--workers>1` 验证 —— 阻塞于 OSS reset-and-init 干净环境跑 parallel 实测
- **#6**:Admin promote-now UI —— 阻塞于 Phase 5 audit 页一并实现
- **#7**:`/api/user/soul-profile/export` GDPR 审计 —— 暂缓,等合规决策
- **#9**:L1L2 Memory Tier 前端 Admin UI —— 阻塞于真实租户启用 schedulers
- **#10**:L2 reader clamp —— 阻塞于任一租户 L2 active count > 5000
- **#11**:L1L2 Phase 5 生产验证 —— 阻塞于首家试点租户 7 天观察

## 3. 架构总览

3 个 worktree,3 个独立 PR,真并行无冲突。

```
主仓库:auraboot/
   │
   ├─ wt-admin-guard-v2/      ── PR-A:#2 + #4 + #5(同改 AdminRoleInterceptor,串行)
   │
   ├─ wt-timezone-rename/     ── PR-B:#3(subagent,controller rename)
   │
   └─ wt-gradle-wrapper/      ── PR-C:#8(subagent,DevEx 极小改动)
```

**冲突分析**:
- PR-A 改 `AdminRoleInterceptor.java` + DDL + 1 个新 Service + 1 个新审计表
- PR-B 改 1 个 controller(rename file)+ web-admin 内 timezone fetch
- PR-C 改 `.gitignore` + 新增 1 个 binary 文件
- 三组完全无文件重叠,合并顺序无要求,independent revert

## 4. PR-A:AdminRoleInterceptor 二轮收紧

### 4.1 #2 platform_admin 角色 + path-scope 路径映射

#### 4.1.1 角色 seed(走 RoleTemplate 路径,非 SQL INSERT)

**架构决策**(2026-05-07 修订):`ab_role` 表的 `id BIGINT` + `pid VARCHAR(26)` 必须由 `UniqueIdGenerator` 生成,且无 UNIQUE(code) 约束;静态 SQL `INSERT` 在多重维度上不工作。改用项目既有的 RoleTemplate 路径——`TenantBootstrapServiceImpl#createRoles` 在租户初始化时按模板创建角色。

修改 `platform/src/main/resources/tenant-templates/default-bootstrap.json`,在 `roles` 数组顶部插入 `platform_admin`(priority=0,优先级高于 tenant_admin=1):

```json
{
  "code": "platform_admin",
  "name": "平台管理员",
  "description": "平台管理员角色,可访问跨租户的基础设施与云配置端点(/api/admin/infrastructure/**, /api/admin/cloud-config/**)",
  "type": "tenant",
  "scopeType": "tenant",
  "priority": 0,
  "isDefault": false,
  "isDeletable": false
}
```

**作用域语义**:`platform_admin` 同 `tenant_admin` 一样是 per-tenant 角色(`tenant_id` 由 bootstrap 设置)。"平台"语义体现在**谁被授予该角色**,而非 schema 层面。security 由"政策上只授予可信用户"保证。优势:`AdminRoleChecker` 现有查询路径无需改动(仍可用 `ur.tenant_id = ? AND r.code = ?`)。

#### 4.1.2 默认 admin 账号同时绑双角色

OSS dev 环境 `scripts/oss-reset-and-init.sh` 在 bootstrap API 完成后追加一条 SQL,给默认 admin 同时绑 `tenant_admin` + `platform_admin`:

```sql
-- 假设默认 admin 走 /api/bootstrap/setup 创建,login_name='admin'
INSERT INTO ab_user_role (user_id, tenant_id, role_id, status, member_id, created_at)
SELECT
    u.id::text AS user_id,
    tm.tenant_id,
    r.id AS role_id,
    'active',
    tm.id AS member_id,
    NOW()
FROM ab_user u
JOIN ab_tenant_member tm ON tm.user_id = u.id::text
JOIN ab_role r ON r.code = 'platform_admin' AND r.tenant_id = tm.tenant_id
WHERE u.login_name = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM ab_user_role ur2
      WHERE ur2.member_id = tm.id AND ur2.role_id = r.id
  );
```

注:具体 `ab_user_role` 列(`user_id` vs `member_id` 双键)以现有 schema 为准,实施时通过 `\d ab_user_role` 验证。

#### 4.1.3 拦截器改造(单一拦截器内做 path-scope)

```java
@Component
public class AdminRoleInterceptor implements HandlerInterceptor {

    // 跨租户路径,需要 platform_admin
    private static final List<PathPattern> PLATFORM_ADMIN_PATHS = List.of(
        PathPattern.parse("/api/admin/infrastructure/**"),
        PathPattern.parse("/api/admin/cloud-config/**")
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = MetaContext.getCurrentUserId();
        if (tenantId == null || StringUtils.isBlank(userId)) {
            return reject(resp, "auth context missing");
        }

        String requiredRole = resolveRequiredRole(req.getRequestURI());
        if (!adminRoleChecker.hasRole(tenantId, userId, requiredRole)) {
            log.warn("admin role missing: user={} tenant={} required={} path={}",
                     userId, tenantId, requiredRole, req.getRequestURI());
            return reject(resp, requiredRole + " required");
        }
        // 把决议出的 role 放到 request attribute,afterCompletion 写审计时取
        req.setAttribute("auraboot.admin.resolved_role", requiredRole);
        req.setAttribute("auraboot.admin.start_time_ms", System.currentTimeMillis());
        return true;
    }

    private String resolveRequiredRole(String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (PathPattern p : PLATFORM_ADMIN_PATHS) {
            if (p.matches(container)) {
                return "platform_admin";
            }
        }
        return "tenant_admin";
    }
}
```

`AdminRoleChecker.isTenantAdmin(...)` 重构为通用 `hasRole(tenantId, userId, roleCode)`,SQL `r.code = 'tenant_admin'` 改为参数化 `r.code = ?`。

### 4.2 #4 Caffeine 60s TTL 缓存

在 `AdminRoleChecker` 内部包一层 Caffeine:

```java
@Component
public class AdminRoleChecker {

    private final JdbcTemplate jdbcTemplate;
    private final Cache<RoleCacheKey, Boolean> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))
        .maximumSize(10_000)
        .recordStats()
        .build();

    public boolean hasRole(long tenantId, String userId, String roleCode) {
        RoleCacheKey key = new RoleCacheKey(tenantId, userId, roleCode);
        return cache.get(key, k -> lookupFromDb(k.tenantId(), k.userId(), k.roleCode()));
    }

    private boolean lookupFromDb(long tenantId, String userId, String roleCode) {
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM ab_user_role ur
              JOIN ab_tenant_member tm ON ur.member_id = tm.id
              JOIN ab_role r ON ur.role_id = r.id
            WHERE tm.user_id = ? AND ur.tenant_id = ? AND r.code = ?
              AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL)
              AND ur.status = 'active'
              AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL)
              AND r.status = 'active'
            """,
            Integer.class, userId, tenantId, roleCode
        );
        return count != null && count > 0;
    }

    private record RoleCacheKey(long tenantId, String userId, String roleCode) {}
}
```

**缓存语义**:
- key:`(tenantId, userId, roleCode)` 三元组
- 驱逐:60s TTL after write + 10K 上限
- 无主动失效:role 变更频率极低,60s 容忍可接受
- `recordStats()` 暴露给 Micrometer,counter 名 `aura.admin.role_check.cache.{hit,miss}`

### 4.3 #5 ab_admin_action_log 通用审计表

#### 4.3.1 DDL

```sql
CREATE TABLE IF NOT EXISTS ab_admin_action_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,            -- "tenant_admin" | "platform_admin"
    path VARCHAR(512) NOT NULL,
    method VARCHAR(8) NOT NULL,
    status INTEGER NOT NULL,                    -- HTTP status from response
    request_body_summary VARCHAR(2048),         -- redacted JSON keys, nullable
    latency_ms INTEGER,                         -- request duration, nullable
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_action_log_tenant_time
    ON ab_admin_action_log (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_action_log_actor_time
    ON ab_admin_action_log (actor_user_id, created_at DESC);
```

**字段说明(决策 3=a+b+c)**:
- `actor_role` —— 当时拦截器决议出的有效角色,审计时直接看出是谁的权限触发的
- `request_body_summary` —— 仅记录 JSON 顶层 keys(不含 value),如 `{"keys":["userId","reason"]}`,避免敏感数据落表
- `latency_ms` —— 顺手做性能观测

#### 4.3.2 写入位置

`AdminRoleInterceptor.afterCompletion()`,无论拦截放行还是拒绝都写一行(401/403 也算 audit 信号):

```java
@Override
public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                            Object handler, Exception ex) {
    Long tenantId = MetaContext.getCurrentTenantId();
    String userId = MetaContext.getCurrentUserId();
    String resolvedRole = (String) req.getAttribute("auraboot.admin.resolved_role");
    Long startMs = (Long) req.getAttribute("auraboot.admin.start_time_ms");

    if (tenantId == null || userId == null) return;  // pre-auth 失败,无可审计身份

    long latencyMs = startMs != null ? System.currentTimeMillis() - startMs : 0L;
    auditService.logAdminAction(
        tenantId, userId,
        resolvedRole != null ? resolvedRole : "unknown",
        req.getRequestURI(), req.getMethod(),
        resp.getStatus(),
        bodySummarizer.summarize(req),
        (int) latencyMs
    );
}
```

#### 4.3.3 AdminAuditService

```java
@Service
public class AdminAuditService {

    private final JdbcTemplate jdbc;

    @Async("adminAuditExecutor")
    public void logAdminAction(Long tenantId, String userId, String role,
                               String path, String method, int status,
                               String bodySummary, int latencyMs) {
        try {
            jdbc.update(
                """
                INSERT INTO ab_admin_action_log
                  (tenant_id, actor_user_id, actor_role, path, method,
                   status, request_body_summary, latency_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                """,
                tenantId, userId, role, path, method,
                status, bodySummary, latencyMs
            );
        } catch (Exception e) {
            log.warn("admin audit insert failed: {}", e.getMessage());
        }
    }
}
```

`adminAuditExecutor` 用一个最小线程池(corePool=2, max=4, queue=1000),避免阻塞请求路径。失败仅打 warn 不抛(audit 是 best-effort,不影响主路径)。

#### 4.3.4 RequestBodySummarizer 脱敏规则

```java
@Component
public class RequestBodySummarizer {

    public String summarize(HttpServletRequest req) {
        if ("GET".equalsIgnoreCase(req.getMethod()) || "DELETE".equalsIgnoreCase(req.getMethod())) {
            return null;
        }
        // 只记录 JSON 顶层 keys,不记录 value
        // 实现:用 ContentCachingRequestWrapper 拿 body 字节,Jackson tree 解析顶层
        // 长度上限 2048,超长截断
        // 解析失败 → 返回 "{\"parse_error\":true}"
    }
}
```

**关键约束**:
- 不记录任何 value(避免脱敏复杂化)
- 仅顶层 keys(嵌套对象的 inner keys 不要,简化实现)
- 解析失败 fallback 到固定标记,不影响审计写入

#### 4.3.5 USP 现有审计表去向

`ab_agent_user_soul_profile_admin_action` **保留**(它含 USP 业务专用字段 `target_user_id` / `reason`),与新表并存。USP 控制器双写:
- 业务语义字段 → 老表(显式 `insertAdminActionAudit()` 调用,代码不变)
- 通用 access trail → 新表(由拦截器自动写,USP 无需感知)

不做表合并。设计哲学:领域审计表(USP)负责"业务语义",通用审计表(`ab_admin_action_log`)负责"操作 trail"。

## 5. PR-B:TimezoneMigrationController 重命名

### 5.1 后端改动

| 旧 | 新 |
|---|---|
| `TimezoneMigrationController.java` | `TenantTimezoneController.java`(`git mv` + 类名 rename) |
| `@RequestMapping("/api/admin/timezone")` | `@RequestMapping("/api/admin/tenants/timezone")` |
| `TimezoneMigrationControllerTest` | `TenantTimezoneControllerTest` |

端点子路径 `/migration-check`、`/tenant-timezone` 不变。

### 5.2 路径映射(AdminRoleInterceptor 兼容)

新路径 `/api/admin/tenants/timezone/**` 仍在 `/api/admin/**` 下,自动走 `AdminRoleInterceptor` + `tenant_admin` gate(单租户语义,与调研一致)。无需在 `PLATFORM_ADMIN_PATHS` 列表登记。

### 5.3 前端改动

```bash
grep -rn "/api/admin/timezone" web-admin/src
# 把所有命中点替换为 /api/admin/tenants/timezone
```

dev 阶段允许破坏性重命名(memory: `feedback_dev_stage_breaking_ok.md`)。**不留 deprecated alias / forwarding stub**。CHANGELOG 同步更新。

## 6. PR-C:gradle-wrapper.jar 跟踪

### 6.1 .gitignore 改动

```diff
 *.jar
+!gradle/wrapper/gradle-wrapper.jar
+!**/gradle/wrapper/gradle-wrapper.jar
```

### 6.2 跟踪 jar

```bash
git add platform/gradle/wrapper/gradle-wrapper.jar
git commit -m "build(gradle): track gradle-wrapper.jar to fix worktree usage"
```

### 6.3 验证

```bash
git ls-files | grep gradle-wrapper.jar
# 期望:platform/gradle/wrapper/gradle-wrapper.jar

# 新建 worktree 验证:
git worktree add /tmp/test-wrapper main
cd /tmp/test-wrapper && ./gradlew --version
# 不应报 "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"
git worktree remove /tmp/test-wrapper
```

## 7. 测试策略

### 7.1 PR-A 测试

#### 后端集成测试

文件:`platform/src/test/java/com/auraboot/framework/application/security/AdminRoleInterceptorIntegrationTest.java`

| 用例 | 描述 | 期望 |
|---|---|---|
| T1 | `tenant_admin` 用户访问 `/api/admin/users` | 200 |
| T2 | `tenant_admin` 用户访问 `/api/admin/infrastructure` | 拒绝(409 admin role required) |
| T3 | `platform_admin` 用户访问 `/api/admin/infrastructure` | 200 |
| T4 | `platform_admin` 用户访问 `/api/admin/users` | 拒绝(单租户接口不接受 platform_admin)<br>**注**:dev seed 同一账号双绑可正常,生产环境需双绑 |
| T5 | 连续 3 次相同 (userId, tenantId, roleCode) hasRole 调用 | 第 1 次 JDBC,第 2-3 次 cache hit;`@SpyBean JdbcTemplate` 验证调用次数 |
| T6 | 缓存 TTL 验证 | 用 `Caffeine.expireAfter(...)` + `Ticker` 注入模拟时钟,前进 61s 后 cache miss |

文件:`platform/src/test/java/com/auraboot/framework/application/security/AdminAuditServiceIntegrationTest.java`

| 用例 | 描述 | 期望 |
|---|---|---|
| T7 | 接收一次 admin GET 请求 | `ab_admin_action_log` 多 1 行,9 字段全部正确 |
| T8 | POST `{"userId":"x","password":"secret"}` | `request_body_summary` = `{"keys":["userId","password"]}`(value 不落表) |
| T9 | tenant_admin 访问 `/api/admin/infrastructure` 被拒 | 审计行 status=409, actor_role="platform_admin"(决议出的 required role) |

#### E2E 测试

文件:`web-admin/tests/e2e/admin/admin-guard-v2.spec.ts`(新增 1 个 spec)

- platform_admin 账号登录 → 进入 Infrastructure 页 → 数据可见
- tenant_admin 账号登录(无 platform_admin) → 访问 Infrastructure 页 → 收到 403/拒绝提示

### 7.2 PR-B 测试

- `TenantTimezoneControllerTest`(rename 自原测试类),路径全部更新为 `/api/admin/tenants/timezone`
- 验证 `/api/admin/tenants/timezone/migration-check` 返回 200
- 全仓 grep `TimezoneMigration` 残留 = 0(除 CHANGELOG / spec)

### 7.3 PR-C 测试

人手验证:
- `git ls-files | grep gradle-wrapper.jar` 输出 1 行
- 新建 worktree `./gradlew --version` 不报 jar 缺失

## 8. Migration / DDL 汇总

`platform/src/main/resources/sql/migrations/2026-05-07_admin_guard_v2.sql`:

```sql
-- #2: platform_admin role
INSERT INTO ab_role (code, name, system_role, status, created_at)
VALUES ('platform_admin', 'Platform Administrator', TRUE, 'active', NOW())
ON CONFLICT (code) DO NOTHING;

-- #5: ab_admin_action_log
CREATE TABLE IF NOT EXISTS ab_admin_action_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    path VARCHAR(512) NOT NULL,
    method VARCHAR(8) NOT NULL,
    status INTEGER NOT NULL,
    request_body_summary VARCHAR(2048),
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_admin_action_log_tenant_time
    ON ab_admin_action_log (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_action_log_actor_time
    ON ab_admin_action_log (actor_user_id, created_at DESC);
```

## 9. 验收标准

### PR-A
- [ ] `platform_admin` 角色 SQL seed 通过 reset-and-init 自动执行
- [ ] AdminRoleInterceptor path-scope 决议正确(infrastructure / cloud-config = platform_admin,其余 = tenant_admin)
- [ ] AdminRoleChecker Caffeine 60s TTL 生效,Micrometer counter `aura.admin.role_check.cache.{hit,miss}` 有数据
- [ ] `ab_admin_action_log` 表创建,9 字段齐全,2 个索引存在
- [ ] 拦截器 `afterCompletion` 异步写审计(`@Async`),200/401/403 都写
- [ ] `request_body_summary` 仅记录顶层 keys 不记录 value
- [ ] 集成测试 T1-T9 全绿
- [ ] 1 个 E2E spec 跑通

### PR-B
- [ ] `TenantTimezoneController` 在 `/api/admin/tenants/timezone/**`
- [ ] 旧 `TimezoneMigrationController.java` 已 `git rm`
- [ ] 前端 `web-admin/src` 内所有 `/api/admin/timezone` 引用更新
- [ ] 集成测试通过
- [ ] grep 全仓 `TimezoneMigration` 残留 = 0(除 CHANGELOG / spec)

### PR-C
- [ ] `git ls-files | grep gradle-wrapper.jar` 输出 1 行
- [ ] 新 worktree `./gradlew --version` 不报 jar 缺失

### 共同
- [ ] 更新 `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md`,#2/#3/#4/#5/#8 标记为 ✅ closed,链接到对应 PR
- [ ] CHANGELOG 同步(每个 PR 各自更新)

## 10. Non-Goals

- **不**实现"按 path 频率告警"(可观测性留给后续 Grafana panel,本 spec 不涵盖)
- **不**调整 USP 现有专用审计表(并存策略)
- **不**为 `request_body_summary` 实现可配置脱敏规则(目前简单的"only top-level keys, no values"够用,复杂场景留 follow-up)
- **不**给 `AdminRoleInterceptor` 加主动 cache invalidation(role 变更事件总线不存在,60s TTL 足够)

## 11. 风险与权衡

| 风险 | 说明 | 缓解 |
|---|---|---|
| platform_admin 角色拆出后,existing tenant 有现成跨租户 admin 操作 → 401 | dev 阶段允许破坏性,但 OSS reset-and-init 默认 admin 双绑可避免 | OSS init 数据脚本同时绑双角色 |
| Caffeine 缓存键设计:`(tenantId, userId, roleCode)` 三元组 | 用户在另一 tenant 的 role 变更不会立即反映 | 60s TTL 足够,管理面操作低频可接受 |
| 异步审计写失败 → audit 行丢失 | `@Async` 线程池 reject / DB 异常会丢一条 | warn 日志可观测,后续补"审计写失败 counter"(Non-Goal,留 follow-up) |
| `RequestBodySummarizer` 解析超长 body OOM | body > 2048 截断 | `ContentCachingRequestWrapper` 配 maxPayloadLength 限制 |
| TimezoneMigration 重命名破坏外部调用 | 前端是唯一已知调用方 | grep web-admin + 全仓搜 `/api/admin/timezone`,确认无遗漏 |
| gradle-wrapper.jar 跟踪后 git lfs 风险 | 单文件 ~60KB,普通 git blob 即可 | 不引入 LFS;若未来体积膨胀再迁 |

## 12. 实施完成后续动作

1. 更新 `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md`:
   - #2/#3/#4/#5/#8 标记 ✅ closed,加 PR 链接
   - #3 备注修正:实际是单租户 controller rename,而非 platform_admin 迁移(调研发现)
2. CHANGELOG 同步(三个 PR 各自更新)
3. 与 Plan C(`docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md`)关联 cross-link
4. memory 增补一条 `project_admin_guard_v2_2026_05_07.md`
