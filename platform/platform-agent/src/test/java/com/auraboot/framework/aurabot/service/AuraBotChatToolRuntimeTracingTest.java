package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class AuraBotChatToolRuntimeTracingTest {
    private final AiTraceService traces = mock(AiTraceService.class);
    private final TraceContext trace = TraceContext.builder().traceId("actual-turn").build();
    private final SpanContext generation = SpanContext.builder().spanId("actual-generation").build();
    private final AuraBotChatToolRuntimeAdapter adapter = new AuraBotChatToolRuntimeAdapter(
            null, null, null, null, null, null, null, null, new ReadOnlyProfilePolicy(), 3, traces, trace);
    private final ChatTurnRuntime.ChatToolLoopRound round = new ChatTurnRuntime.ChatToolLoopRound(
            null, null, "session", "deepseek", "deepseek-chat", 1, null, "prompt", 100,
            List.of(), List.of(), List.of());
    private ChatTurnRuntime.ChatToolLoopCallbacks callbacks() {
        return ReflectionTestUtils.invokeMethod(adapter, "callbacks", null, null, "session", null,
                ExecutionEnvelope.readOnlyCatalog(), "source");
    }
    @Test void recordsActualModelUsageWithoutInventingCost() {
        when(traces.startSpan(eq(trace), isNull(), eq("generation"), eq("llm_round_1"), any())).thenReturn(generation);
        var callbacks = callbacks();
        Object span = callbacks.beforeProviderCall(round, LlmChatRequest.builder().model("deepseek-chat").build());
        assertThat(span).isSameAs(generation);
        callbacks.afterProviderResponse(round, span, LlmChatResponse.builder()
                .inputTokens(123).outputTokens(45).stopReason("end_turn").content(List.of()).build());
        verify(traces).recordGeneration(generation, "deepseek-chat", 123, 45, null, "end_turn", List.of(), List.of());
        verify(traces).endSpan(generation, Map.of("stopReason", "end_turn"), "success");
    }
    @Test void providerFailureClosesSpanAsErrorWithoutSuccessUsage() {
        callbacks().afterProviderFailure(round, generation, "provider unavailable", new IllegalStateException("down"));
        verify(traces).endSpan(generation, "provider unavailable", "error");
        verify(traces, never()).recordGeneration(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
