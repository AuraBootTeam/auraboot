package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.BatchRequest;
import com.auraboot.framework.agent.dto.BatchResult;
import com.auraboot.framework.agent.dto.BatchStatus;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link AnthropicBatchService} — boots the full Spring
 * context so we exercise the real {@link AgentProperties} binding,
 * {@link JdbcTemplate} bean, and bean wiring, but stubs the WebClient with an
 * in-process {@code exchangeFunction} so no network traffic is needed.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@code submitBatch} parses the upstream id and writes a row to
 *       {@code ab_agent_batch_job} with {@code status='submitted'} and the
 *       returned {@code batch_id}.</li>
 *   <li>{@code getBatch} parses {@code processing_status} and per-status counts
 *       end-to-end through Jackson + the DTO snake-case mapping.</li>
 *   <li>{@code getResults} parses a JSONL stream into typed
 *       {@link BatchResult}s including succeeded / errored entries.</li>
 * </ol>
 */
@DisplayName("AnthropicBatchService (P0-4)")
class AnthropicBatchServiceIntegrationTest extends BaseIntegrationTest {

    private static final String BATCH_ID = "msgbatch_int_01";

    @Autowired
    private AnthropicBatchService batchService;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private JdbcTemplate jdbc;

    private String originalApiKey;

    @BeforeEach
    void seedConfig() {
        // Ensure submit's requireApiKey gate passes — store original and
        // restore in @AfterEach so we don't leak into sibling tests.
        originalApiKey = agentProperties.getAnthropic().getApiKey();
        agentProperties.getAnthropic().setApiKey("sk-ant-int-test");
    }

    @AfterEach
    void cleanup() {
        agentProperties.getAnthropic().setApiKey(originalApiKey);
        // Clean up the batch_job rows this test class created so we don't
        // leave fixture rows around. The test runs with @Rollback in its
        // outer transaction, but the JDBC writes from the service may live
        // in the same connection — either way the explicit DELETE is safe
        // and idempotent.
        jdbc.update("DELETE FROM ab_agent_batch_job WHERE batch_id = ?", BATCH_ID);
    }

    /**
     * Build a WebClient whose exchangeFunction returns the supplied JSON body
     * with HTTP 200, regardless of method/URL. Mirrors the helper used in
     * {@code AnthropicLlmProviderIntegrationTest}.
     */
    private WebClient cannedResponseClient(String responseJson) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(responseJson)
                        .build()))
                .build();
    }

    /** Swap the wired WebClient on the service so we don't hit the real API. */
    private void stubWebClient(String responseJson) {
        ReflectionTestUtils.setField(batchService, "webClient", cannedResponseClient(responseJson));
    }

    // ---------------------------------------------------------------------
    // (1) submitBatch — id round-trip + ab_agent_batch_job row written
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("submitBatch returns batch id and records ab_agent_batch_job row")
    void submitBatch_returnsBatchId_andRecordsJobRow() {
        String canned = "{"
                + "\"id\":\"" + BATCH_ID + "\","
                + "\"processing_status\":\"in_progress\","
                + "\"request_counts\":{\"processing\":2,\"succeeded\":0,\"errored\":0,\"canceled\":0,\"expired\":0}"
                + "}";
        stubWebClient(canned);

        BatchRequest req1 = BatchRequest.builder()
                .customId("mem_pid_a")
                .params(AnthropicRequest.builder()
                        .model("claude-haiku-4")
                        .max_tokens(64)
                        .messages(List.of(AnthropicRequest.Message.builder()
                                .role("user").content("score me").build()))
                        .build())
                .build();
        BatchRequest req2 = BatchRequest.builder()
                .customId("mem_pid_b")
                .params(AnthropicRequest.builder()
                        .model("claude-haiku-4")
                        .max_tokens(64)
                        .messages(List.of(AnthropicRequest.Message.builder()
                                .role("user").content("score me too").build()))
                        .build())
                .build();

        String returned = batchService.submitBatch(List.of(req1, req2),
                "memory_promotion_scoring");

        assertThat(returned).isEqualTo(BATCH_ID);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT batch_id, purpose, request_count, status, tenant_id, created_by "
                        + "  FROM ab_agent_batch_job WHERE batch_id = ?",
                BATCH_ID);
        assertThat(row.get("batch_id")).isEqualTo(BATCH_ID);
        assertThat(row.get("purpose")).isEqualTo("memory_promotion_scoring");
        assertThat(((Number) row.get("request_count")).intValue()).isEqualTo(2);
        assertThat(row.get("status")).isEqualTo("submitted");
        assertThat(((Number) row.get("tenant_id")).longValue())
                .as("tenant_id should be drawn from MetaContext set by BaseIntegrationTest")
                .isEqualTo(getTestTenant().getId());
    }

    // ---------------------------------------------------------------------
    // (2) getBatch — parses processing_status + counts
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("getBatch parses status and request counts")
    void getBatch_parsesStatusAndCounts() {
        String canned = "{"
                + "\"id\":\"msgbatch_x\","
                + "\"processing_status\":\"ended\","
                + "\"created_at\":\"2026-04-29T08:00:00Z\","
                + "\"ended_at\":\"2026-04-29T11:30:00Z\","
                + "\"results_url\":\"https://api.anthropic.com/v1/messages/batches/msgbatch_x/results\","
                + "\"request_counts\":{"
                + "  \"processing\":0,\"succeeded\":48,\"errored\":2,\"canceled\":0,\"expired\":0"
                + "}}";
        stubWebClient(canned);

        BatchStatus status = batchService.getBatch("msgbatch_x");

        assertThat(status).isNotNull();
        assertThat(status.getId()).isEqualTo("msgbatch_x");
        assertThat(status.getProcessingStatus()).isEqualTo("ended");
        assertThat(status.getEndedAt()).isNotNull();
        assertThat(status.getResultsUrl()).contains("/results");
        assertThat(status.getRequestCounts()).isNotNull();
        assertThat(status.getRequestCounts().getSucceeded()).isEqualTo(48);
        assertThat(status.getRequestCounts().getErrored()).isEqualTo(2);
        assertThat(status.getRequestCounts().getProcessing()).isZero();
    }

    // ---------------------------------------------------------------------
    // (3) getResults — JSONL stream into BatchResult list
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("getResults parses JSONL stream into typed BatchResult list")
    void getResults_parsesJsonlStream() {
        String jsonl =
                "{\"custom_id\":\"mem_pid_a\",\"result\":{\"type\":\"succeeded\",\"message\":{\"id\":\"msg_a\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"{\\\"score\\\":0.82}\"}],\"usage\":{\"input_tokens\":120,\"output_tokens\":18}}}}\n"
              + "{\"custom_id\":\"mem_pid_b\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"upstream busy\"}}}\n"
              + "{\"custom_id\":\"mem_pid_c\",\"result\":{\"type\":\"expired\"}}\n";
        stubWebClient(jsonl);

        List<BatchResult> results = batchService.getResults("msgbatch_x");

        assertThat(results).hasSize(3);

        BatchResult ok = results.get(0);
        assertThat(ok.getCustomId()).isEqualTo("mem_pid_a");
        assertThat(ok.getType()).isEqualTo("succeeded");
        assertThat(ok.getMessage()).isNotNull();
        assertThat(ok.getMessage().getContent().get(0).getText()).contains("\"score\":0.82");
        assertThat(ok.getMessage().getUsage()).isNotNull();
        assertThat(ok.getMessage().getUsage().getInput_tokens()).isEqualTo(120);

        BatchResult err = results.get(1);
        assertThat(err.getCustomId()).isEqualTo("mem_pid_b");
        assertThat(err.getType()).isEqualTo("errored");
        assertThat(err.getError()).containsEntry("type", "overloaded_error");

        BatchResult exp = results.get(2);
        assertThat(exp.getCustomId()).isEqualTo("mem_pid_c");
        assertThat(exp.getType()).isEqualTo("expired");
        assertThat(exp.getMessage()).isNull();
    }
}
