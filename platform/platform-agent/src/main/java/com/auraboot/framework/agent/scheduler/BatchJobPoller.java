package com.auraboot.framework.agent.scheduler;

import com.auraboot.framework.agent.dto.BatchStatus;
import com.auraboot.framework.agent.provider.AnthropicBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * P0-4 — periodic poller for Anthropic Messages Batch jobs.
 *
 * <p>The {@link AnthropicBatchService} writes one row per submission into
 * {@code ab_agent_batch_job} with {@code status='submitted'}. This poller
 * picks up rows in {@code ('submitted', 'in_progress')} and asks the upstream
 * batch API for their current status. State machine:
 *
 * <ul>
 *   <li>upstream {@code in_progress} -&gt; row {@code status='in_progress'} +
 *       refreshed counts (no terminal stamp).</li>
 *   <li>upstream {@code ended} with {@code errored=0} and {@code processing=0}
 *       -&gt; {@code status='succeeded'} + {@code completed_at=now}.</li>
 *   <li>upstream {@code ended} with all errored/canceled/expired and zero
 *       succeeded -&gt; {@code status='failed'} + {@code completed_at}.</li>
 *   <li>upstream {@code ended} mixed (some succeeded, some not) -&gt;
 *       {@code status='partial'} + {@code completed_at}.</li>
 * </ul>
 *
 * <p>Disabled by default ({@code aura.agent.batch.enabled=false}) — matches
 * the project's default-off scheduler convention. Default fixed delay is
 * 30 minutes (Anthropic's 24h SLA does not reward tighter polling).
 *
 * <p>Red-line compliance: per-row failure does not stop the tick — we log,
 * skip the row, and continue. The next tick will retry. No silent retry
 * loop or bypass of rollback-only.
 */
@Slf4j
@Component
public class BatchJobPoller {

    /** Job statuses that are still "open" — the only set the poller scans. */
    private static final List<String> OPEN_STATUSES = List.of("submitted", "in_progress");

    private final JdbcTemplate jdbc;
    private final AnthropicBatchService batchService;

    @Value("${aura.agent.batch.enabled:false}")
    private boolean enabled;

    public BatchJobPoller(JdbcTemplate jdbc, AnthropicBatchService batchService) {
        this.jdbc = jdbc;
        this.batchService = batchService;
    }

    /**
     * Default 30 minutes between ticks. Override via
     * {@code aura.agent.batch.poll-interval-ms}.
     */
    @Scheduled(fixedDelayString = "${aura.agent.batch.poll-interval-ms:1800000}")
    public void runScheduled() {
        if (!enabled) {
            return;
        }
        int updated = pollOnce();
        if (updated > 0) {
            log.info("BatchJobPoller tick: updated={}", updated);
        }
    }

    /**
     * One-shot poll — returns the number of rows whose status was changed.
     * Visible for tests / ops endpoints.
     */
    public int pollOnce() {
        List<Map<String, Object>> jobs = jdbc.queryForList(
                "SELECT pid, tenant_id, batch_id, purpose, request_count, status "
                        + "  FROM ab_agent_batch_job "
                        + " WHERE status IN ('submitted','in_progress') "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE)");

        int updated = 0;
        for (Map<String, Object> job : jobs) {
            try {
                if (advance(job)) {
                    updated++;
                }
            } catch (RuntimeException e) {
                // Per-row resilience: a single bad batch (e.g. revoked API
                // key, transient 500) must not block other tenants' jobs.
                // Logged at warn so ops sees it; next tick retries.
                log.warn("BatchJobPoller: failed to advance batch_id={} pid={}: {}",
                        job.get("batch_id"), job.get("pid"), e.getMessage());
            }
        }
        return updated;
    }

    /**
     * Pull the latest status from upstream and update the row.
     *
     * @return {@code true} if the row's status column actually changed.
     */
    private boolean advance(Map<String, Object> job) {
        String pid = (String) job.get("pid");
        String batchId = (String) job.get("batch_id");
        String currentStatus = (String) job.get("status");

        BatchStatus upstream = batchService.getBatch(batchId);
        if (upstream == null || upstream.getProcessingStatus() == null) {
            log.warn("BatchJobPoller: empty status for batch_id={}", batchId);
            return false;
        }

        BatchStatus.Counts counts = upstream.getRequestCounts();
        int succeeded = counts == null ? 0 : counts.getSucceeded();
        int errored   = counts == null ? 0 : counts.getErrored();
        int canceled  = counts == null ? 0 : counts.getCanceled();
        int expired   = counts == null ? 0 : counts.getExpired();
        int processing = counts == null ? 0 : counts.getProcessing();

        String nextStatus = mapStatus(upstream.getProcessingStatus(),
                succeeded, errored, canceled, expired, processing);
        boolean terminal = isTerminal(nextStatus);

        if (terminal) {
            jdbc.update(
                    "UPDATE ab_agent_batch_job "
                            + "   SET status = ?, succeeded_count = ?, errored_count = ?, "
                            + "       results_uri = COALESCE(?, results_uri), "
                            + "       completed_at = NOW() "
                            + " WHERE pid = ?",
                    nextStatus, succeeded, errored, upstream.getResultsUrl(), pid);
        } else {
            jdbc.update(
                    "UPDATE ab_agent_batch_job "
                            + "   SET status = ?, succeeded_count = ?, errored_count = ? "
                            + " WHERE pid = ?",
                    nextStatus, succeeded, errored, pid);
        }

        return !nextStatus.equals(currentStatus);
    }

    /**
     * Translate the upstream {@code processing_status} + counts into our
     * stored {@code ab_agent_batch_job.status} column. Visible for tests.
     */
    static String mapStatus(String upstreamStatus,
                            int succeeded, int errored, int canceled, int expired,
                            int processing) {
        if (upstreamStatus == null) {
            return "in_progress";
        }
        switch (upstreamStatus) {
            case "in_progress":
            case "canceling":
                return "in_progress";
            case "ended":
                // Terminal — figure out succeeded vs failed vs partial.
                if (succeeded > 0 && (errored + canceled + expired) == 0 && processing == 0) {
                    return "succeeded";
                }
                if (succeeded == 0 && (errored + canceled + expired) > 0) {
                    return "failed";
                }
                // Mixed (some succeeded, some not) or zero-everything edge case.
                return succeeded > 0 ? "partial" : "failed";
            default:
                // Unknown upstream status — keep us "open" so next tick retries.
                return "in_progress";
        }
    }

    private static boolean isTerminal(String status) {
        return "succeeded".equals(status)
                || "failed".equals(status)
                || "partial".equals(status);
    }

    /** Public for tests that want to see the open-status whitelist. */
    public static List<String> openStatuses() {
        return OPEN_STATUSES;
    }
}
