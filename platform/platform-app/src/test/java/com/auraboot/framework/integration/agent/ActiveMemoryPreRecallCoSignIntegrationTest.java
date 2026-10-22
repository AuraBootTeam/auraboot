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
 * PR-72 C3 — {@code ActiveMemoryService.preRecall} must write one
 * {@code ab_agent_memory_access_log} row per (memory, user, day) so that
 * {@code MemoryPromotionExtractor} Strategy B ({@code implicit_co_sign}) sees
 * interactive chat access (not just cron agent runs).
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Active Memory pre-recall co-sign access log (PR-72 C3)")
class ActiveMemoryPreRecallCoSignIntegrationTest extends BaseIntegrationTest {

    @Autowired private ActiveMemoryService activeMemory;
    @Autowired private AgentMemoryService memory;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String userId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        long base = System.nanoTime() % 1_000_000;
        tenantId = 9_700_000L + base;
        userId = "u_" + base;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_access_log WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ? OR scope_key = ?", tenantId, userId);
    }

    @Test
    @DisplayName("preRecall writes access-log row keyed on (memory_pid, user_id)")
    void preRecall_writes_access_log() {
        String pid = memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Deploy window", "Deploy to prod only on Tuesday mornings",
                8, true, "tenant", null);

        List<Map<String, Object>> snippets = activeMemory.preRecall(tenantId, userId, "deploy");
        assertThat(snippets).isNotEmpty();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log "
                        + "WHERE memory_pid = ? AND user_id = ? AND access_day = CURRENT_DATE",
                Integer.class, pid, userId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("preRecall with null userId is a no-op on access log")
    void preRecall_null_user_no_log() {
        String pid = memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Change freeze", "No deploys during month-end close",
                7, true, "tenant", null);

        activeMemory.preRecall(tenantId, null, "change");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, pid);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("two preRecall calls same day increment access_count on same row")
    void preRecall_idempotent_per_day() {
        String pid = memory.createScopedMemory(
                tenantId, agent, "fact", "operations",
                "Standup", "Daily standup at 10am local",
                9, true, "tenant", null);

        activeMemory.preRecall(tenantId, userId, "standup");
        activeMemory.preRecall(tenantId, userId, "standup");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log "
                        + "WHERE memory_pid = ? AND user_id = ?",
                Integer.class, pid, userId);
        assertThat(count).isEqualTo(1);

        Integer accessCount = jdbc.queryForObject(
                "SELECT access_count FROM ab_agent_memory_access_log "
                        + "WHERE memory_pid = ? AND user_id = ? AND access_day = CURRENT_DATE",
                Integer.class, pid, userId);
        assertThat(accessCount).isGreaterThanOrEqualTo(2);
    }
}
