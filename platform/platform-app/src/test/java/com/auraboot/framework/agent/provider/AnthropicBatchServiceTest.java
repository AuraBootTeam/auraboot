package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AnthropicRequest;
import com.auraboot.framework.agent.dto.BatchRequest;
import com.auraboot.framework.agent.dto.BatchResult;
import com.auraboot.framework.agent.dto.BatchStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AnthropicBatchService} — focuses on JSONL parsing,
 * DTO round-trips, and input validation. The HTTP path is exercised in
 * {@code AnthropicBatchServiceIntegrationTest} where a stubbed WebClient is
 * available; here we pass {@code null} for {@code WebClient} / {@code JdbcTemplate}
 * so the unit tests stay fast and isolated.
 */
class AnthropicBatchServiceTest {

    private ObjectMapper objectMapper;
    private AnthropicBatchService service;

    @BeforeEach
    void setUp() {
        // Register JavaTimeModule so BatchStatus.created_at / ended_at
        // (java.time.Instant) deserialise from ISO strings — production uses
        // the Spring-provided ObjectMapper which auto-registers it.
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // ConfigurationProperties bean — populated for tests that reach the
        // requireApiKey gate; unused otherwise.
        AgentProperties props = new AgentProperties();
        AgentProperties.Anthropic anth = new AgentProperties.Anthropic();
        anth.setApiKey("sk-ant-test-key");
        anth.setBaseUrl("https://api.anthropic.com");
        props.setAnthropic(anth);
        this.service = new AnthropicBatchService(null, objectMapper, props, null);
    }

    // ---------------------------------------------------------------------
    // (a) DTO serialization round-trips
    // ---------------------------------------------------------------------

    @Test
    void batchRequestSerializesCustomIdAndParams() throws Exception {
        AnthropicRequest params = AnthropicRequest.builder()
                .model("claude-haiku-4")
                .max_tokens(64)
                .messages(List.of(AnthropicRequest.Message.builder()
                        .role("user").content("hello").build()))
                .build();
        BatchRequest req = BatchRequest.builder()
                .customId("mem_pid_01")
                .params(params)
                .build();

        String json = objectMapper.writeValueAsString(req);

        assertThat(json).contains("\"customId\":\"mem_pid_01\"");
        assertThat(json).contains("\"model\":\"claude-haiku-4\"");
        assertThat(json).contains("\"max_tokens\":64");
    }

    @Test
    void batchStatusDeserializesCountsAndProcessingStatus() throws Exception {
        String upstream = "{"
                + "\"id\":\"msgbatch_abc\","
                + "\"processing_status\":\"in_progress\","
                + "\"created_at\":\"2026-04-29T10:00:00Z\","
                + "\"request_counts\":{"
                + "  \"processing\":3,\"succeeded\":7,\"errored\":1,\"canceled\":0,\"expired\":0"
                + "}"
                + "}";

        BatchStatus status = objectMapper.readValue(upstream, BatchStatus.class);

        assertThat(status.getId()).isEqualTo("msgbatch_abc");
        assertThat(status.getProcessingStatus()).isEqualTo("in_progress");
        assertThat(status.getEndedAt()).isNull();
        assertThat(status.getRequestCounts()).isNotNull();
        assertThat(status.getRequestCounts().getProcessing()).isEqualTo(3);
        assertThat(status.getRequestCounts().getSucceeded()).isEqualTo(7);
        assertThat(status.getRequestCounts().getErrored()).isEqualTo(1);
    }

    @Test
    void batchStatusDeserializesEndedTerminalState() throws Exception {
        String upstream = "{"
                + "\"id\":\"msgbatch_done\","
                + "\"processing_status\":\"ended\","
                + "\"created_at\":\"2026-04-29T08:00:00Z\","
                + "\"ended_at\":\"2026-04-29T11:00:00Z\","
                + "\"results_url\":\"https://api.anthropic.com/v1/messages/batches/msgbatch_done/results\","
                + "\"request_counts\":{"
                + "  \"processing\":0,\"succeeded\":10,\"errored\":0,\"canceled\":0,\"expired\":0"
                + "}"
                + "}";

        BatchStatus status = objectMapper.readValue(upstream, BatchStatus.class);

        assertThat(status.getProcessingStatus()).isEqualTo("ended");
        assertThat(status.getEndedAt()).isNotNull();
        assertThat(status.getResultsUrl()).contains("/results");
        assertThat(status.getRequestCounts().getSucceeded()).isEqualTo(10);
    }

    // ---------------------------------------------------------------------
    // (b) JSONL parsing — happy path + error path
    // ---------------------------------------------------------------------

    @Test
    void parseJsonlHandlesMixedSucceededAndErroredLines() {
        String jsonl =
                "{\"custom_id\":\"a\",\"result\":{\"type\":\"succeeded\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}}\n"
              + "{\"custom_id\":\"b\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"too many\"}}}\n"
              + "{\"custom_id\":\"c\",\"result\":{\"type\":\"expired\"}}\n";

        List<BatchResult> parsed = service.parseJsonl(jsonl);

        assertThat(parsed).hasSize(3);

        // (a) succeeded — message available
        BatchResult ok = parsed.get(0);
        assertThat(ok.getCustomId()).isEqualTo("a");
        assertThat(ok.getType()).isEqualTo("succeeded");
        assertThat(ok.getMessage()).isNotNull();
        assertThat(ok.getMessage().getContent()).hasSize(1);
        assertThat(ok.getMessage().getContent().get(0).getText()).isEqualTo("ok");

        // (b) errored — error map populated, message null
        BatchResult err = parsed.get(1);
        assertThat(err.getCustomId()).isEqualTo("b");
        assertThat(err.getType()).isEqualTo("errored");
        assertThat(err.getMessage()).isNull();
        assertThat(err.getError()).isNotNull();
        assertThat(err.getError()).containsEntry("type", "overloaded_error");

        // (c) expired — no message no error
        BatchResult exp = parsed.get(2);
        assertThat(exp.getType()).isEqualTo("expired");
    }

    @Test
    void parseJsonlSkipsBlankLinesAndHandlesCrlf() {
        String jsonl =
                "\r\n"
              + "{\"custom_id\":\"a\",\"result\":{\"type\":\"succeeded\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\",\"content\":[]}}}\r\n"
              + "\r\n"
              + "{\"custom_id\":\"b\",\"result\":{\"type\":\"canceled\"}}\r\n";

        List<BatchResult> parsed = service.parseJsonl(jsonl);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getCustomId()).isEqualTo("a");
        assertThat(parsed.get(1).getCustomId()).isEqualTo("b");
    }

    @Test
    void parseJsonlReturnsEmptyForNullOrBlank() {
        assertThat(service.parseJsonl(null)).isEmpty();
        assertThat(service.parseJsonl("")).isEmpty();
        assertThat(service.parseJsonl("   \n  \r\n")).isEmpty();
    }

    @Test
    void parseJsonlThrowsOnMalformedLineWithLineNumber() {
        String jsonl = "{\"custom_id\":\"a\",\"result\":{\"type\":\"succeeded\"}}\n"
                + "this-is-not-json\n"
                + "{\"custom_id\":\"c\",\"result\":{\"type\":\"expired\"}}\n";

        assertThatThrownBy(() -> service.parseJsonl(jsonl))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("this-is-not-json");
    }

    // ---------------------------------------------------------------------
    // (c) Input validation
    // ---------------------------------------------------------------------

    @Test
    void submitBatchRejectsNullOrEmptyRequests() {
        assertThatThrownBy(() -> service.submitBatch(null, "memory_promotion_scoring"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one request");
        assertThatThrownBy(() -> service.submitBatch(List.of(), "memory_promotion_scoring"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one request");
    }

    @Test
    void submitBatchRejectsBlankPurpose() {
        BatchRequest req = BatchRequest.builder()
                .customId("a")
                .params(AnthropicRequest.builder().model("m").max_tokens(1).build())
                .build();
        assertThatThrownBy(() -> service.submitBatch(List.of(req), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
        assertThatThrownBy(() -> service.submitBatch(List.of(req), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
    }

    @Test
    void getBatchRejectsBlankBatchId() {
        assertThatThrownBy(() -> service.getBatch(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getBatch("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getResultsRejectsBlankBatchId() {
        assertThatThrownBy(() -> service.getResults(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitBatchRequiresApiKey() {
        AgentProperties propsWithoutKey = new AgentProperties();
        propsWithoutKey.setAnthropic(new AgentProperties.Anthropic());
        AnthropicBatchService noKeyService = new AnthropicBatchService(
                null, objectMapper, propsWithoutKey, null);

        BatchRequest req = BatchRequest.builder()
                .customId("a")
                .params(AnthropicRequest.builder().model("m").max_tokens(1).build())
                .build();

        assertThatThrownBy(() -> noKeyService.submitBatch(List.of(req), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void batchResultEnvelopeAccessorsFlattenResultBlock() {
        BatchResult.Envelope env = BatchResult.Envelope.builder()
                .type("errored")
                .error(Map.of("type", "rate_limit_error", "message", "slow down"))
                .build();
        BatchResult res = BatchResult.builder().customId("xx").result(env).build();

        assertThat(res.getType()).isEqualTo("errored");
        assertThat(res.getMessage()).isNull();
        assertThat(res.getError()).containsEntry("type", "rate_limit_error");

        BatchResult empty = new BatchResult();
        assertThat(empty.getType()).isNull();
        assertThat(empty.getMessage()).isNull();
        assertThat(empty.getError()).isNull();
    }
}
