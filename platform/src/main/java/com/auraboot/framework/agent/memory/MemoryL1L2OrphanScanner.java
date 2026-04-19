package com.auraboot.framework.agent.memory;

import com.auraboot.framework.agent.metrics.MemoryL1L2PromotionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PR-84 / Phase 3 — cron-based catch-up for L1 rows that missed the
 * event-driven promotion path.
 *
 * <p>The event-driven {@link MemoryL1L2Promoter} depends on
 * {@code AgentRunService} publishing {@code SessionEndedEvent} after the run
 * completes. If the JVM crashes between {@code saveRunMemory} and the listener
 * executing, or if the listener throws, the L1 rows stay {@code category='session'}
 * with {@code promoted_at = NULL} and never get considered for L2 again until
 * decay soft-deletes them.
 *
 * <p>This scanner closes that gap with a 15-minute cron:
 * <ol>
 *   <li>Acquire {@link #LOCK_KEY advisory lock 7311} (single-flight).</li>
 *   <li>Scan {@code ab_agent_memory} for candidates older than 1 hour with
 *       {@code category='session'} / {@code importance >= BASE_IMPORTANCE_GATE} /
 *       {@code promoted_at IS NULL}.</li>
 *   <li>Delegate each row to {@link MemoryL1L2Promoter#promoteCandidate} —
 *       the same scoring + hash-dedup + cosine-dedup pipeline used by the
 *       event listener, so rescue and normal paths can never diverge.</li>
 *   <li>Cap {@value #BATCH_CAP} rows per run to bound advisory-lock hold
 *       time; next tick picks up the remainder.</li>
 * </ol>
 *
 * <p>Advisory lock key {@code 7311} — design §6 originally reserved
 * {@code 7309} but the brief pinned {@code 7311} / {@code 7312} (the 7309-7310
 * slots were reused by an earlier patch; see brief).
 *
 * <p>Red-line compliance:
 * <ul>
 *   <li>No fallback / ensure / retry. Promotion failure surfaces via the
 *       underlying {@link MemoryL1L2Promoter} which rolls back and increments
 *       {@code OUTCOME_FAILED}.</li>
 *   <li>Uses {@link TransactionTemplate} so the advisory lock holds for the
 *       whole scan without needing {@code REQUIRES_NEW}.</li>
 *   <li>Disabled by default ({@code acp.memory.l1l2.orphan-scan.enabled=false})
 *       — matches every other Phase 3 scheduler in this subsystem.</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryL1L2OrphanScanner {

    /** Advisory lock key — brief-assigned {@code 7311}. */
    public static final long LOCK_KEY = 7311L;

    /** Per-tick row cap so the lock never hogs one connection for minutes. */
    public static final int BATCH_CAP = 500;

    /**
     * Candidate must be at least this many hours old — design §4.1 uses 1h
     * here to keep the normal-path event listener as the primary; only
     * rescue truly stuck rows.
     *
     * <p>Round-2 review #6: int constant (no longer a text-interval literal).
     * The SELECT uses {@code make_interval(hours => ?)} — preemptively kills
     * any injection vector should this constant become configurable later.
     */
    public static final int AGE_HOURS = 1;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MemoryL1L2Promoter promoter;
    private final MemoryL1L2PromotionMetrics metrics;
    private final MemoryL1L2LeaderElection leaderElection;

    @Value("${acp.memory.l1l2.orphan-scan.enabled:false}")
    private boolean enabled;

    public MemoryL1L2OrphanScanner(JdbcTemplate jdbc,
                                   PlatformTransactionManager txManager,
                                   MemoryL1L2Promoter promoter,
                                   MemoryL1L2PromotionMetrics metrics,
                                   MemoryL1L2LeaderElection leaderElection) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.promoter = promoter;
        this.metrics = metrics;
        this.leaderElection = leaderElection;
    }

    /** Every 15 minutes; override via {@code acp.memory.l1l2.orphan-scan.cron}. */
    @Scheduled(cron = "${acp.memory.l1l2.orphan-scan.cron:0 */15 * * * *}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        ScanSummary summary = runOnce();
        if (summary.candidates() > 0) {
            log.info("MemoryL1L2OrphanScanner tick: {}", summary);
        }
    }

    /**
     * One-shot entry point for tests / ops. Returns per-tick counts (never
     * cumulative). Runs in a single transaction, acquires the advisory lock,
     * and releases on commit.
     */
    public ScanSummary runOnce() {
        // Phase 4 (PR-85): coarse multi-instance gate before the advisory lock.
        // When leader-election is disabled (default) acquire() returns true
        // and behaviour is unchanged; when enabled, non-leader instances skip
        // the tick entirely so we don't duplicate scans across replicas.
        if (!leaderElection.acquire(MemoryL1L2LeaderElection.JOB_ORPHAN)) {
            log.debug("MemoryL1L2OrphanScanner: not leader for {}, skipping tick",
                    MemoryL1L2LeaderElection.JOB_ORPHAN);
            metrics.recordLeaderSkipped(MemoryL1L2LeaderElection.JOB_ORPHAN,
                    leaderElection.getInstanceId());
            return new ScanSummary(0, 0, 0, 0, 0);
        }
        Integer[] counts = tx.execute(status -> {
            Boolean acquired = jdbc.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("MemoryL1L2OrphanScanner: advisory lock {} busy", LOCK_KEY);
                return new Integer[]{0, 0, 0, 0, 0};
            }
            try {
                return scanLocked();
            } finally {
                Boolean released = jdbc.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("MemoryL1L2OrphanScanner: pg_advisory_unlock({}) returned {}",
                            LOCK_KEY, released);
                }
            }
        });
        if (counts == null) {
            return new ScanSummary(0, 0, 0, 0, 0);
        }
        return new ScanSummary(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private Integer[] scanLocked() {
        // Select aged orphan L1 rows. The WHERE clauses mirror the event-path
        // candidate SELECT, with three differences: (a) no source_run_id
        // filter (cron is per-tenant, not per-run), (b) hard 1h age floor,
        // (c) promoted_at IS NULL gate so we never reconsider rows the event
        // listener already finished.
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT pid, tenant_id, memory_agent_id, memory_type, memory_title, "
                        + "       memory_content, importance, access_count, created_at, "
                        + "       scope, scope_key, embedding, source_run_id "
                        + "  FROM ab_agent_memory "
                        + " WHERE category = 'session' "
                        + "   AND importance >= ? "
                        + "   AND promoted_at IS NULL "
                        + "   AND created_at < NOW() - make_interval(hours => ?) "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + " ORDER BY created_at ASC "
                        + " LIMIT ?",
                MemoryL1L2Promoter.BASE_IMPORTANCE_GATE, AGE_HOURS, BATCH_CAP);

        int promoted = 0;
        int skippedDup = 0;
        int skippedDupSemantic = 0;
        int skippedLowScore = 0;

        Instant now = Instant.now();
        for (Map<String, Object> row : candidates) {
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String sourceRunId = (String) row.get("source_run_id");
            try {
                MemoryL1L2Promoter.Outcome o = promoter.promoteCandidate(
                        tenantId, sourceRunId, row, now);
                switch (o) {
                    case PROMOTED -> promoted++;
                    case DEDUP_HIT -> skippedDup++;
                    case DEDUP_HIT_SEMANTIC -> skippedDupSemantic++;
                    case LOW_SCORE -> skippedLowScore++;
                }
            } catch (RuntimeException e) {
                metrics.recordPromotionOutcome(tenantId,
                        MemoryL1L2PromotionMetrics.OUTCOME_FAILED);
                // Let the transaction roll back — same contract as the event
                // listener. A single-tenant failure kills the tick; next
                // tick retries (and presumably fails fast on the same row,
                // surfacing via the failed counter).
                throw e;
            }
        }

        return new Integer[]{candidates.size(), promoted, skippedDup,
                skippedDupSemantic, skippedLowScore};
    }

    /**
     * Per-tick scan result. All counts are per-tick and never cumulative.
     */
    public record ScanSummary(int candidates, int promoted, int dedupHits,
                              int semanticDedupHits, int lowScore) {
    }
}
