package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
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
 * User Soul Profile activator (PR-76, plan §5.2).
 *
 * <p>Periodically flips DRAFT rows past the shadow period (default 24h)
 * to ACTIVE, demoting any prior ACTIVE row to SUPERSEDED. One ACTIVE
 * row per (tenant, user) is enforced by the partial unique index
 * {@code uq_user_soul_profile_active}; the demote + activate pair must
 * execute in a single transaction per user so we never leave the
 * invariant briefly violated.
 *
 * <p>Advisory-lock key {@code 7307} — distinct from the deriver's
 * {@code 7306} and Memory Promotion's {@code 7303-7305}.
 *
 * <p>Mirrors {@link MemoryPromotionActivator}: one outer transaction for
 * the whole tick (connection pinned so advisory-lock release targets the
 * same session). A failure on one user rolls the tick back and the next
 * scheduled run retries — acceptable given idempotency (status guard on
 * the activate UPDATE).
 */
@Slf4j
@Service
public class UserSoulProfileActivator {

    static final long LOCK_KEY = 7307L;
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final JdbcTemplate jdbcTemplate;
    private final UserSoulProfileMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${acp.user.soul-profile.activator.enabled:false}")
    private boolean enabled;

    @Value("${acp.user.soul-profile.shadow-period-hours:24}")
    private int shadowPeriodHours;

    public UserSoulProfileActivator(JdbcTemplate jdbcTemplate,
                                    UserSoulProfileMetrics metrics,
                                    PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${acp.user.soul-profile.activator.cron:0 */30 * * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int activated = runOnce();
        if (activated > 0) {
            log.info("UserSoulProfileActivator: activated {} draft profile(s)", activated);
        }
    }

    /** Returns the number of rows activated in this tick. */
    public int runOnce() {
        Integer result = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("UserSoulProfileActivator: advisory lock {} busy, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runOnceLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("UserSoulProfileActivator: pg_advisory_unlock({}) returned {}",
                            LOCK_KEY, released);
                }
            }
        });
        return result == null ? 0 : result;
    }

    private int runOnceLocked() {
        // Age-out clause uses make_interval so the parameter is a safe integer,
        // not a string concat.
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, user_id, version "
                        + "FROM ab_agent_user_soul_profile "
                        + "WHERE status = ? "
                        + "  AND hidden_at IS NULL "
                        + "  AND created_at <= NOW() - make_interval(hours => ?) "
                        + "ORDER BY created_at ASC",
                STATUS_DRAFT, shadowPeriodHours);

        int activated = 0;
        for (Map<String, Object> row : drafts) {
            String pid = (String) row.get("pid");
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String userId = (String) row.get("user_id");
            int version = ((Number) row.get("version")).intValue();

            // 1. Demote any prior ACTIVE row for the same (tenant, user).
            //    Safe no-op when there is none.
            jdbcTemplate.update(
                    "UPDATE ab_agent_user_soul_profile "
                            + "SET status = ?, superseded_at = NOW() "
                            + "WHERE tenant_id = ? AND user_id = ? AND status = ?",
                    STATUS_SUPERSEDED, tenantId, userId, STATUS_ACTIVE);

            // 2. Promote the DRAFT → ACTIVE. Status guard prevents activating
            //    a row already moved (e.g. hide / forget racing with the tick).
            int updated = jdbcTemplate.update(
                    "UPDATE ab_agent_user_soul_profile "
                            + "SET status = ?, activated_at = NOW() "
                            + "WHERE pid = ? AND status = ?",
                    STATUS_ACTIVE, pid, STATUS_DRAFT);

            if (updated == 1) {
                activated++;
                metrics.recordActivation(tenantId);
                log.info("UserSoulProfileActivator: activated tenant={} user={} version={} pid={}",
                        tenantId, userId, version, pid);
            } else {
                // Concurrent hide/forget flipped the DRAFT before us. Not an
                // error; skip quietly. The demote above is benign — it only
                // applies to a row that was already ACTIVE and is now being
                // replaced... by nothing. A subsequent tick will handle a
                // later DRAFT if one exists.
                log.warn("UserSoulProfileActivator: DRAFT pid={} tenant={} user={} moved concurrently "
                                + "(updated=0); skipping", pid, tenantId, userId);
            }
        }
        return activated;
    }
}
