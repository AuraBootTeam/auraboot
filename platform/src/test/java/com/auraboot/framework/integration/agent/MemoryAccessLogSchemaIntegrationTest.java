package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.MemoryPromotionExpirer;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.auraboot.framework.integration.TestIdGenerator;

/**
 * PR-73 — schema invariants for {@code ab_agent_memory_access_log}:
 * <ul>
 *   <li>{@code tenant_id NOT NULL} enforced.</li>
 *   <li>FK {@code memory_pid → ab_agent_memory(pid) ON DELETE CASCADE}:
 *       deleting a memory row cascades to its access-log rows.</li>
 *   <li>{@link AgentMemoryService#recordMemoryAccess} is a safe no-op
 *       when the {@code memory_pid} does not exist.</li>
 *   <li>{@link MemoryPromotionExpirer#runOnce} purges rows whose
 *       {@code last_seen_at} is older than 90 days.</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryAccessLog schema + retention (PR-73)")
class MemoryAccessLogSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentMemoryService memoryService;
    @Autowired private MemoryPromotionExpirer expirer;

    private Long tenantId;
    private String agentCode;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        agentCode = "acclog-test-" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        // FK cascade will remove access_log rows, but also clean any orphans
        // by tenant to be safe.
        jdbc.update("DELETE FROM ab_agent_memory_access_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seedMemory() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'user', 't', 'c', 6, FALSE, "
                        + "NOW(), NOW(), FALSE)",
                pid, tenantId, agentCode);
        return pid;
    }

    @Test
    @DisplayName("tenant_id is NOT NULL — direct insert without it fails")
    void tenantIdNotNull_enforced() {
        String memoryPid = seedMemory();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ab_agent_memory_access_log "
                        + "  (memory_pid, user_id, access_day) "
                        + "VALUES (?, ?, CURRENT_DATE)",
                memoryPid, "u1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("FK cascade — deleting memory row removes its access_log rows")
    void fkCascade_removesAccessLog() {
        String memoryPid = seedMemory();
        memoryService.recordMemoryAccess(memoryPid, "u1");
        memoryService.recordMemoryAccess(memoryPid, "u2");

        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, memoryPid);
        assertThat(before).isEqualTo(2);

        jdbc.update("DELETE FROM ab_agent_memory WHERE pid = ?", memoryPid);

        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, memoryPid);
        assertThat(after).isZero();
    }

    @Test
    @DisplayName("recordMemoryAccess — unknown memory_pid is a no-op")
    void recordMemoryAccess_unknownMemory_isNoOp() {
        String bogusPid = UniqueIdGenerator.generate();
        memoryService.recordMemoryAccess(bogusPid, "u1");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, bogusPid);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("recordMemoryAccess — sets tenant_id from memory row")
    void recordMemoryAccess_setsTenantId() {
        String memoryPid = seedMemory();
        memoryService.recordMemoryAccess(memoryPid, "u1");
        Long loggedTenant = jdbc.queryForObject(
                "SELECT tenant_id FROM ab_agent_memory_access_log WHERE memory_pid = ? AND user_id = ?",
                Long.class, memoryPid, "u1");
        assertThat(loggedTenant).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("retention — rows with last_seen_at older than 90 days are purged by runOnce()")
    void retention_purgesStaleRows() {
        String memoryPid = seedMemory();

        // Fresh row: should survive.
        memoryService.recordMemoryAccess(memoryPid, "fresh");

        // Stale row: last_seen_at 91 days ago. Direct insert because the
        // service always writes NOW().
        jdbc.update("INSERT INTO ab_agent_memory_access_log "
                        + "  (memory_pid, tenant_id, user_id, access_day, access_count, "
                        + "   first_seen_at, last_seen_at) "
                        + "VALUES (?, ?, ?, (NOW() - INTERVAL '91 days')::date, 1, "
                        + "        NOW() - INTERVAL '91 days', NOW() - INTERVAL '91 days')",
                memoryPid, tenantId, "stale");

        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, memoryPid);
        assertThat(before).isEqualTo(2);

        expirer.runOnce();

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                Integer.class, memoryPid);
        assertThat(remaining).isEqualTo(1);

        String survivingUser = jdbc.queryForObject(
                "SELECT user_id FROM ab_agent_memory_access_log WHERE memory_pid = ?",
                String.class, memoryPid);
        assertThat(survivingUser).isEqualTo("fresh");
    }
}
