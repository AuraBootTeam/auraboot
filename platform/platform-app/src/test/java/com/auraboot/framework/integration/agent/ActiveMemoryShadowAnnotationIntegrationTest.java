package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ActiveMemoryService;
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

/**
 * PR-68 Phase 4 — grounding-side shadow annotation.
 *
 * <p>Memory Promotion design §8: when {@code AgentMemoryService.searchScoped} /
 * {@code loadScopedByImportance} return a memory row with {@code shadow_mode=TRUE}
 * (i.e. the source promotion is still in its 7-day {@code PROMOTED_SHADOW}
 * observation window), the snippet passed to grounding/LLM is prefixed with
 * {@link ActiveMemoryService#SHADOW_ANNOTATION_PREFIX} so AuraBot can preface
 * its reply with uncertainty language ("根据团队近期记忆（尚在观察期）：...").
 *
 * <p>ACTIVE memories (post 7-day window) and session/user-scope memories are
 * rendered verbatim — no prefix.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Active Memory shadow annotation (PR-68)")
class ActiveMemoryShadowAnnotationIntegrationTest extends BaseIntegrationTest {

    @Autowired private ActiveMemoryService activeMemory;
    @Autowired private AgentMemoryService memory;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String userId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_400_000L + base;
        userId = "u_" + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ? OR scope_key = ?", tenantId, userId);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shadow_mode=TRUE tenant memory snippet is prefixed with annotation")
    void shadow_tenant_memory_is_annotated() {
        String pid = memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Month-end close", "Close books on the 28th of each month",
                8, true, "tenant", null);
        // Flip shadow_mode TRUE to simulate a freshly-approved promotion in its
        // observation window.
        jdbc.update("UPDATE ab_agent_memory SET shadow_mode = TRUE WHERE pid = ?", pid);

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "month-end");
        assertThat(snippets).hasSize(1);
        Map<String, Object> s = snippets.get(0);

        assertThat(s.get("content").toString())
                .startsWith(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX)
                .contains("Close books on the 28th");
        assertThat(s.get("shadow_mode")).isEqualTo(true);
        assertThat(s.get("scope")).isEqualTo("tenant");
    }

    @Test
    @DisplayName("shadow_mode=FALSE tenant memory is rendered verbatim (no prefix)")
    void active_tenant_memory_not_annotated() {
        memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Quarterly review", "Quarterly review happens in week 13",
                7, true, "tenant", null);
        // shadow_mode defaults to FALSE via schema — no flip.

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "quarterly");
        assertThat(snippets).hasSize(1);
        Map<String, Object> s = snippets.get(0);

        assertThat(s.get("content").toString())
                .doesNotStartWith(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX)
                .isEqualTo("Quarterly review happens in week 13");
        assertThat(s.get("shadow_mode")).isEqualTo(false);
    }

    @Test
    @DisplayName("shadow_mode surfaces via loadScopedByImportance path (no keyword)")
    void shadow_mode_via_importance_path() {
        String pid = memory.createScopedMemory(
                tenantId, agent, "preference", "operations",
                "Standup time", "Daily standup at 10am local",
                9, true, "tenant", null);
        jdbc.update("UPDATE ab_agent_memory SET shadow_mode = TRUE WHERE pid = ?", pid);

        // Empty user message → importance pre-recall path only.
        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "");
        assertThat(snippets)
                .anyMatch(s -> s.get("content").toString().startsWith(ActiveMemoryService.SHADOW_ANNOTATION_PREFIX)
                        && s.get("shadow_mode").equals(true));
    }
}
