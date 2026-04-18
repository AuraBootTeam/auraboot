package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
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
 * Memory Promotion expirer (PR-66 Phase 2, plan §6.4).
 *
 * <p>Daily cleanup of terminal-ish promotion rows:
 * <ul>
 *   <li>{@code DRAFT_PENDING_REVIEW} older than 30 days → {@code EXPIRED}
 *       (reject_reason='other', review_comment documents the auto-expiry;
 *       this still counts as a soft reject for the learning signal).</li>
 *   <li>{@code REVIEWED_REJECTED} older than 90 days → {@code DISCARDED}
 *       (retention cleanup — no metric; simply takes the row out of the
 *       audit tabs).</li>
 * </ul>
 *
 * <p>Advisory-lock key {@code 7305} — distinct from the extractor (7303)
 * and activator (7304).
 */
@Slf4j
@Service
public class MemoryPromotionExpirer {

    private static final long LOCK_KEY = 7305L;

    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_DISCARDED = "DISCARDED";
    private static final String EXPIRED_REJECT_REASON = "other";
    private static final String EXPIRED_COMMENT =
            "auto-expired after 30d with no reviewer action";

    private final JdbcTemplate jdbcTemplate;
    private final MemoryPromotionMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public MemoryPromotionExpirer(JdbcTemplate jdbcTemplate,
                                  MemoryPromotionMetrics metrics,
                                  PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${acp.memory.promotion.expirer.enabled:false}")
    private boolean enabled;

    /** Daily at 03:45 UTC (after the extractor's 03:30 window). */
    @Scheduled(cron = "${acp.memory.promotion.expirer.cron:0 45 3 * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int total = runOnce();
        if (total > 0) {
            log.info("MemoryPromotionExpirer: expired/discarded {} row(s)", total);
        }
    }

    /**
     * Returns total rows touched (expired-drafts + discarded-rejects).
     */
    public int runOnce() {
        Integer result = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("MemoryPromotionExpirer: advisory lock {} busy, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runOnceLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("MemoryPromotionExpirer: pg_advisory_unlock({}) returned {}", LOCK_KEY, released);
                }
            }
        });
        return result == null ? 0 : result;
    }

    private int runOnceLocked() {
        int total = 0;

        // 1. DRAFT > 30d → EXPIRED. Collect tenant IDs so each expiry
        //    increments the per-tenant decision counter.
        List<Map<String, Object>> staleDrafts = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id FROM ab_agent_memory_promotion "
                        + "WHERE status = ? AND created_at < NOW() - INTERVAL '30 days'",
                MemoryPromotionApplier.STATUS_DRAFT);
        for (Map<String, Object> row : staleDrafts) {
            String pid = (String) row.get("pid");
            Long tenantId = row.get("tenant_id") == null
                    ? null : ((Number) row.get("tenant_id")).longValue();
            int updated = jdbcTemplate.update(
                    "UPDATE ab_agent_memory_promotion "
                            + "SET status = ?, reject_reason = ?, review_comment = ?, "
                            + "    reviewed_at = NOW(), updated_at = NOW() "
                            + "WHERE pid = ? AND status = ?",
                    STATUS_EXPIRED, EXPIRED_REJECT_REASON, EXPIRED_COMMENT,
                    pid, MemoryPromotionApplier.STATUS_DRAFT);
            if (updated == 1) {
                total++;
                metrics.recordDecision(tenantId, MemoryPromotionMetrics.DECISION_EXPIRE, null);
            }
        }

        // 2. REJECTED > 90d → DISCARDED. Bulk update is fine — no per-row
        //    metric needed for the retention sweep.
        int discarded = jdbcTemplate.update(
                "UPDATE ab_agent_memory_promotion "
                        + "SET status = ?, updated_at = NOW() "
                        + "WHERE status = ? AND reviewed_at < NOW() - INTERVAL '90 days'",
                STATUS_DISCARDED, MemoryPromotionApplier.STATUS_REJECTED);
        total += discarded;

        return total;
    }
}
