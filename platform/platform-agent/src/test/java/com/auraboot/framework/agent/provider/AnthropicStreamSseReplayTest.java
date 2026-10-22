package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 record-replay for the Anthropic <strong>streaming</strong> path (test-strategy doc
 * item ②, SSE follow-up). The WebClient SSE consumption ({@code bodyToFlux(ServerSentEvent)})
 * is framework code; the value is OUR per-event parser
 * {@link AnthropicLlmProvider#handleAnthropicSseEvent}. This test replays the exact
 * recorded Anthropic stream frames through it and asserts the {@link LlmChunk} output —
 * deterministically, no network, catching the "only fails on a real stream" class
 * (delta extraction, stop_reason/usage aggregation, error/ping handling).
 */
class AnthropicStreamSseReplayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicLlmProvider provider =
            new AnthropicLlmProvider(WebClient.builder().build(), objectMapper, new SimpleMeterRegistry());

    private Flux<LlmChunk> event(String type, String data, AtomicLong seq,
                                 AnthropicLlmProvider.StreamingAggregator agg) throws Exception {
        return provider.handleAnthropicSseEvent(type, data, seq, agg);
    }

    @Test
    void textDeltaFrame_emitsDeltaChunk() throws Exception {
        AtomicLong seq = new AtomicLong();
        var agg = new AnthropicLlmProvider.StreamingAggregator(new java.util.ArrayList<>());
        List<LlmChunk> chunks = event("content_block_delta",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                seq, agg).collectList().block();
        assertEquals(1, chunks.size());
        assertEquals("Hello", chunks.get(0).delta());
        assertFalse(chunks.get(0).done());
    }

    @Test
    void messageDeltaThenStop_aggregatesStopReasonIntoTerminalChunk() throws Exception {
        AtomicLong seq = new AtomicLong();
        var agg = new AnthropicLlmProvider.StreamingAggregator(new java.util.ArrayList<>());

        // message_delta carries stop_reason + output_tokens — no chunk, just aggregation.
        List<LlmChunk> none = event("message_delta",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":42}}",
                seq, agg).collectList().block();
        assertTrue(none.isEmpty(), "message_delta should not emit a chunk");

        // message_stop emits the terminal aggregated chunk.
        List<LlmChunk> end = event("message_stop", "{\"type\":\"message_stop\"}", seq, agg).collectList().block();
        assertEquals(1, end.size());
        LlmChunk done = end.get(0);
        assertTrue(done.done());
        assertEquals("tool_use", done.aggregateResponse().getStopReason(),
                "stop_reason from message_delta must reach the terminal aggregate");
    }

    @Test
    void errorFrame_propagatesAsError() {
        AtomicLong seq = new AtomicLong();
        var agg = new AnthropicLlmProvider.StreamingAggregator(new java.util.ArrayList<>());
        assertThrows(Exception.class, () ->
                event("error", "{\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}", seq, agg)
                        .collectList().block());
    }

    @Test
    void pingAndMessageStart_emitNoChunk() throws Exception {
        AtomicLong seq = new AtomicLong();
        var agg = new AnthropicLlmProvider.StreamingAggregator(new java.util.ArrayList<>());
        assertTrue(event("ping", "{}", seq, agg).collectList().block().isEmpty());
        assertTrue(event("message_start",
                "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}", seq, agg)
                .collectList().block().isEmpty());
    }
}
