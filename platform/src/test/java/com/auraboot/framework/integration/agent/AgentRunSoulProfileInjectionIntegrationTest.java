package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-77 Phase 3 — AgentRunService.loadMemorySection must prepend the User Soul
 * Profile section when an ACTIVE profile exists for the current user.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AgentRunService Soul Profile grounding injection (PR-77)")
class AgentRunSoulProfileInjectionIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentMemoryService memory;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_790_000L + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
    }

    private String invokeLoadMemorySection(Long tid, String agentCode) throws Exception {
        AgentRunService target = AopUtils.isAopProxy(agentRunService)
                ? AopTestUtils.getTargetObject(agentRunService) : agentRunService;
        Method m = AgentRunService.class.getDeclaredMethod(
                "loadMemorySection", Long.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(target, tid, agentCode);
    }

    private void seedActiveProfile(Long tid, String uid) {
        String profile = """
                {
                  "persona": {"text": "Product engineer, pragmatic tone"},
                  "preferences": {"communication_style": {"text": "terse bullet points"}},
                  "language": "zh-CN"
                }
                """;
        jdbc.update("INSERT INTO ab_agent_user_soul_profile " +
                "(pid, tenant_id, user_id, version, status, profile, profile_hash, activated_at) " +
                "VALUES (?, ?, ?, 1, 'ACTIVE', ?::jsonb, ?, ?)",
                UniqueIdGenerator.generate(), tid, uid, profile,
                "h_" + System.nanoTime(), Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("Memory section gains Soul Profile preamble when ACTIVE profile exists")
    void memory_section_prepends_profile() throws Exception {
        memory.createScopedMemory(tenantId, agent, "fact", "operations",
                "Release cadence", "Release every other Thursday morning",
                9, true, "tenant", null);
        seedActiveProfile(tenantId, testUser.getId().toString());

        String section = invokeLoadMemorySection(tenantId, agent);

        assertThat(section).isNotNull();
        assertThat(section).contains("About this user");
        assertThat(section).contains("Product engineer");
        assertThat(section).contains("## Agent Memory");
        // Profile preamble precedes the memory section
        int profileIdx = section.indexOf("About this user");
        int memoryIdx = section.indexOf("## Agent Memory");
        assertThat(profileIdx).isGreaterThanOrEqualTo(0);
        assertThat(memoryIdx).isGreaterThan(profileIdx);
    }

    @Test
    @DisplayName("No profile → memory section unchanged (no preamble)")
    void no_profile_no_preamble() throws Exception {
        memory.createScopedMemory(tenantId, agent, "fact", "operations",
                "Release cadence", "Release every other Thursday morning",
                9, true, "tenant", null);

        String section = invokeLoadMemorySection(tenantId, agent);
        assertThat(section).isNotNull();
        assertThat(section).doesNotContain("About this user");
        assertThat(section).startsWith("## Agent Memory");
    }

    @Test
    @DisplayName("No user in MetaContext (system/cron) → no profile injected")
    void null_user_no_injection() throws Exception {
        memory.createScopedMemory(tenantId, agent, "fact", "operations",
                "Release cadence", "Release every other Thursday morning",
                9, true, "tenant", null);
        // Seed a profile for some user, but clear the context so no user id is present
        seedActiveProfile(tenantId, "someone_else");
        MetaContext.clear();
        MetaContext.setSystemTenantContext(tenantId);
        try {
            String section = invokeLoadMemorySection(tenantId, agent);
            assertThat(section).isNotNull();
            assertThat(section).doesNotContain("About this user");
        } finally {
            // Restore for @AfterEach
            MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(),
                    testUser.getUserName());
        }
    }

    @Test
    @DisplayName("Hidden profile → no preamble")
    void hidden_profile_no_preamble() throws Exception {
        memory.createScopedMemory(tenantId, agent, "fact", "operations",
                "Release cadence", "Release every other Thursday morning",
                9, true, "tenant", null);
        jdbc.update("INSERT INTO ab_agent_user_soul_profile " +
                "(pid, tenant_id, user_id, version, status, profile, profile_hash, hidden_at) " +
                "VALUES (?, ?, ?, 1, 'ACTIVE', ?::jsonb, ?, ?)",
                UniqueIdGenerator.generate(), tenantId, testUser.getId().toString(),
                "{\"persona\":{\"text\":\"hidden persona\"}}",
                "h_" + System.nanoTime(), Timestamp.from(Instant.now()));

        String section = invokeLoadMemorySection(tenantId, agent);
        assertThat(section).isNotNull();
        assertThat(section).doesNotContain("About this user");
    }
}
