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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final MemoryL1L2LeaderElection leaderElection;

    @Value("${acp.memory.l1l2.demoter.enabled:false}")
    private boolean enabled;

    @Value("${acp.memory.l1l2.demoter.age-days:90}")
    private int ageDays = DEFAULT_DEMOTE_AGE_DAYS;

    @Value("${acp.memory.l1l2.demoter.importance-max:3}")
    private int importanceMax = DEFAULT_DEMOTE_IMPORTANCE_MAX;

    public MemoryL1L2Demoter(JdbcTemplate jdbc,
                             PlatformTransactionManager txManager,
                             MemoryL1L2PromotionMetrics metrics,
                             MemoryL1L2LeaderElection leaderElection) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.metrics = metrics;
        this.leaderElection = leaderElection;
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
        // Phase 4 (PR-85): multi-instance gate — see MemoryL1L2OrphanScanner.
        if (!leaderElection.acquire(MemoryL1L2LeaderElection.JOB_DEMOTER)) {
            log.debug("MemoryL1L2Demoter: not leader for {}, skipping tick",
                    MemoryL1L2LeaderElection.JOB_DEMOTER);
            metrics.recordLeaderSkipped(MemoryL1L2LeaderElection.JOB_DEMOTER,
                    leaderElection.getInstanceId());
            return new DemoteSummary(0, 0);
        }
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
        // Round-2 review fix #1 + #7:
        //  (#1) Use make_interval(days => ?) with setInt rather than
        //       (? || ' days')::interval — avoids text→interval coercion under
        //       prepared-statement caching and plays nicer with pgBouncer.
        //  (#7) A newly-promoted row has last_accessed=NULL and promoted_at=NOW();
        //       without a grace period it is immediately eligible for demotion.
        //       Fall back through COALESCE(last_accessed, promoted_at, created_at)
        //       so the freshest "touch" timestamp gates demotion.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pid, tenant_id FROM ab_agent_memory "
                        + " WHERE category IN ('user','agent') "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + "   AND (shareable IS NULL OR shareable = FALSE) "
                        + "   AND importance < ? "
                        + "   AND COALESCE(last_accessed, promoted_at, created_at) "
                        + "       < NOW() - make_interval(days => ?) "
                        + " ORDER BY COALESCE(last_accessed, promoted_at, created_at) ASC "
                        + " LIMIT ?",
                importanceMax, ageDays, BATCH_CAP);

        int scanned = rows.size();
        if (scanned == 0) {
            return new Integer[]{0, 0};
        }

        // Round-2 review #2: single-statement batch UPDATE with RETURNING.
        // TODO(Phase 4): once the concurrent-test scaffold lands, add a
        // MemoryL1L2DemoterIntegrationTest#runOnce_batchUpdateHandlesRace
        // case that flips one pid back to category='user' with a fresh
        // last_accessed between SELECT and UPDATE and asserts the
        // demote_skipped metric fires for the raced pid.
        // The staleness predicate (same COALESCE(last_accessed, promoted_at,
        // created_at) < NOW() - make_interval(days => ?)) is re-checked
        // atomically: a promoter that flipped category back to 'user' between
        // our SELECT and UPDATE will still match category IN ('user','agent')
        // but the staleness guard and last_accessed refresh (promoter sets
        // last_accessed = NOW() on re-touch in L2 read path) would fail — so
        // race-losers simply drop out of the RETURNING set.
        String[] pidArray = new String[scanned];
        Map<String, Long> pidToTenant = new HashMap<>(scanned * 2);
        for (int i = 0; i < scanned; i++) {
            Map<String, Object> row = rows.get(i);
            String pid = (String) row.get("pid");
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            pidArray[i] = pid;
            pidToTenant.put(pid, tenantId);
        }

        List<String> demotedPids = jdbc.execute(
                (java.sql.Connection conn) -> {
                    java.sql.Array pgArray = conn.createArrayOf("text", pidArray);
                    try (java.sql.PreparedStatement ps = conn.prepareStatement(
                            "UPDATE ab_agent_memory "
                                    + "   SET category = 'session', "
                                    + "       demoted_at = NOW(), "
                                    + "       demotion_count = COALESCE(demotion_count, 0) + 1, "
                                    + "       updated_at = NOW() "
                                    + " WHERE pid = ANY(?) "
                                    + "   AND category IN ('user','agent') "
                                    + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                                    + "   AND (shareable IS NULL OR shareable = FALSE) "
                                    + "   AND COALESCE(last_accessed, promoted_at, created_at) "
                                    + "       < NOW() - make_interval(days => ?) "
                                    + " RETURNING pid")) {
                        ps.setArray(1, pgArray);
                        ps.setInt(2, ageDays);
                        List<String> out = new ArrayList<>();
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                out.add(rs.getString(1));
                            }
                        }
                        return out;
                    }
                });

        if (demotedPids == null) {
            demotedPids = List.of();
        }
        int demoted = demotedPids.size();

        // Batch audit INSERT for successfully-demoted rows only.
        if (!demotedPids.isEmpty()) {
            List<Object[]> auditBatch = new ArrayList<>(demotedPids.size());
            for (String pid : demotedPids) {
                Long tenantId = pidToTenant.get(pid);
                auditBatch.add(new Object[]{
                        UniqueIdGenerator.generate(),
                        tenantId,
                        pid,
                        MemoryL1L2PromotionMetrics.EVENT_TYPE_L2_DEMOTED
                });
            }
            jdbc.batchUpdate(
                    "INSERT INTO ab_agent_memory_tier_event "
                            + "  (pid, tenant_id, memory_pid, event_type, dedup_mode, "
                            + "   merged_into_pid, score_snapshot, source_run_id, created_at) "
                            + "VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, NOW())",
                    auditBatch);
        }

        // Metrics: one demoted outcome per actually-updated pid; one
        // demote_skipped per SELECTed pid that lost the race.
        Set<String> demotedSet = new HashSet<>(demotedPids);
        for (Map.Entry<String, Long> entry : pidToTenant.entrySet()) {
            Long tenantId = entry.getValue();
            if (demotedSet.contains(entry.getKey())) {
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

    /** Per-tick summary. {@code scanned} ≥ {@code demoted} (race-losers counted in scanned). */
    public record DemoteSummary(int scanned, int demoted) {
    }
}
