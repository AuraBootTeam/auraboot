package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Approval-notification outbox with exponential-backoff retry
 * (spec §3.5.4). Two responsibilities:
 *
 *   1. {@link #enqueue} — called synchronously from
 *      {@link AgentApprovalGateService#checkAndRequestApproval} when an
 *      approval row is created. Writes a pending notification per
 *      approver and returns immediately so the caller isn't blocked on
 *      channel latency.
 *   2. {@link #processDue} — scheduled worker (1/min) picks pending rows
 *      whose next_retry_at has elapsed, dispatches through
 *      {@link ApprovalNotificationSender}, and on failure bumps the
 *      retry_count + reschedules via {@link #BACKOFFS_SECONDS}. Caps at
 *      5 attempts → 'failed' terminal state.
 *
 * The sender (email / sms / inbox / webhook) is behind an interface so
 * real channels land later; v0 uses a logging stub.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalNotificationOutbox {

    /**
     * Retry intervals from spec §3.5.4: 1 min → 5 min → 30 min → 2 h → 12 h.
     * Array index = retry_count (0 = first retry after initial failure).
     * retry_count ≥ length → terminal 'failed'.
     */
    public static final long[] BACKOFFS_SECONDS = {60L, 300L, 1800L, 7200L, 43200L};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApprovalNotificationSender sender;

    // Field-level default + @Value override. The field default keeps the
    // service usable when constructed manually (tests, CLI runners) where
    // @Value injection is bypassed; Spring-managed instances still receive
    // the config value via property override.
    @Value("${acp.approval.outbox.batch-size:50}")
    private int batchSize = 50;

    // =========================================================================
    // Enqueue
    // =========================================================================

    /**
     * Enqueue one notification row. Returns the generated pid.
     * The caller typically invokes this once per approver.
     */
    public String enqueue(Long tenantId, String approvalPid, String recipientKind,
                           String recipientId, String channel, Map<String, Object> payload) {
        String pid = UniqueIdGenerator.generate();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception e) {
            payloadJson = "{}";
        }

        jdbcTemplate.update(
                "INSERT INTO ab_agent_approval_notification_outbox " +
                        "(pid, tenant_id, approval_pid, recipient_kind, recipient_id, " +
                        " channel, payload, status, retry_count, next_retry_at, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'pending', 0, NOW(), NOW(), NOW())",
                pid, tenantId, approvalPid, recipientKind, recipientId,
                channel == null ? "inbox" : channel, payloadJson);
        return pid;
    }

    // =========================================================================
    // Process due
    // =========================================================================

    /** Scheduled worker — every minute, pick up a batch of due rows and dispatch. */
    @Scheduled(cron = "${acp.approval.outbox.cron:0 * * * * *}")
    public int processDue() {
        List<Map<String, Object>> due = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, approval_pid, recipient_kind, recipient_id, " +
                        "       channel, payload::text AS payload_json, retry_count " +
                        "FROM ab_agent_approval_notification_outbox " +
                        "WHERE status = 'pending' AND next_retry_at <= NOW() " +
                        "ORDER BY next_retry_at ASC " +
                        "LIMIT ?", batchSize);

        int dispatched = 0;
        for (Map<String, Object> row : due) {
            if (attemptDispatch(row)) dispatched++;
        }
        if (!due.isEmpty()) {
            log.info("ApprovalOutbox: {}/{} dispatched in this tick", dispatched, due.size());
        }
        return dispatched;
    }

    /** Returns true on successful delivery. */
    boolean attemptDispatch(Map<String, Object> row) {
        String pid = (String) row.get("pid");
        try {
            Map<String, Object> payload = parsePayload((String) row.get("payload_json"));
            sender.send(
                    (String) row.get("channel"),
                    (String) row.get("recipient_kind"),
                    (String) row.get("recipient_id"),
                    (String) row.get("approval_pid"),
                    payload);
            markDelivered(pid);
            return true;
        } catch (Exception e) {
            int retryCount = ((Number) row.get("retry_count")).intValue();
            scheduleNextAttempt(pid, retryCount, e.getMessage());
            return false;
        }
    }

    private void markDelivered(String pid) {
        jdbcTemplate.update(
                "UPDATE ab_agent_approval_notification_outbox " +
                        "SET status = 'delivered', delivered_at = NOW(), updated_at = NOW() " +
                        "WHERE pid = ?", pid);
    }

    private void scheduleNextAttempt(String pid, int currentRetryCount, String errorMessage) {
        int nextRetry = currentRetryCount + 1;
        if (nextRetry > BACKOFFS_SECONDS.length) {
            jdbcTemplate.update(
                    "UPDATE ab_agent_approval_notification_outbox " +
                            "SET status = 'failed', retry_count = ?, last_error = ?, updated_at = NOW() " +
                            "WHERE pid = ?", nextRetry, truncate(errorMessage), pid);
            log.warn("ApprovalOutbox: notification {} exhausted {} retries — failed", pid, BACKOFFS_SECONDS.length);
            return;
        }
        long backoffSeconds = BACKOFFS_SECONDS[currentRetryCount];
        jdbcTemplate.update(
                "UPDATE ab_agent_approval_notification_outbox " +
                        "SET retry_count = ?, last_error = ?, " +
                        "    next_retry_at = NOW() + (? || ' seconds')::interval, " +
                        "    updated_at = NOW() " +
                        "WHERE pid = ?",
                nextRetry, truncate(errorMessage), String.valueOf(backoffSeconds), pid);
    }

    private Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** Test-only inspection. */
    public Map<String, Object> peek(String pid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, status, retry_count, next_retry_at, last_error, delivered_at " +
                        "FROM ab_agent_approval_notification_outbox WHERE pid = ?", pid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Pluggable sender — real implementations dispatch through the
     * notification / IM / email / webhook stack; default is a logging
     * stub that always succeeds.
     */
    public interface ApprovalNotificationSender {
        void send(String channel, String recipientKind, String recipientId,
                   String approvalPid, Map<String, Object> payload);
    }

    @Service
    @Slf4j
    public static class LoggingSender implements ApprovalNotificationSender {
        @Override
        public void send(String channel, String recipientKind, String recipientId,
                          String approvalPid, Map<String, Object> payload) {
            log.info("[ApprovalNotification] channel={} kind={} id={} approval={} payload-keys={}",
                    channel, recipientKind, recipientId, approvalPid,
                    payload == null ? 0 : payload.size());
        }
    }
}
