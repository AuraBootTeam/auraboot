package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryDecayService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-22: Memory L3 decay — nightly importance decrement for stale
 * memories, soft-delete at importance≤0.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryDecayService L3 (PR-22)")
class MemoryDecayIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryDecayService decay;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private final String agent = "aurabot";

    @BeforeEach
    void setup() {
        tenantId = 9_600_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    /**
     * Insert a memory row whose last_accessed is at a specific offset from NOW.
     * @param lastAccessedDaysAgo days ago (null = never accessed, i.e. NULL)
     */
    private String seedMemory(String category, int importance, boolean shareable,
                               Integer lastAccessedDaysAgo) {
        String pid = UniqueIdGenerator.generate();
        String lastAccessClause = lastAccessedDaysAgo == null ? "NULL"
                : "NOW() - INTERVAL '" + lastAccessedDaysAgo + " days'";
        jdbc.update("INSERT INTO ab_agent_memory " +
                        "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                        " memory_title, memory_content, importance, shareable, " +
                        " scope, last_accessed, created_at, updated_at, deleted_flag) " +
                        "VALUES (?, ?, ?, 'fact', ?, 'decay-test', 'body', ?, ?, 'tenant', " +
                        lastAccessClause + ", NOW(), NOW(), FALSE)",
                pid, tenantId, agent, category, importance, shareable);
        return pid;
    }

    private int importanceOf(String pid) {
        Integer v = jdbc.queryForObject(
                "SELECT importance FROM ab_agent_memory WHERE pid = ?", Integer.class, pid);
        return v == null ? -1 : v;
    }

    private boolean deletedFlag(String pid) {
        Boolean v = jdbc.queryForObject(
                "SELECT deleted_flag FROM ab_agent_memory WHERE pid = ?", Boolean.class, pid);
        return Boolean.TRUE.equals(v);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("stale non-session memory loses 1 importance per run")
    void stale_memory_decays() {
        decay.setStaleDays(30);
        decay.setDecrement(1);
        decay.setSkipShareable(true);

        String stale = seedMemory("user", 5, false, 45);  // 45d since last access
        String fresh = seedMemory("user", 5, false, 5);   // accessed recently

        MemoryDecayService.DecayResult result = decay.decayStaleMemories();
        assertThat(result.decayed()).isGreaterThanOrEqualTo(1);

        assertThat(importanceOf(stale)).isEqualTo(4);
        assertThat(importanceOf(fresh)).isEqualTo(5);
    }

    @Test
    @DisplayName("memory with NULL last_accessed is treated as stale")
    void null_last_access_decays() {
        decay.setStaleDays(30);
        decay.setDecrement(1);
        decay.setSkipShareable(true);

        String neverRead = seedMemory("user", 5, false, null);
        decay.decayStaleMemories();
        assertThat(importanceOf(neverRead)).isEqualTo(4);
    }

    @Test
    @DisplayName("session-category memory is skipped (own decay schedule)")
    void session_memory_not_touched() {
        decay.setStaleDays(30);
        String session = seedMemory("session", 5, false, 45);
        decay.decayStaleMemories();
        assertThat(importanceOf(session)).isEqualTo(5);
    }

    @Test
    @DisplayName("shareable memory is skipped when skipShareable=true")
    void shareable_skipped_when_flag_on() {
        decay.setStaleDays(30);
        decay.setSkipShareable(true);
        String shared = seedMemory("agent", 5, true, 45);
        decay.decayStaleMemories();
        assertThat(importanceOf(shared)).isEqualTo(5);
    }

    @Test
    @DisplayName("shareable memory decays when skipShareable=false")
    void shareable_decays_when_flag_off() {
        decay.setStaleDays(30);
        decay.setDecrement(1);
        decay.setSkipShareable(false);
        String shared = seedMemory("agent", 5, true, 45);
        decay.decayStaleMemories();
        assertThat(importanceOf(shared)).isEqualTo(4);
    }

    @Test
    @DisplayName("memory decremented to 0 is purged in the same run")
    void zero_importance_purged() {
        decay.setStaleDays(30);
        decay.setDecrement(1);

        String dying = seedMemory("user", 1, false, 45);
        // One run: decay phase 1→0, then purge phase soft-deletes it.
        MemoryDecayService.DecayResult r = decay.decayStaleMemories();
        assertThat(r.decayed()).isGreaterThanOrEqualTo(1);
        assertThat(r.purged()).isGreaterThanOrEqualTo(1);
        assertThat(importanceOf(dying)).isEqualTo(0);
        assertThat(deletedFlag(dying)).isTrue();
    }

    @Test
    @DisplayName("already-at-zero memory is purged without needing another decay pass")
    void pre_zero_importance_still_purges() {
        decay.setStaleDays(30);
        decay.setDecrement(1);

        // Insert at importance=0 directly (could happen from manual tuning /
        // seed data). last_accessed can be recent — the purge phase only
        // cares about importance <= 0, not staleness.
        String atZero = seedMemory("user", 0, false, 5);
        decay.decayStaleMemories();
        assertThat(deletedFlag(atZero)).isTrue();
    }

    @Test
    @DisplayName("importance floor at 0 (GREATEST clamp) — never goes negative")
    void importance_floor_zero() {
        decay.setStaleDays(30);
        decay.setDecrement(10);  // larger than the stored importance
        String small = seedMemory("user", 3, false, 45);

        decay.decayStaleMemories();
        assertThat(importanceOf(small)).isZero();
    }

    @Test
    @DisplayName("already-deleted rows are ignored by the decay query")
    void already_deleted_untouched() {
        decay.setStaleDays(30);
        String gone = seedMemory("user", 7, false, 45);
        jdbc.update("UPDATE ab_agent_memory SET deleted_flag = TRUE WHERE pid = ?", gone);

        decay.decayStaleMemories();
        // Still 7 — the update skipped deleted rows.
        Integer importance = jdbc.queryForObject(
                "SELECT importance FROM ab_agent_memory WHERE pid = ?", Integer.class, gone);
        assertThat(importance).isEqualTo(7);
    }

    @Test
    @DisplayName("DecayResult returns both decayed and purged counts")
    void decay_result_shape() {
        decay.setStaleDays(30);
        decay.setDecrement(1);

        seedMemory("user", 3, false, 45);
        seedMemory("user", 1, false, 45);   // will drop to 0 + purged
        seedMemory("user", 5, false, 5);    // fresh — untouched

        MemoryDecayService.DecayResult r = decay.decayStaleMemories();
        assertThat(r.decayed()).isGreaterThanOrEqualTo(2);
        assertThat(r.purged()).isGreaterThanOrEqualTo(1);
    }
}
