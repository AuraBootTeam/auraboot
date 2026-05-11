# Admin Guard v2 与 USP/Memory 后续 backlog 收尾 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md` 中定义的 5 项 backlog 收尾(#2 platform_admin / #3 timezone rename / #4 Caffeine cache / #5 通用 admin 审计 / #8 gradle wrapper)。

**Architecture:** 3 个 git worktree 各跑 1 个独立 PR;PR-A 串行落地 #2/#4/#5(同改 `AdminRoleInterceptor`);PR-B/PR-C 并行 subagent 推进。每项任务 TDD,先写失败测试再实现。

**Tech Stack:** Java 21 / Spring Boot 3 / Caffeine / PostgreSQL JDBC / JUnit 5 + AssertJ / Playwright

---

## Phase 0:Worktree 创建(主对话执行)

### Task 0.1:为 PR-A 创建 worktree

**Files:**
- Create branch: `feat/admin-guard-v2`
- Worktree: `/Users/ghj/work/auraboot-wt/admin-guard-v2`

- [ ] **Step 1: 在 auraboot 主仓库创建 worktree**

```bash
cd /Users/ghj/work/auraboot/auraboot
git worktree add -b feat/admin-guard-v2 /Users/ghj/work/auraboot-wt/admin-guard-v2 main
```

期望:输出 `Preparing worktree (new branch 'feat/admin-guard-v2')` + `HEAD is now at <sha> ...`

- [ ] **Step 2: 验证 gradle wrapper jar 存在(若不存在,从主仓库 cp 过来)**

```bash
ls /Users/ghj/work/auraboot-wt/admin-guard-v2/platform/gradle/wrapper/gradle-wrapper.jar \
  || cp /Users/ghj/work/auraboot/auraboot/platform/gradle/wrapper/gradle-wrapper.jar \
        /Users/ghj/work/auraboot-wt/admin-guard-v2/platform/gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 3: 把设计 spec 复制到该 worktree(便于在分支内追溯)**

```bash
cp /Users/ghj/work/auraboot/auraboot/docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md \
   /Users/ghj/work/auraboot-wt/admin-guard-v2/docs/plans/2026-05/
```

注:`docs/plans/2026-05/` 在 worktree 中可能不存在,先 `mkdir -p`。该 spec 会随 PR-A 一起进入 main。

### Task 0.2:为 PR-B 创建 worktree

- [ ] **Step 1: 创建 worktree**

```bash
cd /Users/ghj/work/auraboot/auraboot
git worktree add -b feat/timezone-rename /Users/ghj/work/auraboot-wt/timezone-rename main
```

- [ ] **Step 2: 同上 cp gradle-wrapper.jar(若 PR-C 还没合并)**

```bash
cp /Users/ghj/work/auraboot/auraboot/platform/gradle/wrapper/gradle-wrapper.jar \
   /Users/ghj/work/auraboot-wt/timezone-rename/platform/gradle/wrapper/gradle-wrapper.jar
```

### Task 0.3:为 PR-C 创建 worktree

- [ ] **Step 1: 创建 worktree**

```bash
cd /Users/ghj/work/auraboot/auraboot
git worktree add -b feat/gradle-wrapper-tracked /Users/ghj/work/auraboot-wt/gradle-wrapper main
```

PR-C 不需要 cp jar(它的工作就是把 jar 加进 git tracking)。

---

## Phase 1:PR-A — AdminRoleInterceptor 二轮收紧(串行)

**工作目录:** `/Users/ghj/work/auraboot-wt/admin-guard-v2`

> 本 phase 全部任务都在 PR-A worktree 中串行执行。原因:#2 / #4 / #5 三项都改同一份 `AdminRoleInterceptor.java`,串行避免合并冲突。

### Task A.1:DDL migration 文件(#2 + #5 schema)

**Files:**
- Create: `platform/src/main/resources/database/migrations/2026-05-07_admin_guard_v2.sql`

- [ ] **Step 1: 创建 migration 文件**

```sql
-- =============================================================================
-- Admin Guard v2 (2026-05-07)
-- - #2 platform_admin role
-- - #5 generic admin action audit log
-- =============================================================================

-- (#2) platform_admin role
INSERT INTO ab_role (code, name, system_role, status, created_at)
VALUES ('platform_admin', 'Platform Administrator', TRUE, 'active', NOW())
ON CONFLICT (code) DO NOTHING;

-- (#5) ab_admin_action_log generic audit table
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

- [ ] **Step 2: 验证 migration 命名格式与目录中现有文件一致**

```bash
ls /Users/ghj/work/auraboot-wt/admin-guard-v2/platform/src/main/resources/database/migrations/ | tail -5
```

期望:命名约定 `YYYY-MM-DD_xxx.sql` 或 `Vxxx__xxx.sql`(若现有都是 V-prefix 风格,则需把文件改为 `V<next>__admin_guard_v2.sql`,与现有保持一致)

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
git add platform/src/main/resources/database/migrations/2026-05-07_admin_guard_v2.sql
git commit -m "feat(admin-guard): add platform_admin role and ab_admin_action_log DDL"
```

---

### Task A.2:`AdminRoleChecker.hasRole()` 通用化(#2 基础)

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/application/security/AdminRoleChecker.java`
- Test: `platform/src/test/java/com/auraboot/framework/application/security/AdminRoleCheckerIntegrationTest.java`(新建,如不存在)

- [ ] **Step 1: 写失败测试**

```java
package com.auraboot.framework.application.security;

import com.auraboot.framework.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRoleCheckerIntegrationTest extends BaseIntegrationTest {

    @Autowired AdminRoleChecker checker;

    @Test
    void hasRole_returnsTrue_whenUserHasTenantAdminRole() {
        // Given a user with tenant_admin role (use BaseIntegrationTest seed admin)
        long tenantId = getDefaultTenantId();
        String adminUserId = getDefaultAdminUserId();

        // When
        boolean result = checker.hasRole(tenantId, adminUserId, "tenant_admin");

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void hasRole_returnsFalse_whenUserLacksRole() {
        long tenantId = getDefaultTenantId();
        String adminUserId = getDefaultAdminUserId();

        // platform_admin 角色尚未绑给 admin(将由 Task A.8 处理),此处应返回 false
        boolean result = checker.hasRole(tenantId, adminUserId, "platform_admin");

        assertThat(result).isFalse();
    }

    @Test
    void hasRole_returnsFalse_forUnknownRoleCode() {
        long tenantId = getDefaultTenantId();
        String adminUserId = getDefaultAdminUserId();

        boolean result = checker.hasRole(tenantId, adminUserId, "nonexistent_role");

        assertThat(result).isFalse();
    }
}
```

注:`getDefaultTenantId()` / `getDefaultAdminUserId()` 是 `BaseIntegrationTest` 已提供的 helper(若不存在,在 helper 中新增,从 `MetaContext` 默认 seed 读取)。

- [ ] **Step 2: 跑测试,确认失败**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
LOG=/tmp/test-A2-$(date +%Y%m%d-%H%M%S).log
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleCheckerIntegrationTest \
  2>&1 | tee "$LOG"
echo "Log saved to $LOG"
```

期望:第 1 个测试 PASS(原 `isTenantAdmin` 逻辑可用)、第 2 个测试 FAIL(`hasRole` 方法不存在,编译错误)

- [ ] **Step 3: 实现 `hasRole()`**

读 `AdminRoleChecker.java` 现有内容,把 `isTenantAdmin(long tenantId, String userId)` 重构为 `hasRole(long tenantId, String userId, String roleCode)`:

```java
package com.auraboot.framework.application.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminRoleChecker {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Checks whether (userId, tenantId) has the given role code (active, non-deleted).
     */
    public boolean hasRole(long tenantId, String userId, String roleCode) {
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

    /**
     * @deprecated use {@link #hasRole(long, String, String)} with code "tenant_admin".
     */
    @Deprecated(forRemoval = true)
    public boolean isTenantAdmin(long tenantId, String userId) {
        return hasRole(tenantId, userId, "tenant_admin");
    }
}
```

注:`@Deprecated(forRemoval = true)` 临时保留 `isTenantAdmin` 兼容当前唯一调用方(`AdminRoleInterceptor`),Task A.4 改完拦截器后删除。

- [ ] **Step 4: 跑测试,确认全 PASS**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleCheckerIntegrationTest \
  2>&1 | tee /tmp/test-A2-pass.log
```

期望:3 个测试全 PASS

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminRoleChecker.java
git add platform/src/test/java/com/auraboot/framework/application/security/AdminRoleCheckerIntegrationTest.java
git commit -m "refactor(admin-guard): generalize AdminRoleChecker.hasRole(roleCode)"
```

---

### Task A.3:Caffeine 缓存包 `AdminRoleChecker.hasRole()`(#4)

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/application/security/AdminRoleChecker.java`
- Modify: `platform/src/test/java/com/auraboot/framework/application/security/AdminRoleCheckerIntegrationTest.java`
- Modify: `platform/build.gradle`(若 Caffeine 依赖未引入)

- [ ] **Step 1: 检查 Caffeine 依赖**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
grep -n "caffeine" platform/build.gradle*
```

若未找到,在 `platform/build.gradle` 的 `dependencies {}` 块中添加:

```gradle
implementation 'com.github.ben-manes.caffeine:caffeine:3.1.8'
```

(若项目用 BOM/Spring Boot starter,可能 Caffeine 已经被 `spring-boot-starter-cache` 间接引入;直接 grep `import com.github.benmanes.caffeine` 看现有用法)

- [ ] **Step 2: 写失败测试(缓存命中验证)**

在 `AdminRoleCheckerIntegrationTest.java` 中追加:

```java
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 在已有类中添加:
@SpyBean JdbcTemplate jdbcTemplate;

@Test
void hasRole_secondCallHitsCache_skipsJdbc() {
    long tenantId = getDefaultTenantId();
    String adminUserId = getDefaultAdminUserId();

    clearInvocations(jdbcTemplate);

    // First call → cache miss → JDBC
    boolean r1 = checker.hasRole(tenantId, adminUserId, "tenant_admin");
    // Second call → cache hit → no JDBC
    boolean r2 = checker.hasRole(tenantId, adminUserId, "tenant_admin");

    assertThat(r1).isTrue();
    assertThat(r2).isTrue();
    verify(jdbcTemplate, times(1)).queryForObject(any(String.class), eq(Integer.class), any(), any(), any());
}
```

- [ ] **Step 3: 跑测试,确认 cache miss 测试 FAIL**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleCheckerIntegrationTest \
  2>&1 | tee /tmp/test-A3-fail.log
```

期望:`hasRole_secondCallHitsCache_skipsJdbc` FAIL,`verify(..., times(1))` 实际为 2 次

- [ ] **Step 4: 实现 Caffeine 缓存**

修改 `AdminRoleChecker.java`:

```java
package com.auraboot.framework.application.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;

@Component
public class AdminRoleChecker {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final Cache<RoleCacheKey, Boolean> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(60))
        .maximumSize(10_000)
        .recordStats()
        .build();

    public AdminRoleChecker(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerCacheMetrics() {
        meterRegistry.gauge("aura.admin.role_check.cache.hit",
            cache, c -> c.stats().hitCount());
        meterRegistry.gauge("aura.admin.role_check.cache.miss",
            cache, c -> c.stats().missCount());
        meterRegistry.gauge("aura.admin.role_check.cache.size",
            cache, Cache::estimatedSize);
    }

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

    @Deprecated(forRemoval = true)
    public boolean isTenantAdmin(long tenantId, String userId) {
        return hasRole(tenantId, userId, "tenant_admin");
    }

    private record RoleCacheKey(long tenantId, String userId, String roleCode) {}
}
```

- [ ] **Step 5: 跑测试,确认全 PASS**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleCheckerIntegrationTest \
  2>&1 | tee /tmp/test-A3-pass.log
```

期望:4 个测试全 PASS

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminRoleChecker.java
git add platform/src/test/java/com/auraboot/framework/application/security/AdminRoleCheckerIntegrationTest.java
[ -f platform/build.gradle ] && git add platform/build.gradle
git commit -m "feat(admin-guard): cache AdminRoleChecker.hasRole with 60s Caffeine TTL"
```

---

### Task A.4:`AdminRoleInterceptor` path-scope 决议(#2 主体)

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/application/security/AdminRoleInterceptor.java`
- Test: `platform/src/test/java/com/auraboot/framework/application/security/AdminRoleInterceptorIntegrationTest.java`(新建)

- [ ] **Step 1: 写失败测试(用 MockMvc 验证 path-scope)**

```java
package com.auraboot.framework.application.security;

import com.auraboot.framework.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminRoleInterceptorIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void tenantAdmin_canAccess_userListEndpoint() throws Exception {
        // BaseIntegrationTest 默认 admin 已绑 tenant_admin
        mockMvc.perform(get("/api/admin/users")
                .with(authedAsDefaultAdmin()))
            .andExpect(status().isOk());
    }

    @Test
    void tenantAdmin_isRejected_onInfrastructureEndpoint() throws Exception {
        // 默认 admin 此时未绑 platform_admin
        mockMvc.perform(get("/api/admin/infrastructure/status")
                .with(authedAsDefaultAdmin()))
            .andExpect(status().isOk())  // HTTP 200 (业务包装),body 中 code=409
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("platform_admin")));
    }

    @Test
    void tenantAdmin_isRejected_onCloudConfigEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/cloud-config/list")
                .with(authedAsDefaultAdmin()))
            .andExpect(jsonPath("$.code").value(409));
    }
}
```

注:`authedAsDefaultAdmin()` 是 `BaseIntegrationTest` 提供的 helper,若不存在则参考既有 controller 测试中 MockMvc 的 auth 注入方式;实际请求路径需匹配现有 controller(`/api/admin/infrastructure/status`、`/api/admin/cloud-config/list` 是举例,先 `grep "@GetMapping|@PostMapping" InfrastructureController.java` 找一个真实端点)。

- [ ] **Step 2: 跑测试,确认 FAIL**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleInterceptorIntegrationTest \
  2>&1 | tee /tmp/test-A4-fail.log
```

期望:`tenantAdmin_isRejected_onInfrastructureEndpoint` FAIL(返回 200 因为现有逻辑只查 tenant_admin,会放行)

- [ ] **Step 3: 实现 path-scope 决议**

读现有 `AdminRoleInterceptor.java`,改为:

```java
package com.auraboot.framework.application.security;

import com.auraboot.framework.application.context.MetaContext;
import com.auraboot.framework.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final String ATTR_RESOLVED_ROLE = "auraboot.admin.resolved_role";
    private static final String ATTR_START_TIME_MS = "auraboot.admin.start_time_ms";

    private static final List<PathPattern> PLATFORM_ADMIN_PATHS = List.of(
        new PathPatternParser().parse("/api/admin/infrastructure/**"),
        new PathPatternParser().parse("/api/admin/cloud-config/**")
    );

    private final AdminRoleChecker adminRoleChecker;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler)
            throws Exception {
        Long tenantId = MetaContext.getCurrentTenantId();
        String userId = MetaContext.getCurrentUserId();

        if (tenantId == null || StringUtils.isBlank(userId)) {
            log.warn("admin endpoint accessed without auth context: path={}", req.getRequestURI());
            return reject(resp, "auth context required");
        }

        String requiredRole = resolveRequiredRole(req.getRequestURI());

        if (!adminRoleChecker.hasRole(tenantId, userId, requiredRole)) {
            log.warn("admin role missing: user={} tenant={} required={} path={}",
                userId, tenantId, requiredRole, req.getRequestURI());
            return reject(resp, requiredRole + " required");
        }

        req.setAttribute(ATTR_RESOLVED_ROLE, requiredRole);
        req.setAttribute(ATTR_START_TIME_MS, System.currentTimeMillis());
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

    private boolean reject(HttpServletResponse resp, String reason) throws Exception {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(409, "admin role required: " + reason);
        objectMapper.writeValue(resp.getWriter(), body);
        return false;
    }
}
```

注:`reject()` 与现有实现完全等价(HTTP 200 + body code=409)。`ApiResponse.fail(...)` 的具体签名以 `grep "class ApiResponse"` 实际确认,签名不一致时调整为 `ApiResponse.error(...)` 或 `new ApiResponse<>(...)` 等。

- [ ] **Step 4: 跑测试,确认 3 个 path-scope 测试全 PASS**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleInterceptorIntegrationTest \
  2>&1 | tee /tmp/test-A4-pass.log
```

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminRoleInterceptor.java
git add platform/src/test/java/com/auraboot/framework/application/security/AdminRoleInterceptorIntegrationTest.java
git commit -m "feat(admin-guard): path-scope role decision (platform_admin vs tenant_admin)"
```

---

### Task A.5:`RequestBodySummarizer` 脱敏摘要

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/application/security/RequestBodySummarizer.java`
- Test: `platform/src/test/java/com/auraboot/framework/application/security/RequestBodySummarizerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.auraboot.framework.application.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySummarizerTest {

    private final RequestBodySummarizer summarizer = new RequestBodySummarizer(new ObjectMapper());

    @Test
    void summarize_returnsNull_forGetRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/users");
        assertThat(summarizer.summarize(req)).isNull();
    }

    @Test
    void summarize_returnsNull_forDeleteRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/admin/users/1");
        assertThat(summarizer.summarize(req)).isNull();
    }

    @Test
    void summarize_returnsTopLevelKeys_forPostRequest() throws Exception {
        MockHttpServletRequest mockReq = new MockHttpServletRequest("POST", "/api/admin/users");
        mockReq.setContent("""
            {"userId":"x","password":"secret","nested":{"inner":1}}
            """.getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mockReq);
        wrapped.getInputStream().readAllBytes(); // populate cache

        String summary = summarizer.summarize(wrapped);

        assertThat(summary).contains("userId", "password", "nested");
        assertThat(summary).doesNotContain("secret", "inner", "1");
    }

    @Test
    void summarize_returnsParseErrorMarker_forInvalidJson() throws Exception {
        MockHttpServletRequest mockReq = new MockHttpServletRequest("POST", "/api/admin/users");
        mockReq.setContent("not-json".getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mockReq);
        wrapped.getInputStream().readAllBytes();

        String summary = summarizer.summarize(wrapped);

        assertThat(summary).isEqualTo("{\"parse_error\":true}");
    }

    @Test
    void summarize_truncates_at2048Chars() throws Exception {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            sb.append("\"key").append(i).append("\":1,");
        }
        sb.append("\"last\":1}");

        MockHttpServletRequest mockReq = new MockHttpServletRequest("POST", "/api/admin/users");
        mockReq.setContent(sb.toString().getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mockReq);
        wrapped.getInputStream().readAllBytes();

        String summary = summarizer.summarize(wrapped);

        assertThat(summary.length()).isLessThanOrEqualTo(2048);
    }
}
```

- [ ] **Step 2: 跑测试确认 FAIL**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.RequestBodySummarizerTest \
  2>&1 | tee /tmp/test-A5-fail.log
```

期望:全部 FAIL(类不存在)

- [ ] **Step 3: 实现 RequestBodySummarizer**

```java
package com.auraboot.framework.application.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RequestBodySummarizer {

    private static final int MAX_LEN = 2048;
    private static final String PARSE_ERROR = "{\"parse_error\":true}";

    private final ObjectMapper objectMapper;

    /**
     * Returns a redacted JSON summary of request body, recording only top-level keys
     * (no values), or null for GET/DELETE/no-body requests.
     */
    public String summarize(HttpServletRequest req) {
        String method = req.getMethod();
        if ("GET".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
            return null;
        }
        if (!(req instanceof ContentCachingRequestWrapper wrapper)) {
            return null;
        }
        byte[] content = wrapper.getContentAsByteArray();
        if (content.length == 0) return null;

        try {
            JsonNode root = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (!root.isObject()) {
                return PARSE_ERROR;
            }
            List<String> keys = new ArrayList<>();
            Iterator<String> it = root.fieldNames();
            while (it.hasNext()) keys.add(it.next());

            String result = objectMapper.writeValueAsString(java.util.Map.of("keys", keys));
            return result.length() > MAX_LEN ? result.substring(0, MAX_LEN) : result;
        } catch (Exception e) {
            log.debug("body summarize failed: {}", e.getMessage());
            return PARSE_ERROR;
        }
    }
}
```

- [ ] **Step 4: 跑测试,确认全 PASS**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.RequestBodySummarizerTest \
  2>&1 | tee /tmp/test-A5-pass.log
```

- [ ] **Step 5: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/RequestBodySummarizer.java
git add platform/src/test/java/com/auraboot/framework/application/security/RequestBodySummarizerTest.java
git commit -m "feat(admin-guard): add RequestBodySummarizer for redacted audit body keys"
```

---

### Task A.6:`AdminAuditService` + 异步线程池(#5 写入路径)

**Files:**
- Create: `platform/src/main/java/com/auraboot/framework/application/security/AdminAuditService.java`
- Create: `platform/src/main/java/com/auraboot/framework/application/security/AdminAuditConfig.java`
- Test: `platform/src/test/java/com/auraboot/framework/application/security/AdminAuditServiceIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.auraboot.framework.application.security;

import com.auraboot.framework.support.BaseIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired AdminAuditService auditService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void logAdminAction_writesRowAsync() {
        long tenantId = getDefaultTenantId();
        String userId = getDefaultAdminUserId();

        auditService.logAdminAction(tenantId, userId, "tenant_admin",
            "/api/admin/users", "GET", 200, null, 42);

        // Async: poll until row exists (timeout 3s)
        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() -> {
                Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM ab_admin_action_log WHERE tenant_id=? AND actor_user_id=? "
                    + "ORDER BY created_at DESC LIMIT 1",
                    tenantId, userId);
                assertThat(row.get("actor_role")).isEqualTo("tenant_admin");
                assertThat(row.get("path")).isEqualTo("/api/admin/users");
                assertThat(row.get("method")).isEqualTo("GET");
                assertThat(row.get("status")).isEqualTo(200);
                assertThat(row.get("latency_ms")).isEqualTo(42);
                assertThat(row.get("request_body_summary")).isNull();
            });
    }

    @Test
    void logAdminAction_persistsRedactedBodySummary() {
        long tenantId = getDefaultTenantId();
        String userId = getDefaultAdminUserId();
        String summary = "{\"keys\":[\"userId\",\"password\"]}";

        auditService.logAdminAction(tenantId, userId, "tenant_admin",
            "/api/admin/users", "POST", 200, summary, 50);

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            String stored = jdbc.queryForObject(
                "SELECT request_body_summary FROM ab_admin_action_log "
                + "WHERE tenant_id=? AND method='POST' ORDER BY created_at DESC LIMIT 1",
                String.class, tenantId);
            assertThat(stored).contains("userId", "password");
            assertThat(stored).doesNotContain("secret");
        });
    }
}
```

- [ ] **Step 2: 跑测试,确认 FAIL**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminAuditServiceIntegrationTest \
  2>&1 | tee /tmp/test-A6-fail.log
```

期望:编译错误(类不存在)

- [ ] **Step 3: 实现 AdminAuditConfig(线程池)**

```java
package com.auraboot.framework.application.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AdminAuditConfig {

    @Bean(name = "adminAuditExecutor")
    public Executor adminAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("admin-audit-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 4: 实现 AdminAuditService**

```java
package com.auraboot.framework.application.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {

    private final JdbcTemplate jdbcTemplate;

    @Async("adminAuditExecutor")
    public void logAdminAction(Long tenantId, String userId, String role,
                               String path, String method, int status,
                               String bodySummary, Integer latencyMs) {
        try {
            jdbcTemplate.update(
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
            log.warn("admin audit insert failed: tenantId={} userId={} path={} err={}",
                tenantId, userId, path, e.getMessage());
        }
    }
}
```

- [ ] **Step 5: 跑测试,确认 2 个测试 PASS**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminAuditServiceIntegrationTest \
  2>&1 | tee /tmp/test-A6-pass.log
```

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminAuditService.java
git add platform/src/main/java/com/auraboot/framework/application/security/AdminAuditConfig.java
git add platform/src/test/java/com/auraboot/framework/application/security/AdminAuditServiceIntegrationTest.java
git commit -m "feat(admin-guard): add AdminAuditService writing to ab_admin_action_log async"
```

---

### Task A.7:拦截器 `afterCompletion` 写审计 + ContentCachingFilter 注册

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/application/security/AdminRoleInterceptor.java`
- Modify: 现有 `WebMvcConfig` 文件(grep `extends WebMvcConfigurer` 找到)
- Test: `platform/src/test/java/com/auraboot/framework/application/security/AdminRoleInterceptorIntegrationTest.java`

- [ ] **Step 1: 写失败测试(扩展 A.4 的测试类)**

在 `AdminRoleInterceptorIntegrationTest.java` 中追加:

```java
@Autowired JdbcTemplate jdbc;

@Test
void afterCompletion_writesAuditRow_forAcceptedRequest() throws Exception {
    long tenantId = getDefaultTenantId();
    String userId = getDefaultAdminUserId();

    mockMvc.perform(get("/api/admin/users").with(authedAsDefaultAdmin()))
        .andExpect(status().isOk());

    Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM ab_admin_action_log WHERE actor_user_id=? AND path=? "
            + "ORDER BY created_at DESC LIMIT 1",
            userId, "/api/admin/users");
        assertThat(row.get("actor_role")).isEqualTo("tenant_admin");
        assertThat(row.get("status")).isEqualTo(200);
    });
}

@Test
void afterCompletion_writesAuditRow_forRejectedRequest() throws Exception {
    long tenantId = getDefaultTenantId();
    String userId = getDefaultAdminUserId();

    mockMvc.perform(get("/api/admin/infrastructure/status").with(authedAsDefaultAdmin()));

    Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT * FROM ab_admin_action_log WHERE actor_user_id=? "
            + "AND path LIKE '/api/admin/infrastructure/%' ORDER BY created_at DESC LIMIT 1",
            userId);
        // resolved required role = platform_admin
        assertThat(row.get("actor_role")).isEqualTo("platform_admin");
        // 业务包装 HTTP 200 + body code 409,但 response.status 仍是 200 → 我们记 200
        // 但 message 中已可看出拒绝
        assertThat(row.get("status")).isEqualTo(200);
    });
}
```

- [ ] **Step 2: 跑测试,确认 FAIL**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleInterceptorIntegrationTest \
  2>&1 | tee /tmp/test-A7-fail.log
```

- [ ] **Step 3: 在 AdminRoleInterceptor 中加 afterCompletion**

修改 `AdminRoleInterceptor.java`,加注入字段 + override:

```java
// 在 class 顶部加字段:
private final AdminAuditService auditService;
private final RequestBodySummarizer bodySummarizer;

// 加 override:
@Override
public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                            Object handler, Exception ex) {
    Long tenantId = MetaContext.getCurrentTenantId();
    String userId = MetaContext.getCurrentUserId();
    if (tenantId == null || StringUtils.isBlank(userId)) {
        return; // pre-auth 已失败,无身份可审计
    }

    String resolvedRole = (String) req.getAttribute(ATTR_RESOLVED_ROLE);
    Long startMs = (Long) req.getAttribute(ATTR_START_TIME_MS);

    int latencyMs = startMs != null
        ? (int) (System.currentTimeMillis() - startMs)
        : 0;

    auditService.logAdminAction(
        tenantId, userId,
        resolvedRole != null ? resolvedRole : "unknown",
        req.getRequestURI(),
        req.getMethod(),
        resp.getStatus(),
        bodySummarizer.summarize(req),
        latencyMs
    );
}
```

注:`@RequiredArgsConstructor` 已在类上,新加的 final 字段会自动注入。

- [ ] **Step 4: 注册 ContentCachingRequestFilter 让 body 可读**

读现有 WebMvcConfig 或 Spring filter 注册位置(grep `OncePerRequestFilter|ContentCachingRequestWrapper`),如果还没有,新建:

```java
package com.auraboot.framework.application.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

@Configuration
public class AdminAuditFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminBodyCacheFilter> adminBodyCacheFilter() {
        FilterRegistrationBean<AdminBodyCacheFilter> reg =
            new FilterRegistrationBean<>(new AdminBodyCacheFilter());
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(0);  // 在 AdminRoleInterceptor 之前
        return reg;
    }

    static class AdminBodyCacheFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                        FilterChain chain) throws ServletException, IOException {
            ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req);
            chain.doFilter(wrapped, resp);
        }
    }
}
```

- [ ] **Step 5: 跑测试,确认全 PASS**

```bash
./gradlew :platform:test --tests \
  com.auraboot.framework.application.security.AdminRoleInterceptorIntegrationTest \
  2>&1 | tee /tmp/test-A7-pass.log
```

- [ ] **Step 6: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminRoleInterceptor.java
git add platform/src/main/java/com/auraboot/framework/application/security/AdminAuditFilterConfig.java
git add platform/src/test/java/com/auraboot/framework/application/security/AdminRoleInterceptorIntegrationTest.java
git commit -m "feat(admin-guard): write ab_admin_action_log via afterCompletion + body filter"
```

---

### Task A.8:OSS reset 脚本给 admin 双绑 platform_admin

**Files:**
- Modify: `scripts/oss-reset-and-init.sh`(在 bootstrap API 调用之后追加 SQL 执行)

- [ ] **Step 1: 读现有 reset 脚本**

```bash
cat /Users/ghj/work/auraboot-wt/admin-guard-v2/scripts/oss-reset-and-init.sh
```

定位到调用 `/api/bootstrap/setup` 后(等待 bootstrap 完成)的位置,追加一个 SQL 执行步骤。

- [ ] **Step 2: 加 post-bootstrap SQL 步骤**

在 bootstrap API 调用成功后,追加:

```bash
echo "Granting platform_admin to default admin user..."
psql "$DATABASE_URL" <<'EOF'
INSERT INTO ab_user_role (user_id, tenant_id, role_id, status, created_at, member_id)
SELECT
    u.id::text AS user_id,
    tm.tenant_id,
    r.id AS role_id,
    'active',
    NOW(),
    tm.id AS member_id
FROM ab_user u
JOIN ab_tenant_member tm ON tm.user_id = u.id::text
JOIN ab_role r ON r.code = 'platform_admin'
WHERE u.login_name = 'admin'
  AND NOT EXISTS (
      SELECT 1 FROM ab_user_role ur2
      WHERE ur2.member_id = tm.id AND ur2.role_id = r.id
  );
EOF
echo "platform_admin granted."
```

注:具体列名 `user_id` / `member_id` 是否同时存在以现有 `ab_user_role` schema 为准(`psql "$DATABASE_URL" -c "\d ab_user_role"` 验证);若仅有一个,则去掉对应 join。`u.id::text` 保险起见做 cast,如果 `ab_tenant_member.user_id` 是 BIGINT 直接对齐去 cast。

- [ ] **Step 3: 本地跑 reset 验证**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
bash scripts/oss-reset-and-init.sh 2>&1 | tee /tmp/reset-A8.log
```

期望:输出 "platform_admin granted." 且无 SQL 错误

- [ ] **Step 4: 验证 admin 用户确实绑了 platform_admin**

```bash
psql "$DATABASE_URL" -c "
SELECT u.login_name, r.code
FROM ab_user u
JOIN ab_tenant_member tm ON tm.user_id = u.id::text
JOIN ab_user_role ur ON ur.member_id = tm.id
JOIN ab_role r ON r.id = ur.role_id
WHERE u.login_name = 'admin'
  AND ur.status = 'active';
"
```

期望:输出至少 2 行(`tenant_admin` + `platform_admin`)

- [ ] **Step 5: Commit**

```bash
git add scripts/oss-reset-and-init.sh
git commit -m "feat(admin-guard): grant platform_admin to default admin in reset-and-init"
```

---

### Task A.9:E2E spec(platform_admin 与 tenant_admin 区分)

**Files:**
- Create: `web-admin/tests/e2e/admin/admin-guard-v2.spec.ts`

- [ ] **Step 1: 写 E2E spec**

```typescript
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from '../helpers/auth';
import { revokeRoleViaApi, grantRoleViaApi } from '../helpers/admin-roles';

test.describe('Admin Guard v2 — path-scope role decision', () => {
  test('platform_admin user can access infrastructure page', async ({ page, request }) => {
    // OSS reset 已给 admin 双绑 platform_admin + tenant_admin,直接验证
    await loginAsAdmin(page);
    await page.goto('/');
    // 从侧边栏点击进入 Infrastructure(若菜单未导出,直接 page.goto 也可,但首选侧边栏)
    await page.click('text=Infrastructure', { timeout: 5000 }).catch(async () => {
      // 菜单标签可能是中文,fallback
      await page.goto('/admin/infrastructure');
    });
    // 数据应正常返回(不显示 403)
    await expect(page.locator('text=403')).toBeHidden();
    await expect(page.locator('text=admin role required')).toBeHidden();
  });

  test('tenant_admin without platform_admin gets rejected on infrastructure', async ({ page, request }) => {
    await loginAsAdmin(page);
    // 暂时撤销 platform_admin
    const adminUserId = await page.evaluate(() => localStorage.getItem('userId'));
    const tenantId = await page.evaluate(() => localStorage.getItem('tenantId'));

    await revokeRoleViaApi(request, adminUserId!, tenantId!, 'platform_admin');

    try {
      const resp = await request.get('/api/admin/infrastructure/status', {
        headers: { Cookie: await page.context().cookies().then(cs =>
          cs.map(c => `${c.name}=${c.value}`).join('; ')) }
      });
      const body = await resp.json();
      expect(body.code).toBe(409);
      expect(body.message).toContain('platform_admin');
    } finally {
      // 恢复角色,避免其他测试受影响
      await grantRoleViaApi(request, adminUserId!, tenantId!, 'platform_admin');
    }
  });
});
```

注:`loginAsAdmin` / `revokeRoleViaApi` / `grantRoleViaApi` helper 若不存在,需在 `web-admin/tests/e2e/helpers/` 下新增。`revoke` / `grant` 的实现可能需要后端支持的 admin role 管理接口;若没有,这个测试可暂时改成"验证 platform_admin 用户的正向 case"+ 后端集成测试覆盖反向 case。

- [ ] **Step 2: 跑 E2E**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2/web-admin
LOG=/tmp/e2e-A9-$(date +%Y%m%d-%H%M%S).log
NO_PROXY=localhost npx playwright test tests/e2e/admin/admin-guard-v2.spec.ts \
  --workers=1 2>&1 | tee "$LOG"
echo "Log saved to $LOG"
```

期望:2 个 test 全 PASS(若 helper 不存在,先实现 helper)

- [ ] **Step 3: Commit**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
git add web-admin/tests/e2e/admin/admin-guard-v2.spec.ts
[ -d web-admin/tests/e2e/helpers ] && git add web-admin/tests/e2e/helpers/admin-roles.ts
git commit -m "test(admin-guard): E2E for platform_admin vs tenant_admin path scopes"
```

---

### Task A.10:跑全量 platform 测试 + 准备 PR

- [ ] **Step 1: 全量 build + test**

```bash
cd /Users/ghj/work/auraboot-wt/admin-guard-v2
./gradlew :platform:build 2>&1 | tee /tmp/build-A10.log
```

期望:BUILD SUCCESSFUL,所有现有测试不退化

- [ ] **Step 2: 全仓 grep 确认无残留 `isTenantAdmin` 调用方**

```bash
grep -rn "isTenantAdmin" platform/src/main/java/ | grep -v AdminRoleChecker.java
```

若有命中(预期为空),决定:
- 如果是 production code:移除调用方改用 `hasRole(...,"tenant_admin")`
- 如果是 test code:同上

然后从 `AdminRoleChecker` 中删除 `@Deprecated` 的 `isTenantAdmin`,并 commit。

- [ ] **Step 3: 删除 deprecated 方法**

修改 `AdminRoleChecker.java`,删除 `isTenantAdmin` 方法及其 `@Deprecated` 注解。

```bash
./gradlew :platform:compileJava 2>&1 | tee /tmp/compile-A10.log
```

期望:编译通过(若有残留调用,先修)

- [ ] **Step 4: Commit cleanup**

```bash
git add platform/src/main/java/com/auraboot/framework/application/security/AdminRoleChecker.java
git commit -m "refactor(admin-guard): drop deprecated AdminRoleChecker.isTenantAdmin"
```

- [ ] **Step 5: Push 并准备 PR**

```bash
git push -u origin feat/admin-guard-v2
gh pr create --title "feat(admin-guard): platform_admin role + Caffeine cache + generic audit log" \
  --body "$(cat <<'EOF'
## Summary
- 新增 `platform_admin` 角色 + path-scope 路径决议(`/api/admin/infrastructure/**` / `/api/admin/cloud-config/**` 要 `platform_admin`,其余仍 `tenant_admin`)
- `AdminRoleChecker.hasRole()` 通用化 + Caffeine 60s TTL 缓存,Micrometer counter 暴露 hit/miss/size
- 新增 `ab_admin_action_log` 通用审计表,拦截器 `afterCompletion` 异步写入(放行/拒绝都写),`request_body_summary` 仅记录顶层 keys 不含 value
- OSS `oss-reset-and-init.sh` 给默认 admin 双绑 platform_admin

关闭 backlog `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md` 第 #2/#4/#5 项。

## Test plan
- [ ] AdminRoleCheckerIntegrationTest 全绿(4 用例,含 cache hit 验证)
- [ ] AdminRoleInterceptorIntegrationTest 全绿(5 用例,含路径决议 + 审计写入)
- [ ] AdminAuditServiceIntegrationTest 全绿(2 用例,异步写入 + 脱敏)
- [ ] RequestBodySummarizerTest 全绿(5 用例)
- [ ] E2E `admin-guard-v2.spec.ts` 全绿
- [ ] OSS reset-and-init 跑通,admin 用户确实绑了 platform_admin

设计文档:`docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md`
EOF
)"
```

---

## Phase 2:PR-B — TimezoneMigrationController 重命名(并行 subagent)

**工作目录:** `/Users/ghj/work/auraboot-wt/timezone-rename`

> 本 phase 可在 PR-A 推进的同时,通过 subagent 并行进行(已确认 web-admin 内无 `/api/admin/timezone` 调用,改动面极小)。

### Task B.1:`git mv` controller 与测试

**Files:**
- Move: `platform/src/main/java/com/auraboot/framework/timezone/controller/TimezoneMigrationController.java`
  → `TenantTimezoneController.java`
- Move: 对应测试类(grep `TimezoneMigrationController` 在 `platform/src/test/java/` 找)

- [ ] **Step 1: 找到测试类完整路径**

```bash
cd /Users/ghj/work/auraboot-wt/timezone-rename
grep -rln "TimezoneMigrationController" platform/src/test/java/
```

记下输出路径(假设 `TimezoneMigrationControllerTest.java` 或 `IntegrationTest.java` 后缀)。

- [ ] **Step 2: git mv 主类**

```bash
git mv platform/src/main/java/com/auraboot/framework/timezone/controller/TimezoneMigrationController.java \
       platform/src/main/java/com/auraboot/framework/timezone/controller/TenantTimezoneController.java
```

- [ ] **Step 3: git mv 测试类**(以 `TimezoneMigrationControllerIntegrationTest.java` 为例,实际以 Step 1 输出为准)

```bash
git mv platform/src/test/java/com/auraboot/framework/timezone/controller/TimezoneMigrationControllerIntegrationTest.java \
       platform/src/test/java/com/auraboot/framework/timezone/controller/TenantTimezoneControllerIntegrationTest.java
```

### Task B.2:类名 + 路径同步替换

**Files:**
- Modify: `platform/src/main/java/com/auraboot/framework/timezone/controller/TenantTimezoneController.java`
- Modify: `platform/src/test/java/com/auraboot/framework/timezone/controller/TenantTimezoneControllerIntegrationTest.java`

- [ ] **Step 1: 替换主类内的类名 + 路径**

读 `TenantTimezoneController.java`,做两处替换:
- `class TimezoneMigrationController` → `class TenantTimezoneController`
- `@RequestMapping("/api/admin/timezone")` → `@RequestMapping("/api/admin/tenants/timezone")`

- [ ] **Step 2: 替换测试类的类名 + 引用**

读 `TenantTimezoneControllerIntegrationTest.java`,做替换:
- `class TimezoneMigrationControllerIntegrationTest` → `class TenantTimezoneControllerIntegrationTest`
- 所有 `/api/admin/timezone` → `/api/admin/tenants/timezone`
- (若导入了 `TimezoneMigrationController` 类型)→ `TenantTimezoneController`

- [ ] **Step 3: 跑该测试,确认 PASS**

```bash
cd /Users/ghj/work/auraboot-wt/timezone-rename
./gradlew :platform:test --tests \
  com.auraboot.framework.timezone.controller.TenantTimezoneControllerIntegrationTest \
  2>&1 | tee /tmp/test-B2.log
```

期望:全 PASS

### Task B.3:全仓 grep 残留 + 编译

- [ ] **Step 1: grep 残留**

```bash
grep -rn "TimezoneMigrationController" /Users/ghj/work/auraboot-wt/timezone-rename/ \
  --include="*.java" --include="*.kt" --include="*.ts" --include="*.tsx" \
  --include="*.json" --include="*.md"
```

期望:输出仅命中 spec 文档 + CHANGELOG(若没改)。任何 `.java` 残留必须修。

- [ ] **Step 2: grep 路径残留**

```bash
grep -rn "/api/admin/timezone" /Users/ghj/work/auraboot-wt/timezone-rename/ \
  --include="*.java" --include="*.ts" --include="*.tsx" --include="*.md" \
  | grep -v "^.*/(spec|design)\.md"
```

期望:除文档外,无产物代码命中。

- [ ] **Step 3: 全 platform 编译**

```bash
./gradlew :platform:compileJava :platform:compileTestJava 2>&1 | tee /tmp/compile-B3.log
```

期望:BUILD SUCCESSFUL,无新增错误。

### Task B.4:Commit + Push + PR

- [ ] **Step 1: Commit**

```bash
git add platform/src/main/java/com/auraboot/framework/timezone/controller/TenantTimezoneController.java
git add platform/src/test/java/com/auraboot/framework/timezone/controller/TenantTimezoneControllerIntegrationTest.java
git commit -m "refactor(timezone): rename TimezoneMigrationController → TenantTimezoneController"
```

- [ ] **Step 2: Push + PR**

```bash
git push -u origin feat/timezone-rename
gh pr create --title "refactor(timezone): rename to TenantTimezoneController + path /api/admin/tenants/timezone" \
  --body "$(cat <<'EOF'
## Summary
- `TimezoneMigrationController` 是单租户操作 `TenantPreference`,而非"平台 ops 一次性迁移"
- 重命名为 `TenantTimezoneController`
- 路径 `/api/admin/timezone/**` → `/api/admin/tenants/timezone/**`
- 仍走 `AdminRoleInterceptor` + `tenant_admin` gate(路径仍在 `/api/admin/**` 下)
- web-admin 内无调用方,无前端改动

关闭 backlog `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md` 第 #3 项。

## Test plan
- [ ] `TenantTimezoneControllerIntegrationTest` 全绿
- [ ] 全仓 grep `TimezoneMigration` / `/api/admin/timezone` 无产物代码残留
EOF
)"
```

---

## Phase 3:PR-C — gradle-wrapper.jar 跟踪(并行 subagent)

**工作目录:** `/Users/ghj/work/auraboot-wt/gradle-wrapper`

### Task C.1:.gitignore 加例外

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: 读 .gitignore 当前 `*.jar` 上下文**

```bash
cd /Users/ghj/work/auraboot-wt/gradle-wrapper
grep -n "\*\.jar" .gitignore
```

记下行号(假设第 58 行)。

- [ ] **Step 2: 在 `*.jar` 之后追加例外**

修改 `.gitignore`,在 `*.jar` 行下方添加两行:

```diff
 *.jar
+!gradle/wrapper/gradle-wrapper.jar
+!**/gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 3: 验证 git check-ignore 反转**

```bash
git check-ignore -v platform/gradle/wrapper/gradle-wrapper.jar
```

期望:**无输出 + exit code 1**(表示文件不再被 ignore)。如果仍输出 ignore 命中,说明例外规则未生效,排查 `.gitignore` 中是否还有更宽的规则。

### Task C.2:把 jar 加进 git tracking

**Files:**
- Add to git: `platform/gradle/wrapper/gradle-wrapper.jar`

- [ ] **Step 1: 确认 jar 文件存在**

```bash
ls -la platform/gradle/wrapper/gradle-wrapper.jar
```

期望:文件存在,通常 60KB 左右。

- [ ] **Step 2: git add**

```bash
git add .gitignore platform/gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 3: 验证 staged**

```bash
git status --short
git ls-files --others --exclude-standard | grep gradle-wrapper
```

期望:`A  .gitignore` + `A  platform/gradle/wrapper/gradle-wrapper.jar` ,且 `ls-files --others` 不再列 jar。

### Task C.3:用新 worktree 验证

- [ ] **Step 1: Commit 当前 staged**

```bash
git commit -m "build(gradle): track gradle-wrapper.jar to fix worktree usage"
```

- [ ] **Step 2: Push,建临时 worktree 验证**

```bash
git push -u origin feat/gradle-wrapper-tracked

# 临时 worktree
TMP_WT=/tmp/test-wrapper-$(date +%s)
cd /Users/ghj/work/auraboot/auraboot
git fetch origin feat/gradle-wrapper-tracked
git worktree add "$TMP_WT" origin/feat/gradle-wrapper-tracked

cd "$TMP_WT"
ls platform/gradle/wrapper/gradle-wrapper.jar  # 应存在
./gradlew --version 2>&1 | head -5             # 不报 wrapper jar 缺失
```

期望:`./gradlew --version` 输出 Gradle 版本,无 "Could not find or load main class org.gradle.wrapper.GradleWrapperMain" 错误。

- [ ] **Step 3: 清理临时 worktree**

```bash
cd /Users/ghj/work/auraboot/auraboot
git worktree remove "$TMP_WT"
```

### Task C.4:开 PR

- [ ] **Step 1: PR**

```bash
cd /Users/ghj/work/auraboot-wt/gradle-wrapper
gh pr create --title "build(gradle): track gradle-wrapper.jar in git to fix worktree usage" \
  --body "$(cat <<'EOF'
## Summary
- 此前 `.gitignore` 的 `*.jar` 规则把 `gradle-wrapper.jar` 一并排除,导致新建 worktree 时 jar 缺失,`./gradlew` 失败
- 加 `.gitignore` 例外:`!gradle/wrapper/gradle-wrapper.jar` + `!**/gradle/wrapper/gradle-wrapper.jar`
- 把 `platform/gradle/wrapper/gradle-wrapper.jar` 加进 git tracking(~60KB binary)

关闭 backlog `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md` 第 #8 项。

## Test plan
- [x] 新 worktree 中 `./gradlew --version` 不报 jar 缺失
- [x] `git ls-files | grep gradle-wrapper.jar` 输出 1 行
EOF
)"
```

---

## Phase 4:Backlog / 内存 / 文档同步(主对话执行,在 3 个 PR 全部 merge 后)

### Task D.1:更新 backlog 文件

**Files:**
- Modify: `docs/backlog/2026-04-19-usp-memory-l1l2-followups.md`

- [ ] **Step 1: 在每项 #2/#3/#4/#5/#8 标题下追加 closure 行**

格式:
```markdown
**Closure (2026-05-07)**:由 PR #<N> ([链接](https://github.com/.../pull/N)) 落地,见 design `docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md`。
```

### 3 额外加注:
```markdown
*Note*:调研发现 `TimezoneMigrationController` 实际是单租户操作 `TenantPreference`(非"平台 ops 一次性迁移"),已重命名为 `TenantTimezoneController`,保留 `tenant_admin` gate。
```

- [ ] **Step 2: Commit + push 到 main**(或随某个 PR 一起)

```bash
cd /Users/ghj/work/auraboot/auraboot
git add docs/backlog/2026-04-19-usp-memory-l1l2-followups.md
git commit -m "docs(backlog): close items #2/#3/#4/#5/#8 from 2026-04-19 follow-ups"
git push origin main
```

### Task D.2:更新 memory

**Files:**
- Create: `/Users/ghj/.claude/projects/-Users-ghj-work-auraboot/memory/project_admin_guard_v2_2026_05_07.md`
- Modify: `/Users/ghj/.claude/projects/-Users-ghj-work-auraboot/memory/MEMORY.md`(加索引)

- [ ] **Step 1: 写新 memory 文件**

内容(frontmatter 占位,实际写时填好):

```markdown
---
name: Admin Guard v2 shipped 2026-05-07
description: 5 项 backlog 收尾(#2 platform_admin / #3 timezone rename / #4 Caffeine / #5 通用 audit / #8 gradle wrapper),3 PR 并行落地
type: project
---

2026-05-07 完成 Plan C §7 的 5 个 open question 中的 4 个,加 backlog #3/#8:
- PR-A `feat/admin-guard-v2`:platform_admin 角色 + path-scope + Caffeine 60s + ab_admin_action_log
- PR-B `feat/timezone-rename`:TimezoneMigrationController → TenantTimezoneController(单租户语义,非平台 ops)
- PR-C `feat/gradle-wrapper-tracked`:.gitignore 例外 + jar tracking
关闭 backlog #2/#3/#4/#5/#8。设计:`docs/plans/2026-05/2026-05-07-admin-guard-v2-and-followups-design.md`。
```

- [ ] **Step 2: 加 MEMORY.md 索引行**

在 MEMORY.md 顶部追加(置于 active 位置):

```markdown
- [Admin Guard v2 shipped 2026-05-07](project_admin_guard_v2_2026_05_07.md) — Plan C §7 收尾 5 项 backlog;3 PR 并行;platform_admin + Caffeine + ab_admin_action_log + timezone rename + gradle wrapper tracked
```

### Task D.3:CHANGELOG 同步

**Files:**
- Modify: `CHANGELOG.md`(若存在;OSS 仓库根目录)

- [ ] **Step 1: 在 Unreleased 段追加**

```markdown
### Added
- `platform_admin` 角色 + 跨租户 admin 路径(`/api/admin/infrastructure/**`、`/api/admin/cloud-config/**`)的 path-scope 决议
- `ab_admin_action_log` 通用 admin 操作审计表(异步写入,records 角色 + 路径 + 状态 + body keys + latency)
- `AdminRoleChecker.hasRole()` Caffeine 60s TTL 缓存,Micrometer counter `aura.admin.role_check.cache.{hit,miss,size}`

### Changed
- `TimezoneMigrationController` → `TenantTimezoneController`,路径 `/api/admin/timezone/**` → `/api/admin/tenants/timezone/**`(无 alias / forwarding stub)
- `AdminRoleChecker.isTenantAdmin()` 删除,改用通用 `hasRole(...,"tenant_admin")`

### Build
- `gradle-wrapper.jar` 加入 git tracking(`.gitignore` 例外),修复 worktree 中 `./gradlew` 缺失 jar 问题
```

### Task D.4:清理 worktree

- [ ] **Step 1: 3 个 PR 全部 merge 后,清理 worktree**

```bash
cd /Users/ghj/work/auraboot/auraboot
git worktree remove /Users/ghj/work/auraboot-wt/admin-guard-v2
git worktree remove /Users/ghj/work/auraboot-wt/timezone-rename
git worktree remove /Users/ghj/work/auraboot-wt/gradle-wrapper

# 删除已 merge 的远程 / 本地分支(可选,确认 merge 后再做)
git fetch --prune origin
git branch -d feat/admin-guard-v2 feat/timezone-rename feat/gradle-wrapper-tracked
```

---

## 自审记录(plan 完成后)

**1. Spec 覆盖**:
- 设计 §4.1 platform_admin → Task A.1(DDL) + A.4(拦截器) + A.8(seed) ✅
- 设计 §4.2 Caffeine cache → Task A.2 + A.3 ✅
- 设计 §4.3 ab_admin_action_log → Task A.1(DDL) + A.5(summarizer) + A.6(service) + A.7(拦截器写入) ✅
- 设计 §5 timezone rename → Task B.1-B.4 ✅
- 设计 §6 gradle wrapper → Task C.1-C.4 ✅
- 设计 §7 测试策略 → 嵌入每个 task 的 TDD 步骤 + Task A.9 E2E ✅
- 设计 §8 migration → Task A.1 ✅
- 设计 §9 验收标准 → 通过每个 task 的 step 验证 + Task A.10 全量 build ✅
- 设计 §12 后续动作 → Task D.1-D.4 ✅

**2. 占位扫描**:
- 无 TBD/TODO
- 所有代码段为完整可粘贴
- 测试代码完整,含 import + assertion

**3. 类型一致性**:
- `AdminRoleChecker.hasRole(long, String, String)` 在 A.2/A.3 一致
- `AdminAuditService.logAdminAction(Long, String, String, String, String, int, String, Integer)` 在 A.6/A.7 调用一致
- `RequestBodySummarizer.summarize(HttpServletRequest)` 在 A.5/A.7 一致
- 字段名:`actor_role` / `request_body_summary` / `latency_ms` 在 DDL / Service / Test / E2E 全部一致
