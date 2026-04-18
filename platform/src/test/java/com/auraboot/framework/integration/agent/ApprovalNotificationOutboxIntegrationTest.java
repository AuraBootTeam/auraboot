package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ApprovalNotificationOutbox;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-21: Approval notification outbox with exponential-backoff retry.
 * Pins: enqueue → due selection → successful delivery path → retry path
 * → exhaustion transitions to 'failed'.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ApprovalNotificationOutbox (PR-21)")
class ApprovalNotificationOutboxIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String approvalPid;

    @BeforeEach
    void setup() {
        tenantId = 9_400_000L + System.nanoTime() % 100_000;
        approvalPid = "apv_" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_approval_notification_outbox WHERE tenant_id = ?", tenantId);
    }

    /** Build a fresh outbox using a controllable sender. */
    private ApprovalNotificationOutbox newOutbox(ApprovalNotificationOutbox.ApprovalNotificationSender sender) {
        return new ApprovalNotificationOutbox(
                jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                sender);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("enqueue writes a pending row with next_retry_at = NOW")
    void enqueue_writes_pending() {
        var outbox = newOutbox((ch, kind, id, av, pl) -> {}); // noop sender
        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of("title", "test"));

        Map<String, Object> row = outbox.peek(pid);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(0);
        assertThat(row.get("next_retry_at")).isNotNull();
    }

    @Test
    @DisplayName("processDue marks the row delivered on sender success")
    void process_success_marks_delivered() {
        List<String> sent = new ArrayList<>();
        var outbox = newOutbox((ch, kind, id, av, pl) -> sent.add(av));

        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of());
        // Diagnostic: count the rows the worker's SELECT will actually see.
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval_notification_outbox " +
                        "WHERE status = 'pending' AND next_retry_at <= NOW() AND pid = ?",
                Integer.class, pid);
        assertThat(pending).as("enqueue's row must be due right away").isEqualTo(1);

        int dispatched = outbox.processDue();

        assertThat(dispatched).isGreaterThanOrEqualTo(1);
        assertThat(sent).contains(approvalPid);
        Map<String, Object> row = outbox.peek(pid);
        assertThat(row.get("status")).isEqualTo("delivered");
        assertThat(row.get("delivered_at")).isNotNull();
    }

    @Test
    @DisplayName("processDue on sender exception bumps retry_count and reschedules")
    void process_failure_reschedules() {
        var outbox = newOutbox((ch, kind, id, av, pl) -> {
            throw new RuntimeException("smtp down");
        });
        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of());
        outbox.processDue();

        Map<String, Object> row = outbox.peek(pid);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(((Number) row.get("retry_count")).intValue()).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("smtp down");
        // next_retry_at bumped forward by BACKOFFS_SECONDS[0] = 60s
        assertThat(row.get("next_retry_at")).isNotNull();
    }

    @Test
    @DisplayName("retry_count > BACKOFFS length → status 'failed' terminal")
    void exhausted_retries_mark_failed() {
        var outbox = newOutbox((ch, kind, id, av, pl) -> {
            throw new RuntimeException("persistent failure");
        });
        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of());

        // Fast-forward: run processDue once per backoff slot + 1, forcing
        // next_retry_at to NOW between attempts so the worker picks them up.
        for (int i = 0; i <= ApprovalNotificationOutbox.BACKOFFS_SECONDS.length; i++) {
            jdbc.update("UPDATE ab_agent_approval_notification_outbox SET next_retry_at = NOW() " +
                    "WHERE pid = ? AND status = 'pending'", pid);
            outbox.processDue();
        }

        Map<String, Object> row = outbox.peek(pid);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(((Number) row.get("retry_count")).intValue())
                .isGreaterThan(ApprovalNotificationOutbox.BACKOFFS_SECONDS.length);
    }

    @Test
    @DisplayName("future next_retry_at excludes row from the due batch")
    void future_retry_not_due() {
        List<String> sent = new ArrayList<>();
        var outbox = newOutbox((ch, kind, id, av, pl) -> sent.add(av));
        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of());
        // Push next_retry into the future.
        jdbc.update("UPDATE ab_agent_approval_notification_outbox " +
                        "SET next_retry_at = NOW() + INTERVAL '1 hour' WHERE pid = ?", pid);

        int dispatched = outbox.processDue();
        assertThat(dispatched).isZero();
        assertThat(sent).isEmpty();

        Map<String, Object> row = outbox.peek(pid);
        assertThat(row.get("status")).isEqualTo("pending");
    }

    @Test
    @DisplayName("backoff interval matches spec §3.5.4 — first retry ≈ 60s away")
    void backoff_schedule_spec() {
        var outbox = newOutbox((ch, kind, id, av, pl) -> {
            throw new RuntimeException("fail");
        });
        String pid = outbox.enqueue(tenantId, approvalPid, "user", "u1", "inbox", Map.of());
        outbox.processDue();

        Long secondsAhead = jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (next_retry_at - NOW()))::bigint " +
                        "FROM ab_agent_approval_notification_outbox WHERE pid = ?",
                Long.class, pid);
        assertThat(secondsAhead).isBetween(50L, 75L);
    }
}
