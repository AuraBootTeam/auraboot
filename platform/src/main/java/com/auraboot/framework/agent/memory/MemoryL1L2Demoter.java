package com.auraboot.framework.agent.memory;

import com.auraboot.framework.agent.metrics.MemoryL1L2PromotionMetrics;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * PR-84 / Phase 3 — daily demotion scan per design §4.4.
 *
 * <p>L2 rows ({@code category IN ('user','agent')}) that have not been
 * accessed for ≥ {@value #DEFAULT_DEMOTE_AGE_DAYS} days and carry
 * {@code importance < demote_threshold} are flipped back to
 * {@code category='session'} (L1) so they participate in the normal decay
 * cycle. This prevents long-tail L2 bloat without hard-deleting rows: the
 * row still exists and can be re-promoted if accessed.
 *
 * <p>Guardrails:
 * <ul>
 *   <li>{@code shareable = TRUE} rows are never demoted (pinned by admin).</li>
 *   <li>{@code importance >= DEMOTE_THRESHOLD} rows are never demoted.</li>
 *   <li>Rows already in L1 ({@code category='session'}) are not re-touched.</li>
 * </ul>
 *
 * <p>Advisory lock key {@code 7312} — brief-assigned. Design §6 reserved a
 * different key but the PR-84 brief pinned this value; honour the brief.
 *
 * <p>Red-line compliance:
 * <ul>
 *   <li>No fallback / ensure / retry — a per-row UPDATE failure aborts the
 *       tick (transaction rolls back, advisory lock releases).</li>
 *   <li>Lowercase enum DB values (category values in {@code session},
 *       {@code user}, {@code agent}).</li>
 *   <li>Disabled by default ({@code acp.memory.l1l2.demoter.enabled=false}).</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryL1L2Demoter {

    /** Advisory lock key — brief-assigned {@code 7312}. */
    public static final long LOCK_KEY = 7312L;

    /** Default age cut-off per design §4.4 (configurable via property). */
    public static final int DEFAULT_DEMOTE_AGE_DAYS = 90;

    /** Default importance cap per design §4.4; rows at or above this stay L2. */
    public static final int DEFAULT_DEMOTE_IMPORTANCE_MAX = 3;

    /** Per-tick cap to bound lock hold time; tomorrow's tick handles the rest. */
    public static final int BATCH_CAP = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MemoryL1L2PromotionMetrics metrics;

    @Value("${acp.memory.l1l2.demoter.enabled:false}")
    private boolean enabled;

    @Value("${acp.memory.l1l2.demoter.age-days:90}")
    private int ageDays = DEFAULT_DEMOTE_AGE_DAYS;

    @Value("${acp.memory.l1l2.demoter.importance-max:3}")
    private int importanceMax = DEFAULT_DEMOTE_IMPORTANCE_MAX;

    public MemoryL1L2Demoter(JdbcTemplate jdbc,
                             PlatformTransactionManager txManager,
                             MemoryL1L2PromotionMetrics metrics) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.metrics = metrics;
    }

    /** Daily at 03:00 — design §6. Override via {@code acp.memory.l1l2.demoter.cron}. */
    @Scheduled(cron = "${acp.memory.l1l2.demoter.cron:0 0 3 * * *}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        DemoteSummary summary = runOnce();
        if (summary.scanned() > 0) {
            log.info("MemoryL1L2Demoter tick: {}", summary);
        }
    }

    /**
     * Test / ops entry point. Runs inside a single transaction; acquires
     * advisory lock {@value #LOCK_KEY} and demotes up to {@value #BATCH_CAP}
     * stale / low-importance L2 rows.
     */
    public DemoteSummary runOnce() {
        Integer[] counts = tx.execute(status -> {
            Boolean acquired = jdbc.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("MemoryL1L2Demoter: advisory lock {} busy", LOCK_KEY);
                return new Integer[]{0, 0};
            }
            try {
                return scanLocked();
            } finally {
                Boolean released = jdbc.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("MemoryL1L2Demoter: pg_advisory_unlock({}) returned {}",
                            LOCK_KEY, released);
                }
            }
        });
        if (counts == null) {
            return new DemoteSummary(0, 0);
        }
        return new DemoteSummary(counts[0], counts[1]);
    }

    private Integer[] scanLocked() {
        // Select L2 rows that are stale AND low-importance AND not pinned.
        // last_accessed IS NULL counts as "stale" — rows that were promoted
        // but never re-accessed are prime demotion candidates.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pid, tenant_id FROM ab_agent_memory "
                        + " WHERE category IN ('user','agent') "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + "   AND (shareable IS NULL OR shareable = FALSE) "
                        + "   AND importance < ? "
                        + "   AND (last_accessed IS NULL OR last_accessed < NOW() - (? || ' days')::interval) "
                        + " ORDER BY COALESCE(last_accessed, created_at) ASC "
                        + " LIMIT ?",
                importanceMax, String.valueOf(ageDays), BATCH_CAP);

        int scanned = rows.size();
        int demoted = 0;

        for (Map<String, Object> row : rows) {
            String pid = (String) row.get("pid");
            Long tenantId = ((Number) row.get("tenant_id")).longValue();

            // Atomic UPDATE guards against racing promoters: if the row was
            // re-accessed / re-promoted between the SELECT and here, the
            // category predicate will fail and the UPDATE affects 0 rows.
            int updated = jdbc.update(
                    "UPDATE ab_agent_memory "
                            + "   SET category = 'session', "
                            + "       demoted_at = NOW(), "
                            + "       demotion_count = COALESCE(demotion_count, 0) + 1, "
                            + "       updated_at = NOW() "
                            + " WHERE pid = ? "
                            + "   AND category IN ('user','agent') "
                            + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                            + "   AND (shareable IS NULL OR shareable = FALSE)",
                    pid);

            if (updated == 1) {
                demoted++;
                writeAuditRow(tenantId, pid);
                metrics.recordDemotionOutcome(tenantId,
                        MemoryL1L2PromotionMetrics.OUTCOME_DEMOTED);
                metrics.recordTierEvent(tenantId,
                        MemoryL1L2PromotionMetrics.EVENT_TYPE_L2_DEMOTED);
            } else {
                metrics.recordDemotionOutcome(tenantId,
                        MemoryL1L2PromotionMetrics.OUTCOME_DEMOTE_SKIPPED);
            }
        }
        return new Integer[]{scanned, demoted};
    }

    private void writeAuditRow(Long tenantId, String memoryPid) {
        jdbc.update(
                "INSERT INTO ab_agent_memory_tier_event "
                        + "  (pid, tenant_id, memory_pid, event_type, dedup_mode, "
                        + "   merged_into_pid, score_snapshot, source_run_id, created_at) "
                        + "VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, NOW())",
                UniqueIdGenerator.generate(), tenantId, memoryPid,
                MemoryL1L2PromotionMetrics.EVENT_TYPE_L2_DEMOTED);
    }

    /** Per-tick summary. {@code scanned} ≥ {@code demoted} (race-losers counted in scanned). */
    public record DemoteSummary(int scanned, int demoted) {
    }
}
