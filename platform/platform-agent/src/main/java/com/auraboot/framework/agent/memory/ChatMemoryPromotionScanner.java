package com.auraboot.framework.agent.memory;

import com.auraboot.framework.agent.metrics.MemoryL1L2PromotionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * D1 (execution-architecture review, owner-approved 2026-07-19) — the chat
 * memory promotion channel.
 *
 * <p>Chat-turn L1 rows ({@code category='conversation_turn'}, written by
 * {@code TurnCompletionMemoryListener}) were structurally excluded from L2
 * promotion twice over: the event/orphan pipelines only consider
 * {@code category='session'}, and their importance (3–5) sits below the
 * durable pipeline's gate of 6 (review G2). This scanner gives them their own
 * cron-based channel:
 * <ol>
 *   <li>Session boundary = idle age. A chat "session" has no natural
 *       {@code SessionEndedEvent}; a row older than
 *       {@code acp.memory.chat-promotion.idle-minutes} (default 60) belongs
 *       to a conversation that has almost certainly gone idle. Per-row age is
 *       the honest v1 proxy; conversation-level idle precision can follow
 *       once the turn-observation telemetry justifies it.</li>
 *   <li>Candidate gate = importance ≥ {@value #CHAT_IMPORTANCE_GATE}:
 *       SYNC_ACTION (4) and ACP_RUN (5) turns qualify; CONTEXTUAL_ANSWER (3)
 *       stays L1 by design. Passing the gate only makes a row a CANDIDATE —
 *       {@link MemoryL1L2Promoter#promoteCandidate} still applies the same
 *       scoring (importance/access/recency/uniqueness ≥ 0.65) and dedup as
 *       the durable pipeline, so a never-recalled one-off action will score
 *       low and stay L1. That is intended: chat rows earn L2 through recall,
 *       not through mere existence.</li>
 *   <li>Delegation reuses the promoter verbatim (source category
 *       {@code conversation_turn}); rescue and durable paths can never
 *       diverge in scoring/dedup semantics.</li>
 * </ol>
 *
 * <p>Advisory lock key {@code 7313} (7301-7308, 7311, 7312 taken).
 * Disabled by default ({@code acp.memory.chat-promotion.enabled=false}) —
 * matches every other scheduler in this subsystem; activation is an ops
 * decision. {@code runOnce()} is the test/ops entry either way.
 */
@Slf4j
@Service
public class ChatMemoryPromotionScanner {

    /** Advisory lock key — see class javadoc for the allocation table. */
    public static final long LOCK_KEY = 7313L;

    /** Per-tick row cap so the lock never hogs one connection for minutes. */
    public static final int BATCH_CAP = 500;

    /** Source rows this channel owns. */
    public static final String SOURCE_CATEGORY = "conversation_turn";

    /**
     * Candidate gate for chat rows: SYNC_ACTION (4) / ACP_RUN (5) qualify,
     * CONTEXTUAL_ANSWER (3) stays L1 (review D1 decision).
     */
    public static final int CHAT_IMPORTANCE_GATE = 4;

    /** Leader-election job code (multi-instance single-flight). */
    public static final String JOB_CHAT_PROMOTION = "memory_chat_promotion";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** Per-row REQUIRES_NEW so a single poison row does not roll back the tick. */
    private final TransactionTemplate txPerRow;
    private final MemoryL1L2Promoter promoter;
    private final MemoryL1L2PromotionMetrics metrics;
    private final MemoryL1L2LeaderElection leaderElection;

    @Value("${acp.memory.chat-promotion.enabled:false}")
    private boolean enabled;

    /** Idle window in minutes — a row older than this is session-over. */
    @Value("${acp.memory.chat-promotion.idle-minutes:60}")
    private int idleMinutes;

    public ChatMemoryPromotionScanner(JdbcTemplate jdbc,
                                      PlatformTransactionManager txManager,
                                      MemoryL1L2Promoter promoter,
                                      MemoryL1L2PromotionMetrics metrics,
                                      MemoryL1L2LeaderElection leaderElection) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.txPerRow = new TransactionTemplate(txManager);
        this.txPerRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.promoter = promoter;
        this.metrics = metrics;
        this.leaderElection = leaderElection;
    }

    /** Every 15 minutes; override via {@code acp.memory.chat-promotion.cron}. */
    @Scheduled(cron = "${acp.memory.chat-promotion.cron:0 */15 * * * *}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        MemoryL1L2OrphanScanner.ScanSummary summary = runOnce();
        if (summary.candidates() > 0) {
            log.info("ChatMemoryPromotionScanner tick: {}", summary);
        }
    }

    /**
     * One-shot entry point for tests / ops. Returns per-tick counts (never
     * cumulative). Runs in a single transaction, acquires the advisory lock,
     * and releases on commit.
     */
    public MemoryL1L2OrphanScanner.ScanSummary runOnce() {
        if (!leaderElection.acquire(JOB_CHAT_PROMOTION)) {
            log.debug("ChatMemoryPromotionScanner: not leader for {}, skipping tick", JOB_CHAT_PROMOTION);
            metrics.recordLeaderSkipped(JOB_CHAT_PROMOTION, leaderElection.getInstanceId());
            return new MemoryL1L2OrphanScanner.ScanSummary(0, 0, 0, 0, 0);
        }
        Integer[] counts = tx.execute(status -> {
            Boolean acquired = jdbc.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("ChatMemoryPromotionScanner: advisory lock {} busy", LOCK_KEY);
                return new Integer[]{0, 0, 0, 0, 0};
            }
            try {
                return scanLocked();
            } finally {
                Boolean released = jdbc.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("ChatMemoryPromotionScanner: pg_advisory_unlock({}) returned {}",
                            LOCK_KEY, released);
                }
            }
        });
        if (counts == null) {
            return new MemoryL1L2OrphanScanner.ScanSummary(0, 0, 0, 0, 0);
        }
        return new MemoryL1L2OrphanScanner.ScanSummary(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private Integer[] scanLocked() {
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT pid, tenant_id, memory_agent_id, memory_type, memory_title, "
                        + "       memory_content, importance, access_count, created_at, "
                        + "       scope, scope_key, embedding, source_run_id "
                        + "  FROM ab_agent_memory "
                        + " WHERE category = ? "
                        + "   AND importance >= ? "
                        + "   AND promoted_at IS NULL "
                        + "   AND created_at < NOW() - make_interval(mins => ?) "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + " ORDER BY created_at ASC "
                        + " LIMIT ?",
                SOURCE_CATEGORY, CHAT_IMPORTANCE_GATE, idleMinutes, BATCH_CAP);

        int promoted = 0;
        int skippedDup = 0;
        int skippedDupSemantic = 0;
        int skippedLowScore = 0;
        int rowFailures = 0;

        Instant now = Instant.now();
        for (Map<String, Object> row : candidates) {
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String sourceRunId = (String) row.get("source_run_id");
            try {
                MemoryL1L2Promoter.Outcome o = txPerRow.execute(status ->
                        promoter.promoteCandidate(tenantId, sourceRunId, row, now, SOURCE_CATEGORY));
                if (o == null) {
                    rowFailures++;
                    continue;
                }
                switch (o) {
                    case PROMOTED -> promoted++;
                    case DEDUP_HIT -> skippedDup++;
                    case DEDUP_HIT_SEMANTIC -> skippedDupSemantic++;
                    case LOW_SCORE -> skippedLowScore++;
                }
            } catch (RuntimeException e) {
                metrics.recordPromotionOutcome(tenantId, MemoryL1L2PromotionMetrics.OUTCOME_FAILED);
                log.warn("ChatMemoryPromotionScanner: promoter failed for pid={} tenant={} — skipping row",
                        row.get("pid"), tenantId, e);
                rowFailures++;
            }
        }
        if (rowFailures > 0) {
            log.info("ChatMemoryPromotionScanner: tick completed with {} row failures (isolated)", rowFailures);
        }

        return new Integer[]{candidates.size(), promoted, skippedDup, skippedDupSemantic, skippedLowScore};
    }
}
