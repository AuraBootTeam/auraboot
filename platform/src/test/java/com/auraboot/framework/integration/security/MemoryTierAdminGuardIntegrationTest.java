package com.auraboot.framework.integration.security;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * PR-85 / Phase 4 — MemoryTierAdminController guard + happy-path integration.
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §9.2}.
 *
 * <p>Covers:
 * <ol>
 *   <li>Non-admin {@code POST /api/admin/memory/{pid}/promote-now} → {@code code=409}
 *       from {@link com.auraboot.framework.application.security.AdminRoleInterceptor}.</li>
 *   <li>Admin with a valid L1 pid → {@code code=0}, row flipped to {@code category='user'}.</li>
 *   <li>Admin with an already-L2 pid → {@code code=409 / memory_not_l1}.</li>
 * </ol>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@TestPropertySource(properties = {
        "acp.memory.l1l2.admin-promote.enabled=true"
})
@DisplayName("MemoryTierAdminController guard + promote-now (PR-85)")
class MemoryTierAdminGuardIntegrationTest extends BaseIntegrationTest {

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update(
                    "DELETE FROM ab_agent_memory_tier_event WHERE tenant_id = ?", tenantId);
            jdbc.update(
                    "DELETE FROM ab_agent_memory WHERE tenant_id = ? AND memory_agent_id LIKE 'MTA_%'",
                    tenantId);
            AdminGuardTestSupport.cleanupTenant(jdbc, tenantId);
        }
    }

    @Test
    @DisplayName("non-admin POST .../promote-now -> 409")
    void nonAdminBlocked() throws Exception {
        tenantId = 9_960_000L + (System.nanoTime() % 10_000);
        String pid = insertL1(tenantId, String.valueOf(testUser.getId()),
                "non-admin blocked test " + UniqueIdGenerator.generate(), 2);

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext, tenantId, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        mockMvc.perform(post("/api/admin/memory/" + pid + "/promote-now")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"should be blocked\"}"))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("admin role required"));
    }

    @Test
    @DisplayName("admin POST .../promote-now on L1 row -> 200 + category=user")
    void adminPromotesL1() throws Exception {
        tenantId = 9_970_000L + (System.nanoTime() % 10_000);
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        String pid = insertL1(tenantId, String.valueOf(testUser.getId()),
                "admin promote success " + UniqueIdGenerator.generate(), 2);

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext, tenantId, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        mockMvc.perform(post("/api/admin/memory/" + pid + "/promote-now")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"ops pinning for debugging\"}"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.outcome").value("promoted"))
                .andExpect(jsonPath("$.data.target_pid").value(pid));

        String category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, pid);
        assert "user".equals(category) : "expected category=user but was " + category;
    }

    @Test
    @DisplayName("admin POST .../promote-now on already-L2 row -> 409 memory_not_l1")
    void adminAlreadyL2_conflict() throws Exception {
        tenantId = 9_980_000L + (System.nanoTime() % 10_000);
        AdminGuardTestSupport.grantTenantAdmin(jdbc, tenantId, testUser.getId());

        String pid = insertL2(tenantId, String.valueOf(testUser.getId()),
                "already promoted " + UniqueIdGenerator.generate(), 7);

        MockMvc mockMvc = AdminGuardTestSupport.buildMockMvc(
                webApplicationContext, tenantId, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        mockMvc.perform(post("/api/admin/memory/" + pid + "/promote-now")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"try again\"}"))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("memory_not_l1"));
    }

    // ------------------------------------------------------------------

    private String insertL1(Long tenantId, String userId, String content, int importance) {
        return insertWithCategory(tenantId, userId, content, "session", importance);
    }

    private String insertL2(Long tenantId, String userId, String content, int importance) {
        return insertWithCategory(tenantId, userId, content, "user", importance);
    }

    private String insertWithCategory(Long tenantId, String userId,
                                      String content, String category, int importance) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("memory_agent_id", "MTA_" + UniqueIdGenerator.generate());
        row.put("memory_type", "fact");
        row.put("category", category);
        row.put("memory_title", "test");
        row.put("memory_content", content);
        row.put("importance", importance);
        row.put("access_count", 1);
        row.put("created_at", LocalDateTime.now().minusMinutes(5));
        row.put("updated_at", LocalDateTime.now());
        row.put("deleted_flag", false);
        row.put("shareable", false);
        row.put("scope", "user");
        row.put("scope_key", userId);
        row.put("demotion_count", 0);
        dynamicDataMapper.insert("ab_agent_memory", row);
        return pid;
    }
}
