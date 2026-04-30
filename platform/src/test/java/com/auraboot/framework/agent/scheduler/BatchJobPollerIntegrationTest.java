package com.auraboot.framework.agent.scheduler;

import com.auraboot.framework.agent.dto.BatchStatus;
import com.auraboot.framework.agent.provider.AnthropicBatchService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BatchJobPoller} — verifies the cron's
 * status-machine end-to-end against a real PostgreSQL row in
 * {@code ab_agent_batch_job}, with {@link AnthropicBatchService} stubbed via
 * Mockito so we don't touch the real Anthropic endpoint.
 *
 * <p>Covers the brief's key transitions:
 * <ol>
 *   <li>{@code in_progress} upstream -&gt; row stays open with refreshed counts.</li>
 *   <li>{@code ended} + all-succeeded upstream -&gt; row flips to
 *       {@code status='succeeded'} with {@code completed_at} stamped.</li>
 * </ol>
 */
@DisplayName("BatchJobPoller (P0-4)")
class BatchJobPollerIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "BJP_";

    @Autowired
    private BatchJobPoller poller;

    @Autowired
    private JdbcTemplate jdbc;

    private AnthropicBatchService originalService;
    private AnthropicBatchService mockService;

    @BeforeEach
    void replaceServiceWithMock() {
        // Swap the wired AnthropicBatchService with a Mockito mock so we can
        // script different upstream replies per test. ReflectionTestUtils
        // restores cleanly in @AfterEach.
        originalService = (AnthropicBatchService)
                ReflectionTestUtils.getField(poller, "batchService");
        mockService = Mockito.mock(AnthropicBatchService.class);
        ReflectionTestUtils.setField(poller, "batchService", mockService);
    }

    @AfterEach
    void cleanup() {
        ReflectionTestUtils.setField(poller, "batchService", originalService);
        // Restrict cleanup to rows this test class created.
        jdbc.update(
                "DELETE FROM ab_agent_batch_job WHERE batch_id LIKE ?",
                TEST_PREFIX + "%");
    }

    private String insertOpenJob(String status, int requestCount) {
        String pid = UniqueIdGenerator.generate();
        String batchId = TEST_PREFIX + pid;
        jdbc.update(
                "INSERT INTO ab_agent_batch_job "
                        + "  (pid, tenant_id, batch_id, purpose, request_count, status, "
                        + "   submitted_at, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)",
                pid, getTestTenant().getId(), batchId,
                "memory_promotion_scoring", requestCount, status, getTestUser().getId());
        return batchId;
    }

    // ---------------------------------------------------------------------
    // (1) Terminal: ended + all-succeeded -> status='succeeded' + stamps
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("ended + all-succeeded -> row flips to succeeded with completed_at + counts")
    void poll_updatesInProgressJobToSucceeded_whenBatchEnded() {
        String batchId = insertOpenJob("in_progress", 5);

        BatchStatus.Counts counts = BatchStatus.Counts.builder()
                .processing(0).succeeded(5).errored(0).canceled(0).expired(0)
                .build();
        BatchStatus ended = BatchStatus.builder()
                .id(batchId)
                .processingStatus("ended")
                .createdAt(Instant.now().minusSeconds(3600))
                .endedAt(Instant.now())
                .resultsUrl("https://api.anthropic.com/v1/messages/batches/" + batchId + "/results")
                .requestCounts(counts)
                .build();
        Mockito.when(mockService.getBatch(ArgumentMatchers.eq(batchId))).thenReturn(ended);

        int updated = poller.pollOnce();

        assertThat(updated).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, succeeded_count, errored_count, results_uri, completed_at "
                        + "  FROM ab_agent_batch_job WHERE batch_id = ?", batchId);
        assertThat(row.get("status")).isEqualTo("succeeded");
        assertThat(((Number) row.get("succeeded_count")).intValue()).isEqualTo(5);
        assertThat(((Number) row.get("errored_count")).intValue()).isZero();
        assertThat(row.get("results_uri")).isNotNull();
        assertThat(row.get("completed_at"))
                .as("completed_at must be stamped on terminal transition")
                .isNotNull();
    }

    // ---------------------------------------------------------------------
    // (2) Non-terminal: in_progress upstream -> row stays in_progress, counts refreshed
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("in_progress upstream -> row stays in_progress with refreshed counts")
    void poll_keepsRowInProgress_whenBatchStillRunning() {
        String batchId = insertOpenJob("submitted", 8);

        BatchStatus.Counts counts = BatchStatus.Counts.builder()
                .processing(3).succeeded(5).errored(0).canceled(0).expired(0)
                .build();
        BatchStatus inProgress = BatchStatus.builder()
                .id(batchId)
                .processingStatus("in_progress")
                .createdAt(Instant.now().minusSeconds(60))
                .requestCounts(counts)
                .build();
        Mockito.when(mockService.getBatch(ArgumentMatchers.eq(batchId))).thenReturn(inProgress);

        poller.pollOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, succeeded_count, errored_count, completed_at "
                        + "  FROM ab_agent_batch_job WHERE batch_id = ?", batchId);
        assertThat(row.get("status")).isEqualTo("in_progress");
        assertThat(((Number) row.get("succeeded_count")).intValue()).isEqualTo(5);
        assertThat(row.get("completed_at"))
                .as("completed_at must remain null while batch is still running")
                .isNull();
    }

    // ---------------------------------------------------------------------
    // (3) mapStatus pure-function unit-style coverage on the public static helper
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("mapStatus returns 'partial' when ended with mixed succeeded+errored")
    void mapStatus_partialWhenMixed() {
        // 7 succeeded, 2 errored, 1 expired -> partial
        String s = BatchJobPoller.mapStatus("ended", 7, 2, 0, 1, 0);
        assertThat(s).isEqualTo("partial");

        // 0 succeeded, 5 errored -> failed
        String f = BatchJobPoller.mapStatus("ended", 0, 5, 0, 0, 0);
        assertThat(f).isEqualTo("failed");

        // in_progress upstream -> in_progress regardless of counts
        String p = BatchJobPoller.mapStatus("in_progress", 3, 0, 0, 0, 5);
        assertThat(p).isEqualTo("in_progress");
    }
}
