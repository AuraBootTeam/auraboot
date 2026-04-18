package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ACP Learning Loop Phase 5 — periodic promotion evaluation.
 *
 * Scans drafts whose status makes them eligible for promotion review
 * (REVIEWED_OK / SHADOW_RUNNING) and invokes
 * {@link PromotionEvaluator#evaluate} on each. The evaluator itself
 * decides whether to flip status to PROMOTED_PENDING_HUMAN or just
 * refresh the shadow_metrics JSON.
 *
 * This runner only batches the work — it doesn't duplicate evaluation
 * logic. Disabled by default; flip {@code acp.learning.promotion.scheduler.enabled}
 * to true once Shadow Mode is generating steady traffic.
 *
 * <p><b>Multi-node safety:</b> guarded by Postgres advisory lock
 * {@value #LOCK_KEY}. Distinct from {@link ShadowRunScheduler} (7301) so
 * the two schedulers never contend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionEvaluationRunner {

    /** Advisory-lock key; documented at class level. Distinct from ShadowRunScheduler (7301). */
    private static final long LOCK_KEY = 7302L;

    private final JdbcTemplate jdbcTemplate;
    private final PromotionEvaluator evaluator;

    @Value("${acp.learning.promotion.scheduler.enabled:false}")
    private boolean enabled;

    @Value("${acp.learning.promotion.scheduler.batch-size:50}")
    private int batchSize;

    @Scheduled(cron = "${acp.learning.promotion.scheduler.cron:0 */15 * * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int processed = runOnce();
        if (processed > 0) {
            log.info("PromotionEvaluationRunner: evaluated {} drafts", processed);
        }
    }

    /** Returns the number of drafts evaluated in this pass. */
    public int runOnce() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("PromotionEvaluationRunner: another instance holds advisory lock {}, skipping tick", LOCK_KEY);
            return 0;
        }
        try {
            return runOnceLocked();
        } finally {
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
        }
    }

    private int runOnceLocked() {
        List<String> draftPids = jdbcTemplate.queryForList(
                "SELECT pid FROM ab_agent_skill_draft " +
                        "WHERE status IN ('REVIEWED_OK', 'SHADOW_RUNNING') " +
                        "ORDER BY created_at ASC LIMIT ?",
                String.class, Math.max(1, Math.min(batchSize, 500)));
        int count = 0;
        for (String pid : draftPids) {
            try {
                evaluator.evaluate(pid);
                count++;
            } catch (Exception e) {
                log.warn("PromotionEvaluationRunner: evaluate failed for {}: {}", pid, e.getMessage());
            }
        }
        return count;
    }
}
