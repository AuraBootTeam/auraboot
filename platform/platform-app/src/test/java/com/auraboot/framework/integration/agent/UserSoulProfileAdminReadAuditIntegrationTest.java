package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.UserSoulProfileAdminController;
import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feat/usp-admin-read-audit — GDPR read-access audit for admin list/stats
 * alongside the pre-existing admin forget audit.
 *
 * <p>Asserts that every admin-facing call ({@code GET /}, {@code GET /stats},
 * {@code POST /forget}) leaves:
 * <ul>
 *   <li>a row in {@code ab_agent_user_soul_profile_admin_action} with the
 *       right {@code action} code (lowercase: {@code list}, {@code stats},
 *       {@code admin_forget}).</li>
 *   <li>a Prometheus counter tick on
 *       {@code auraboot_user_soul_profile_admin_access_total{tenant, action}}.</li>
 * </ul>
 *
 * <p>Complements {@link UserSoulProfileControllerIntegrationTest} (which still
 * covers the pre-existing {@code admin_forget} happy path) by pinning down
 * the previously-silent read-access path.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("UserSoulProfileAdminController read-access audit (GDPR)")
class UserSoulProfileAdminReadAuditIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserSoulProfileAdminController adminController;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = 9_830_000L + System.nanoTime() % 10_000;
        userId = testUser.getId().toString();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_user_soul_profile_admin_action WHERE tenant_id = ?",
                tenantId);
    }

    private String seedActive(Long tenant, String user, int version) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, activated_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'active', ?::jsonb, ?, 0.85, NOW(), NOW())",
                pid, tenant, user, version,
                "{\"persona\":{\"text\":\"engineer\"}}", "h:" + pid);
        return pid;
    }

    private long auditCount(String action) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND action = ?",
                Long.class, tenantId, action);
        return n == null ? 0L : n;
    }

    private double accessCounter(String action) {
        Search search = meterRegistry.find(UserSoulProfileMetrics.ADMIN_ACCESS_TOTAL)
                .tag("tenant", tenantId.toString())
                .tag("action", action);
        if (search.counter() == null) return 0.0d;
        return search.counter().count();
    }

    // =======================================================================
    // list — action=list, target_user_id IS NULL
    // =======================================================================

    @Test
    @DisplayName("Admin GET / inserts audit row with action=list and target_user_id=NULL")
    void list_writesAudit() {
        seedActive(tenantId, userId, 1);

        double before = accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_LIST);
        ApiResponse<List<Map<String, Object>>> r = adminController.list(50);
        assertThat(r.getCode()).isEqualTo("0");

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT acting_admin_id, target_user_id, action, reason " +
                        "FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND action = 'list'",
                tenantId);
        assertThat(audits).hasSize(1);
        Map<String, Object> row = audits.get(0);
        assertThat(row).containsEntry("action", "list")
                .containsEntry("acting_admin_id", testUser.getId().toString());
        // target_user_id is NULL for aggregate-across-users list access.
        assertThat(row.get("target_user_id")).isNull();
        assertThat(row.get("reason")).isNull();

        // Metric incremented for this tenant + action.
        assertThat(accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_LIST))
                .isEqualTo(before + 1.0d);
    }

    @Test
    @DisplayName("Admin GET / twice → two audit rows + metric +2")
    void list_writesAuditPerCall() {
        double before = accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_LIST);
        adminController.list(50);
        adminController.list(50);
        assertThat(auditCount("list")).isEqualTo(2L);
        assertThat(accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_LIST))
                .isEqualTo(before + 2.0d);
    }

    // =======================================================================
    // stats — action=stats, target_user_id IS NULL
    // =======================================================================

    @Test
    @DisplayName("Admin GET /stats inserts audit row with action=stats and target_user_id=NULL")
    void stats_writesAudit() {
        seedActive(tenantId, userId, 1);

        double before = accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_STATS);
        ApiResponse<Map<String, Object>> r = adminController.stats();
        assertThat(r.getCode()).isEqualTo("0");

        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT acting_admin_id, target_user_id, action, reason " +
                        "FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND action = 'stats'",
                tenantId);
        assertThat(audits).hasSize(1);
        Map<String, Object> row = audits.get(0);
        assertThat(row).containsEntry("action", "stats")
                .containsEntry("acting_admin_id", testUser.getId().toString());
        assertThat(row.get("target_user_id")).isNull();

        assertThat(accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_STATS))
                .isEqualTo(before + 1.0d);
    }

    // =======================================================================
    // forget — action=admin_forget, target_user_id set, also ticks admin_access
    // =======================================================================

    @Test
    @DisplayName("Admin POST /forget ticks admin_access{action=admin_forget} counter alongside existing audit row")
    void forget_alsoTicksAdminAccessCounter() {
        String victim = "victim_" + System.nanoTime();
        seedActive(tenantId, victim, 1);

        double beforeAccess = accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_FORGET);
        ApiResponse<Map<String, Object>> r = adminController.forget(
                Map.of("userId", victim, "reason", "gdpr_request"));
        assertThat(r.getCode()).isEqualTo("0");

        // Existing audit row (action=admin_forget) still written.
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT action, target_user_id, reason " +
                        "FROM ab_agent_user_soul_profile_admin_action " +
                        "WHERE tenant_id = ? AND action = 'admin_forget'",
                tenantId);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0))
                .containsEntry("action", "admin_forget")
                .containsEntry("target_user_id", victim)
                .containsEntry("reason", "gdpr_request");

        // Unified admin_access counter ticked with action=admin_forget.
        assertThat(accessCounter(UserSoulProfileAdminController.ACTION_ADMIN_FORGET))
                .isEqualTo(beforeAccess + 1.0d);
    }

    // =======================================================================
    // Isolation — audit rows are tenant-scoped
    // =======================================================================

    @Test
    @DisplayName("Audit rows are tenant-scoped (list in other tenant does not leak here)")
    void auditTenantScoped() {
        Long otherTenant = tenantId + 7_777L;
        MetaContext.setContext(otherTenant, testUser.getId(), testUser.getPid(), testUser.getUserName());
        adminController.list(50);

        // Switch back — no list-audit row in our tenant.
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        assertThat(auditCount("list")).isEqualTo(0L);

        // Cleanup the cross-tenant audit row.
        jdbc.update("DELETE FROM ab_agent_user_soul_profile_admin_action WHERE tenant_id = ?",
                otherTenant);
    }
}
