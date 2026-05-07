package com.auraboot.framework.application.security;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AdminAuditService}.
 *
 * <p>Uses {@code @Transactional(propagation = NEVER)} + {@code @Commit} so that
 * rows committed by the async insert are visible to the Awaitility poll, which
 * runs on the test thread. The standard {@link BaseIntegrationTest} default of
 * {@code @Rollback} would hide async writes because the poll and the insert share
 * no transaction boundary.
 *
 * <p>Cleanup is performed in {@code @AfterEach} via a direct JDBC delete keyed on
 * the per-test {@code tenantId} (a timestamp-based unique value that requires no FK
 * to {@code ab_tenant}), so that test data never persists across runs.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AdminAuditService — async write to ab_admin_action_log")
class AdminAuditServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AdminAuditService auditService;

    @Autowired
    JdbcTemplate jdbc;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_admin_action_log WHERE tenant_id = ?", tenantId);
        }
    }

    @Test
    @DisplayName("logAdminAction writes a row asynchronously with all fields populated")
    void logAdminAction_writesRowAsync() {
        tenantId = System.currentTimeMillis(); // unique per run, no FK to ab_tenant required
        Long userId = 999_001L;

        auditService.logAdminAction(tenantId, userId, "tenant_admin",
                "/api/admin/users", "GET", 200, null, 42);

        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Map<String, Object> row = jdbc.queryForMap(
                            "SELECT * FROM ab_admin_action_log " +
                            "WHERE tenant_id=? AND actor_user_id=? " +
                            "ORDER BY created_at DESC LIMIT 1",
                            tenantId, userId.toString());
                    assertThat(row.get("actor_role")).isEqualTo("tenant_admin");
                    assertThat(row.get("path")).isEqualTo("/api/admin/users");
                    assertThat(row.get("method")).isEqualTo("GET");
                    assertThat(row.get("status")).isEqualTo(200);
                    assertThat(((Number) row.get("latency_ms")).intValue()).isEqualTo(42);
                    assertThat(row.get("request_body_summary")).isNull();
                });
    }

    @Test
    @DisplayName("logAdminAction persists redacted body summary")
    void logAdminAction_persistsRedactedBodySummary() {
        tenantId = System.currentTimeMillis() + 1;
        Long userId = 999_002L;
        String summary = "{\"keys\":[\"userId\",\"password\"]}";

        auditService.logAdminAction(tenantId, userId, "tenant_admin",
                "/api/admin/users", "POST", 200, summary, 50);

        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    String stored = jdbc.queryForObject(
                            "SELECT request_body_summary FROM ab_admin_action_log " +
                            "WHERE tenant_id=? AND method='POST' ORDER BY created_at DESC LIMIT 1",
                            String.class, tenantId);
                    assertThat(stored).contains("userId", "password");
                    assertThat(stored).doesNotContain("secret");
                });
    }
}
