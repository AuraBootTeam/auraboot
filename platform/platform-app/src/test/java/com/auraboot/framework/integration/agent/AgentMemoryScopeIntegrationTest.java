package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the scope-visibility contract on {@code ab_agent_memory} (memory-lifecycle
 * §2.2): a principal identified by (tenantId, userId) may only see a memory when
 *   scope='global'
 *   OR (scope='tenant' AND tenant_id matches)
 *   OR (scope='user'   AND scope_key matches)
 *
 * Also covers GDPR forgetUser and scope validation on create.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AgentMemoryService — scope enforcement (PR-13)")
class AgentMemoryScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentMemoryService memoryService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantA;
    private Long tenantB;
    private String userA;
    private String userB;
    private String agent;

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantA = 9_100_000L + base;
        tenantB = 9_200_000L + base;
        userA   = "user_a_" + base;
        userB   = "user_b_" + base;
        agent   = "mem_test_agent_" + base;
        MetaContext.setContext(tenantA, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE memory_agent_id = ?", agent);
    }

    // -----------------------------------------------------------------------
    // create validation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("createScopedMemory rejects unknown scope values")
    void create_rejects_invalid_scope() {
        assertThatThrownBy(() -> memoryService.createScopedMemory(
                tenantA, agent, "fact", "agent", "t", "c", 5, false,
                "organization", "whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid memory scope");
    }

    @Test
    @DisplayName("createScopedMemory rejects scope=user with blank scope_key")
    void create_user_scope_requires_key() {
        assertThatThrownBy(() -> memoryService.createScopedMemory(
                tenantA, agent, "fact", "agent", "t", "c", 5, false,
                "user", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope_key");
    }

    // -----------------------------------------------------------------------
    // visibility contract
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("user-scoped memory is invisible to a different user in the same tenant")
    void user_scope_isolates_users() {
        memoryService.createScopedMemory(
                tenantA, agent, "preference", "user",
                "userA likes blue", "color=blue", 8, false,
                "user", userA);

        // userA sees it
        List<Map<String, Object>> resA = memoryService.searchScoped(tenantA, userA, agent, "blue", 10);
        assertThat(resA).hasSize(1);
        assertThat(resA.get(0).get("scope")).isEqualTo("user");
        assertThat(resA.get(0).get("scope_key")).isEqualTo(userA);

        // userB does not
        List<Map<String, Object>> resB = memoryService.searchScoped(tenantA, userB, agent, "blue", 10);
        assertThat(resB).isEmpty();
    }

    @Test
    @DisplayName("tenant-scoped memory is visible to all users in the tenant, not to other tenants")
    void tenant_scope_isolates_tenants() {
        memoryService.createScopedMemory(
                tenantA, agent, "fact", "agent",
                "tenantA policy", "policy text", 7, false,
                "tenant", null);

        // any user in tenant A sees it
        assertThat(memoryService.searchScoped(tenantA, userA, agent, "policy", 10)).hasSize(1);
        assertThat(memoryService.searchScoped(tenantA, userB, agent, "policy", 10)).hasSize(1);

        // tenant B does not
        assertThat(memoryService.searchScoped(tenantB, userA, agent, "policy", 10)).isEmpty();
    }

    @Test
    @DisplayName("global memory is visible across all tenants and users")
    void global_scope_is_universal() {
        memoryService.createScopedMemory(
                tenantA, agent, "fact", "agent",
                "platform fact", "everyone should know", 5, false,
                "global", null);

        assertThat(memoryService.searchScoped(tenantA, userA, agent, "platform", 10)).hasSize(1);
        assertThat(memoryService.searchScoped(tenantA, userB, agent, "platform", 10)).hasSize(1);
        assertThat(memoryService.searchScoped(tenantB, userA, agent, "platform", 10)).hasSize(1);
    }

    @Test
    @DisplayName("loadScopedByImportance respects the same scope filter as searchScoped")
    void load_by_importance_is_scope_filtered() {
        memoryService.createScopedMemory(
                tenantA, agent, "preference", "user", "U1", "user A secret", 10, false, "user", userA);
        memoryService.createScopedMemory(
                tenantA, agent, "fact",       "agent", "T1", "tenant A fact",  9, false, "tenant", null);
        memoryService.createScopedMemory(
                tenantB, agent, "fact",       "agent", "T2", "tenant B fact",  9, false, "tenant", null);
        memoryService.createScopedMemory(
                tenantA, agent, "fact",       "agent", "G1", "global fact",    8, false, "global", null);

        // user A in tenant A should see: U1 + T1 + G1 = 3 (ordered by importance DESC)
        List<Map<String, Object>> visible = memoryService.loadScopedByImportance(tenantA, userA, agent, 20);
        assertThat(visible).hasSize(3);
        assertThat(visible.get(0).get("memory_title")).isEqualTo("U1");   // importance 10
        assertThat(visible.get(1).get("memory_title")).isEqualTo("T1");   // importance 9
        assertThat(visible.get(2).get("memory_title")).isEqualTo("G1");   // importance 8
    }

    // -----------------------------------------------------------------------
    // GDPR forget-user
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("forgetUser soft-deletes exactly the scope=user rows for the given user_id")
    void forget_user_is_targeted() {
        memoryService.createScopedMemory(tenantA, agent, "preference", "user", "UA-1", "A1", 7, false, "user", userA);
        memoryService.createScopedMemory(tenantA, agent, "preference", "user", "UA-2", "A2", 6, false, "user", userA);
        memoryService.createScopedMemory(tenantA, agent, "preference", "user", "UB-1", "B1", 7, false, "user", userB);
        memoryService.createScopedMemory(tenantA, agent, "fact",       "agent", "T1",  "T1", 5, false, "tenant", null);
        memoryService.createScopedMemory(tenantA, agent, "fact",       "agent", "G1",  "G1", 5, false, "global", null);

        int deleted = memoryService.forgetUser(tenantA, userA);
        assertThat(deleted).isEqualTo(2);

        // userA now sees only tenant + global (his user rows are gone)
        List<Map<String, Object>> residualA = memoryService.loadScopedByImportance(tenantA, userA, agent, 20);
        assertThat(residualA).hasSize(2);
        assertThat(residualA).extracting(r -> r.get("memory_title"))
                .containsExactlyInAnyOrder("T1", "G1");

        // userB is unaffected
        List<Map<String, Object>> residualB = memoryService.loadScopedByImportance(tenantA, userB, agent, 20);
        assertThat(residualB).hasSize(3);  // UB-1 + T1 + G1
    }

    @Test
    @DisplayName("forgetUser is tenant-scoped — GDPR in tenant A does not erase tenant B")
    void forget_user_is_tenant_scoped() {
        String sharedUserId = "collision_" + System.nanoTime();
        memoryService.createScopedMemory(tenantA, agent, "preference", "user",
                "tenantA pref", "A-side", 7, false, "user", sharedUserId);
        memoryService.createScopedMemory(tenantB, agent, "preference", "user",
                "tenantB pref", "B-side", 7, false, "user", sharedUserId);

        int deleted = memoryService.forgetUser(tenantA, sharedUserId);
        assertThat(deleted).as("only tenantA's row should be deleted").isEqualTo(1);

        // tenantB's row for the same user_id remains intact
        Integer liveInB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory WHERE tenant_id = ? AND scope_key = ? " +
                        "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                Integer.class, tenantB, sharedUserId);
        assertThat(liveInB).isEqualTo(1);
    }

    @Test
    @DisplayName("forgetUser rejects blank user id / null tenant id")
    void forget_user_requires_id() {
        assertThatThrownBy(() -> memoryService.forgetUser(tenantA, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memoryService.forgetUser(tenantA, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> memoryService.forgetUser(null, userA))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // null-userId match hole (M1 fix)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("null/blank userId does NOT match a dirty row whose scope_key='' (system caller)")
    void null_user_does_not_match_blank_scope_key() {
        // Simulate an upstream-bug row that slipped past createScopedMemory's
        // validation and stored a literal blank scope_key at scope='user'.
        jdbc.update("INSERT INTO ab_agent_memory " +
                "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                " memory_title, memory_content, importance, shareable, " +
                " scope, scope_key, created_at, updated_at, deleted_flag) " +
                "VALUES (?, ?, ?, 'fact', 'agent', 'dirty', 'dirty row', 7, FALSE, " +
                " 'user', '', NOW(), NOW(), FALSE)",
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                tenantA, agent);

        // A real user-scoped row with a valid scope_key, for sanity.
        memoryService.createScopedMemory(tenantA, agent, "fact", "user",
                "valid", "valid user row", 7, false, "user", userA);

        // System caller (null userId) — must see NEITHER dirty nor userA's row.
        List<Map<String, Object>> sys = memoryService.loadScopedByImportance(tenantA, null, agent, 20);
        assertThat(sys).extracting(r -> r.get("memory_title"))
                .doesNotContain("dirty", "valid");

        // Blank-string userId — same protection.
        List<Map<String, Object>> blank = memoryService.loadScopedByImportance(tenantA, "", agent, 20);
        assertThat(blank).extracting(r -> r.get("memory_title"))
                .doesNotContain("dirty", "valid");

        // The legitimate owner (userA) still sees his row (but still not dirty).
        List<Map<String, Object>> owner = memoryService.loadScopedByImportance(tenantA, userA, agent, 20);
        assertThat(owner).extracting(r -> r.get("memory_title")).contains("valid");
        assertThat(owner).extracting(r -> r.get("memory_title")).doesNotContain("dirty");
    }

    // -----------------------------------------------------------------------
    // default scope backward-compat
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("legacy createMemory falls back to scope='tenant' via DDL default")
    void legacy_create_defaults_scope_to_tenant() {
        String pid = memoryService.createMemory(
                tenantA, agent, "fact", "agent", "legacy", "legacy content", 5, false);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT scope, scope_key FROM ab_agent_memory WHERE pid = ?", pid);
        assertThat(row.get("scope")).isEqualTo("tenant");
        assertThat(row.get("scope_key")).isNull();

        // legacy rows remain visible via the scoped API (tenant scope matches)
        List<Map<String, Object>> visible = memoryService.searchScoped(tenantA, userA, agent, "legacy", 10);
        assertThat(visible).hasSize(1);
    }
}
