package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnOutcomeMeta;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatTurnCompletionReliabilityTest {
    private final ChatTurnRuntime runtime = new ChatTurnRuntime();

    @Test
    void tokenLimitedResponsePreservesPartialTextAndBothReliabilityFlags() {
        LlmChatResponse response = response("Partial answer");
        response.setStopReason("max_tokens");
        ResponseSink sink = mock(ResponseSink.class);
        TurnOutcome.Success outcome = (TurnOutcome.Success) runtime.completeFinalResponse(
                response, new ArrayList<>(), null, sink, "limited", true);
        assertThat(outcome.finalResponse()).isEqualTo("Partial answer");
        assertThat(outcome.meta()).containsEntry(TurnOutcomeMeta.RESPONSE_TRUNCATED, true)
                .containsEntry(TurnOutcomeMeta.TOOL_FAILURE, true);
        verify(sink).onDone("Partial answer", "limited");
    }

    @Test
    void tokenLimitedStreamPreservesPartialTextAndMarksIncompleteResponse() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatRequest request = LlmChatRequest.builder().model("test-model").build();
        LlmChatResponse aggregate = response("Partial answer");
        aggregate.setStopReason("max_tokens");
        when(provider.streamChat(request, "key", "https://llm.test")).thenReturn(Flux.just(
                LlmChunk.delta(0, "Partial answer"), LlmChunk.done(1, aggregate)));
        ResponseSink sink = mock(ResponseSink.class);
        TurnOutcome.Success outcome = (TurnOutcome.Success) runtime.streamProviderResponse(
                provider, request, "key", "https://llm.test", sink, "limited");
        assertThat(outcome.finalResponse()).isEqualTo("Partial answer");
        assertThat(outcome.meta()).containsEntry(TurnOutcomeMeta.RESPONSE_TRUNCATED, true);
        verify(sink).onDone("Partial answer", "limited");
    }

    private static LlmChatResponse response(String text) {
        return LlmChatResponse.builder().content(List.of(
                LlmChatResponse.ContentBlock.builder().type("text").text(text).build())).build();
    }
}
