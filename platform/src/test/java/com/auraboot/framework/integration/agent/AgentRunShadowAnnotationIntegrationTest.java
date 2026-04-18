package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.application.tenant.MetaContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-72 C2 — {@code AgentRunService.loadMemorySection} must apply the same
 * shadow-mode annotation that {@link ActiveMemoryService} applies in the
 * interactive chat path. Without this, shadow memories look fully-endorsed to
 * the LLM during cron agent runs.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Agent-run memory section shadow annotation (PR-72 C2)")
class AgentRunShadowAnnotationIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentMemoryService memory;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_800_000L + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String invokeLoadMemorySection(Long tid, String agentCode) throws Exception {
        // Unwrap any Spring AOP proxy so reflection targets the real object with
        // autowired fields (e.g. agentProperties).
        AgentRunService target = AopUtils.isAopProxy(agentRunService)
                ? AopTestUtils.getTargetObject(agentRunService) : agentRunService;
        Method m = AgentRunService.class.getDeclaredMethod(
                "loadMemorySection", Long.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(target, tid, agentCode);
    }

    @Test
    @DisplayName("shadow_mode=TRUE memory is prefixed with SHADOW_ANNOTATION_PREFIX in agent-run section")
    void shadow_memory_annotated_in_agent_run_path() throws Exception {
        String pid = memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Release cadence", "Release every other Thursday morning",
                9, true, "tenant", null);
        jdbc.update("UPDATE ab_agent_memory SET shadow_mode = TRUE WHERE pid = ?", pid);

        String section = invokeLoadMemorySection(tenantId, agent);
        assertThat(section).isNotNull();
        assertThat(section)
                .contains(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX)
                .contains("Release every other Thursday morning");
    }

    @Test
    @DisplayName("shadow_mode=FALSE memory is rendered verbatim (no prefix) in agent-run section")
    void active_memory_not_annotated_in_agent_run_path() throws Exception {
        memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Retro cadence", "Retro every second Friday afternoon",
                9, true, "tenant", null);
        // shadow_mode defaults to FALSE via schema.

        String section = invokeLoadMemorySection(tenantId, agent);
        assertThat(section).isNotNull();
        assertThat(section)
                .contains("Retro every second Friday afternoon")
                .doesNotContain(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX);
    }
}
