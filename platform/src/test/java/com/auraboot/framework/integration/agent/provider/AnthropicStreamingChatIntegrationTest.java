package com.auraboot.framework.integration.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.AnthropicLlmProvider;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link AnthropicLlmProvider#streamChat} (E.1 Phase 1).
 *
 * <p>Stubs the {@code aiWebClient} via {@link ReflectionTestUtils} so we
 * exercise the real SSE event-mapping logic without touching the wire. Three
 * canonical cases (per spec):
 *
 * <ul>
 *   <li><b>Case A</b> — multi-chunk text delta sequence aggregates correctly</li>
 *   <li><b>Case B</b> — explicit {@code error} SSE event surfaces as
 *       {@code Flux.error}, with NO fallback to sync (Q5)</li>
 *   <li><b>Case C</b> — message_stop with no preceding deltas yields an
 *       empty aggregate, terminal chunk still {@code done=true}</li>
 * </ul>
 */
class AnthropicStreamingChatIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AnthropicLlmProvider provider;

    /**
     * Build a stub WebClient whose response body is a Server-Sent Events
     * stream with the supplied {@code event:/data:} pairs. Mirrors the wire
     * shape Anthropic emits when {@code stream:true}.
     */
    private WebClient sseClient(String sseBody) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                        .body(sseBody)
                        .build()))
                .build();
    }

    @Test
    void caseA_fiveChunkDeltaSequenceAggregatesToFullText() {
        String sse = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_a\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\",\"usage\":{\"input_tokens\":12,\"output_tokens\":0}}}",
                "",
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\", \"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" 🌍\"}}",
                "",
                "event: content_block_stop",
                "data: {\"type\":\"content_block_stop\",\"index\":0}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        ReflectionTestUtils.setField(provider, "webClient", sseClient(sse));

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6")
                .maxTokens(64)
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("greet").build()))
                .build();

        Flux<LlmChunk> flux = provider.streamChat(req, "sk-test", "https://api.anthropic.com");
        List<LlmChunk> chunks = flux.collectList().block();

        assertThat(chunks).isNotNull();
        // 5 deltas + 1 terminal
        assertThat(chunks).hasSize(6);
        assertThat(chunks.subList(0, 5))
                .extracting(LlmChunk::done).containsOnly(false);
        // seq is monotonic 0..5
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).seq()).isEqualTo((long) i);
        }

        LlmChunk terminal = chunks.get(5);
        assertThat(terminal.done()).isTrue();
        LlmChatResponse aggregate = terminal.aggregateResponse();
        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getContent()).hasSize(1);
        assertThat(aggregate.getContent().get(0).getType()).isEqualTo("text");
        assertThat(aggregate.getContent().get(0).getText()).isEqualTo("Hello, world! 🌍");
        assertThat(aggregate.getStopReason()).isEqualTo("end_turn");
        assertThat(aggregate.getInputTokens()).isEqualTo(12);
        assertThat(aggregate.getOutputTokens()).isEqualTo(7);
    }

    @Test
    void caseB_errorEventSignalsFluxErrorWithoutFallback() {
        // Strict variant: no chunks before error.
        String sse = String.join("\n",
                "event: error",
                "data: {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}",
                "");
        ReflectionTestUtils.setField(provider, "webClient", sseClient(sse));

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6").maxTokens(16)
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("hi").build()))
                .build();

        StepVerifier.create(provider.streamChat(req, "sk-test", "https://api.anthropic.com"))
                .expectErrorMatches(err -> err instanceof RuntimeException
                        && err.getMessage() != null
                        && err.getMessage().contains("slow down"))
                .verify();
    }

    @Test
    void caseC_zeroChunkMessageStopYieldsEmptyAggregate() {
        String sse = String.join("\n",
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_c\",\"usage\":{\"input_tokens\":4,\"output_tokens\":0}}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":0}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");
        ReflectionTestUtils.setField(provider, "webClient", sseClient(sse));

        LlmChatRequest req = LlmChatRequest.builder()
                .model("claude-sonnet-4-6").maxTokens(16)
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user").content("noop").build()))
                .build();

        List<LlmChunk> chunks = provider.streamChat(req, "sk-test", "https://api.anthropic.com")
                .collectList().block();

        assertThat(chunks).hasSize(1);
        LlmChunk only = chunks.get(0);
        assertThat(only.done()).isTrue();
        assertThat(only.aggregateResponse()).isNotNull();
        assertThat(only.aggregateResponse().getContent()).isEmpty();
        assertThat(only.aggregateResponse().getStopReason()).isEqualTo("end_turn");
        assertThat(only.aggregateResponse().getOutputTokens()).isZero();
    }

    /**
     * Reference helper kept for symmetry with the SSE wire shape. Currently
     * unused but documents the byte encoding expected by Spring's reactive
     * SSE codec for future reviewers.
     */
    @SuppressWarnings("unused")
    private static byte[] toBytes(String sse) {
        return sse.getBytes(StandardCharsets.UTF_8);
    }
}
