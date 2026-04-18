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
 * Memory Promotion activator (PR-66 Phase 2, plan §6.3).
 *
 * <p>Periodically promotes rows from {@code PROMOTED_SHADOW} to {@code ACTIVE}
 * once the 7-day shadow window has elapsed. The paired memory row has its
 * {@code shadow_mode} flag cleared so the grounding layer stops annotating
 * the memory as "under observation".
 *
 * <p>Advisory-lock key {@code 7304} — distinct from the extractor's
 * {@code 7303}.
 */
@Slf4j
@Service
public class MemoryPromotionActivator {

    private static final long LOCK_KEY = 7304L;

    private final JdbcTemplate jdbcTemplate;
    private final MemoryPromotionMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public MemoryPromotionActivator(JdbcTemplate jdbcTemplate,
                                    MemoryPromotionMetrics metrics,
                                    PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${acp.memory.promotion.activator.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "${acp.memory.promotion.activator.cron:0 */30 * * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int activated = runOnce();
        if (activated > 0) {
            log.info("MemoryPromotionActivator: activated {} shadow promotion(s)", activated);
        }
    }

    /** Returns the number of rows activated in this tick. */
    public int runOnce() {
        Integer result = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("MemoryPromotionActivator: advisory lock {} busy, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runOnceLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("MemoryPromotionActivator: pg_advisory_unlock({}) returned {}", LOCK_KEY, released);
                }
            }
        });
        return result == null ? 0 : result;
    }

    private int runOnceLocked() {
        // Collect rows whose shadow window has elapsed. We need the tenant_id
        // for the per-tenant ACTIVATE metric increment; doing the SELECT +
        // per-row UPDATEs rather than a bulk UPDATE keeps the metric honest.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, promoted_memory_pid "
                        + "FROM ab_agent_memory_promotion "
                        + "WHERE status = ? AND shadow_ends_at <= NOW()",
                MemoryPromotionApplier.STATUS_SHADOW);

        int activated = 0;
        for (Map<String, Object> row : rows) {
            String pid = (String) row.get("pid");
            String memoryPid = (String) row.get("promoted_memory_pid");
            Long tenantId = row.get("tenant_id") == null
                    ? null : ((Number) row.get("tenant_id")).longValue();

            if (memoryPid != null) {
                jdbcTemplate.update(
                        "UPDATE ab_agent_memory "
                                + "SET shadow_mode = FALSE, updated_at = NOW() "
                                + "WHERE pid = ?",
                        memoryPid);
            }

            int updated = jdbcTemplate.update(
                    "UPDATE ab_agent_memory_promotion "
                            + "SET status = ?, activated_at = NOW(), updated_at = NOW() "
                            + "WHERE pid = ? AND status = ?",
                    MemoryPromotionApplier.STATUS_ACTIVE, pid, MemoryPromotionApplier.STATUS_SHADOW);
            if (updated == 1) {
                activated++;
                metrics.recordDecision(tenantId, MemoryPromotionMetrics.DECISION_ACTIVATE, null);
            }
        }
        return activated;
    }
}
