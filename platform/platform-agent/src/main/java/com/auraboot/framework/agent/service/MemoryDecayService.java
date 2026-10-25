package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Memory L3 decay (memory-lifecycle.md §7).
 *
 * Nightly cron that decrements {@code importance} on stale memories
 * (no access in ≥ {@code staleDays}) and soft-deletes rows whose
 * importance drops to or below zero.
 *
 * Runs across all tenants at once — tenant isolation is maintained by the
 * WHERE clauses but no per-tenant partitioning yet (fine for current data
 * volume; revisit if a single tenant produces millions of memory rows).
 *
 * Session-category rows are skipped here — they have their own decay
 * schedule driven by AgentMemoryConsolidationService (faster, per-run).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryDecayService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${acp.memory.decay.cron:0 30 2 * * *}")
    private String cron;

    /** A memory is "stale" if last_accessed is older than this many days. */
    @Value("${acp.memory.decay.stale-days:30}")
    private int staleDays = 30;

    /** Amount to subtract from importance on each run. */
    @Value("${acp.memory.decay.decrement:1}")
    private int decrement = 1;

    /** Don't decay memories marked shareable — they're pinned by the org. */
    @Value("${acp.memory.decay.skip-shareable:true}")
    private boolean skipShareable = true;

    /** Result of a single decay tick. */
    public record DecayResult(int decayed, int purged) {}

    /**
     * Nightly decay — scheduled at 02:30 UTC by default.
     * Also callable programmatically for tests / manual triggering.
     */
    @Scheduled(cron = "${acp.memory.decay.cron:0 30 2 * * *}")
    public DecayResult decayStaleMemories() {
        int decayed = jdbcTemplate.update(
                "UPDATE ab_agent_memory " +
                        "SET importance = GREATEST(importance - ?, 0), updated_at = NOW() " +
                        "WHERE (deleted_flag IS NULL OR deleted_flag = FALSE) " +
                        "  AND category <> 'session' " +
                        "  AND importance > 0 " +
                        "  AND (last_accessed IS NULL OR last_accessed < NOW() - (? || ' days')::interval) " +
                        (skipShareable ? "  AND (shareable IS NULL OR shareable = FALSE) " : ""),
                decrement, String.valueOf(staleDays));

        int purged = jdbcTemplate.update(
                "UPDATE ab_agent_memory " +
                        "SET deleted_flag = TRUE, updated_at = NOW() " +
                        "WHERE (deleted_flag IS NULL OR deleted_flag = FALSE) " +
                        "  AND category <> 'session' " +
                        "  AND importance <= 0 " +
                        (skipShareable ? "  AND (shareable IS NULL OR shareable = FALSE) " : ""));

        if (decayed > 0 || purged > 0) {
            log.info("Memory L3 decay: {} rows decayed (-{}), {} purged at importance=0",
                    decayed, decrement, purged);
        }
        return new DecayResult(decayed, purged);
    }

    // Test-only setters (package-private) so integration tests can override
    // config without going through Spring property injection.
    public void setStaleDays(int v) { this.staleDays = v; }
    public void setDecrement(int v) { this.decrement = v; }
    public void setSkipShareable(boolean v) { this.skipShareable = v; }
}
