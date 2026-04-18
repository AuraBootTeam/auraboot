package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.GroundingService;
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
 * PR-14: Active Memory pre-recall wires memories into {@code BIF.preContext}.
 * This pins:
 *   - keyword match returns relevant snippet shape
 *   - importance-ordered fill covers the rest up to MAX_SNIPPETS
 *   - empty or missing memory → preContext is empty, not null
 *   - GroundingService.ground() populates preContext when ActiveMemoryService is injected
 *   - scope contract from PR-13 is respected end-to-end
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Active Memory pre-recall (PR-14)")
class ActiveMemoryPreRecallIntegrationTest extends BaseIntegrationTest {

    @Autowired private ActiveMemoryService activeMemory;
    @Autowired private AgentMemoryService memory;
    @Autowired private GroundingService groundingService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String userId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_300_000L + base;
        userId = "u_" + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ? OR scope_key = ?", tenantId, userId);
        jdbc.update("DELETE FROM ab_agent_bif WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("preRecall returns empty (not null) when no memories match")
    void preRecall_empty() {
        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "anything");
        assertThat(snippets).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("preRecall returns keyword match with compact snippet shape")
    void preRecall_keyword_match() {
        memory.createScopedMemory(tenantId, agent, "preference", "user",
                "User prefers dark mode", "The user has selected dark mode theme preference",
                8, false, "user", userId);

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "dark mode");
        assertThat(snippets).hasSize(1);
        Map<String, Object> s = snippets.get(0);
        assertThat(s.keySet()).contains("pid", "type", "title", "content", "importance", "scope");
        assertThat(s.get("type")).isEqualTo("preference");
        assertThat(s.get("title")).isEqualTo("User prefers dark mode");
        assertThat(s.get("scope")).isEqualTo("user");
    }

    @Test
    @DisplayName("preRecall caps snippets at MAX_SNIPPETS (8) even with many matches")
    void preRecall_caps_size() {
        // Seed 12 user-scoped memories that all match "api"
        for (int i = 0; i < 12; i++) {
            memory.createScopedMemory(tenantId, agent, "fact", "user",
                    "api-fact-" + i, "fact about api number " + i,
                    5, false, "user", userId);
        }
        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "api");
        assertThat(snippets.size()).isLessThanOrEqualTo(8);
        assertThat(snippets).isNotEmpty();
    }

    @Test
    @DisplayName("preRecall deduplicates when keyword and importance fill overlap")
    void preRecall_dedupes_overlap() {
        // A single high-importance memory that both the keyword search AND the
        // importance-ordered fill would return.
        memory.createScopedMemory(tenantId, agent, "rule", "user",
                "always use EST timezone", "When scheduling, use EST timezone unless asked otherwise",
                9, false, "user", userId);

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "timezone");
        assertThat(snippets).hasSize(1);
    }

    @Test
    @DisplayName("preRecall respects scope contract — only visible memories surface")
    void preRecall_respects_scope() {
        String otherUser = "other_user_" + System.nanoTime();

        memory.createScopedMemory(tenantId, agent, "fact", "user",
                "other user secret", "cookie is chocolate chip",
                10, false, "user", otherUser);
        memory.createScopedMemory(tenantId, agent, "fact", "agent",
                "tenant fact", "we ship on friday",
                7, false, "tenant", null);
        memory.createScopedMemory(tenantId, agent, "fact", "agent",
                "global fact", "pi is approximately 3.14",
                5, false, "global", null);

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "");
        // userId sees tenant + global, NOT otherUser's secret.
        assertThat(snippets).hasSize(2);
        assertThat(snippets).extracting(s -> s.get("title"))
                .containsExactlyInAnyOrder("tenant fact", "global fact");
    }

    @Test
    @DisplayName("GroundingService.ground() populates BIF.preContext from ActiveMemory")
    void grounding_populates_preContext() {
        memory.createScopedMemory(tenantId, agent, "preference", "user",
                "crm_lead prefers weekly digest", "summarize leads weekly on Monday",
                8, false, "user", userId);
        memory.createScopedMemory(tenantId, agent, "fact", "agent",
                "quarterly planning Q2", "Q2 targets: 50 new leads",
                6, false, "tenant", null);

        GroundingService.GroundingContext ctx = GroundingService.GroundingContext.builder()
                .pageModel("crm_lead")
                .userId(userId)
                .sessionId("sess-test")
                .build();

        BusinessIntentFrame bif = groundingService.ground(tenantId, "show me leads this week", ctx);
        assertThat(bif.getPreContext()).isNotNull();
        assertThat(bif.getPreContext()).isNotEmpty();
        // Both rows are visible to this user (user + tenant scope).
        assertThat(bif.getPreContext()).extracting(s -> s.get("title"))
                .contains("crm_lead prefers weekly digest", "quarterly planning Q2");
    }

    @Test
    @DisplayName("grounding tolerates null userId — sees tenant row but NOT dirty scope_key='' rows")
    void grounding_tolerates_null_user() {
        memory.createScopedMemory(tenantId, agent, "fact", "agent", "t1", "tenant t1", 7, false, "tenant", null);

        // Dirty row: an upstream-bug insert with scope='user' and scope_key='' MUST NOT
        // match a null/system caller (regression pin for M1).
        jdbc.update("INSERT INTO ab_agent_memory " +
                "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                " memory_title, memory_content, importance, shareable, " +
                " scope, scope_key, created_at, updated_at, deleted_flag) " +
                "VALUES (?, ?, ?, 'fact', 'agent', 'dirty', 'dirty null-user', 9, FALSE, " +
                " 'user', '', NOW(), NOW(), FALSE)",
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                tenantId, agent);

        GroundingService.GroundingContext ctx = GroundingService.GroundingContext.builder()
                .pageModel("crm_lead")
                .userId(null)
                .build();

        BusinessIntentFrame bif = groundingService.ground(tenantId, "query leads", ctx);
        assertThat(bif).isNotNull();
        // Tenant-scoped row visible.
        assertThat(bif.getPreContext()).extracting(s -> s.get("title")).contains("t1");
        // Dirty empty-scope_key row is NOT visible.
        assertThat(bif.getPreContext()).extracting(s -> s.get("title")).doesNotContain("dirty");
    }

    @Test
    @DisplayName("preRecall with explicit agentCode reads that agent's bucket (not default aurabot)")
    void preRecall_honors_explicit_agent_code() {
        String customAgent = "crm_ops_" + System.nanoTime();
        memory.createScopedMemory(tenantId, customAgent, "fact", "agent",
                "custom-only", "only crm_ops knows this", 8, false, "tenant", null);
        // aurabot bucket is separate — no row.

        List<Map<String, Object>> aurabotSnippets =
                activeMemory.preRecall(tenantId, userId, "aurabot", "custom");
        List<Map<String, Object>> customSnippets =
                activeMemory.preRecall(tenantId, userId, customAgent, "custom");

        assertThat(aurabotSnippets).extracting(s -> s.get("title")).doesNotContain("custom-only");
        assertThat(customSnippets).extracting(s -> s.get("title")).contains("custom-only");

        // cleanup this custom agent's rows (not caught by the shared "aurabot" cleanup)
        jdbc.update("DELETE FROM ab_agent_memory WHERE memory_agent_id = ?", customAgent);
    }
}
