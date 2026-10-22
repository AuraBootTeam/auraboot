package com.auraboot.framework.conversation;

import com.auraboot.framework.conversation.turn.TurnRegistry;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BroadcastResponseSinkStreamTest {

    @Mock ImMessageBroadcaster broadcaster;
    TurnRegistry registry;
    BroadcastResponseSink sink;

    @BeforeEach
    void setup() {
        registry = new TurnRegistry();
        sink = new BroadcastResponseSink(broadcaster, List.of(11L, 22L), 88L,
                "turn-1", 100L, "Aurabot", 11L, 42L, registry);
    }

    @Test
    void onTurnBeginRegistersAndBroadcasts() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        assertThat(registry.get("turn-1")).isPresent();
        ArgumentCaptor<WsFrame> cap = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster).publish(org.mockito.ArgumentMatchers.eq(List.of(11L, 22L)), cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(ImConstants.WS_AI_TURN_STARTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) cap.getValue().getData();
        assertThat(data.get("turnId")).isEqualTo("turn-1");
        assertThat(data.get("agentId")).isEqualTo(100L);
        assertThat(data.get("initiatorUserId")).isEqualTo(11L);
    }

    @Test
    void onTextChunkBuffersUntilFirstFlushBoundary() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        sink.onTextChunk("Hello world this is a test!"); // 27 chars < 30 threshold, immediate < 50ms
        verify(broadcaster, never()).publish(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(f -> ImConstants.WS_STREAM_CHUNK.equals(f.getType())));
    }

    @Test
    void onTextChunkFlushesOn30CharBoundary() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        sink.onTextChunk("0123456789012345678901234567890"); // 31 chars > 30
        ArgumentCaptor<WsFrame> cap = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(org.mockito.ArgumentMatchers.any(), cap.capture());
        boolean hasChunk = cap.getAllValues().stream()
                .anyMatch(f -> ImConstants.WS_STREAM_CHUNK.equals(f.getType()));
        assertThat(hasChunk).isTrue();
    }

    @Test
    void onTextChunkRespectsCancellationFlag() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        registry.markCancelled("turn-1");
        assertThatThrownBy(() -> sink.onTextChunk("test"))
                .isInstanceOf(TurnCancelledException.class);
    }

    @Test
    void onDoneFlushesResidualAndBroadcastsCompleted() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        sink.onTextChunk("short"); // below 30-char threshold, stays in buffer
        sink.onDone("short", "trace-1");
        ArgumentCaptor<WsFrame> cap = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(org.mockito.ArgumentMatchers.any(), cap.capture());
        boolean hasChunk = cap.getAllValues().stream()
                .anyMatch(f -> ImConstants.WS_STREAM_CHUNK.equals(f.getType()));
        boolean hasStreamEnd = cap.getAllValues().stream()
                .anyMatch(f -> ImConstants.WS_STREAM_END.equals(f.getType()));
        boolean hasCompleted = cap.getAllValues().stream()
                .anyMatch(f -> ImConstants.WS_AI_TURN_COMPLETED.equals(f.getType()));
        assertThat(hasChunk).isTrue();
        assertThat(hasStreamEnd).isTrue();
        assertThat(hasCompleted).isTrue();
        assertThat(registry.get("turn-1").orElseThrow().getStatus())
                .isEqualTo(com.auraboot.framework.conversation.turn.TurnStatus.COMPLETED);
    }

    @Test
    void onErrorBroadcastsTurnFailedWithClassifiedCode() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        sink.onError("HTTP 429 rate limit", "trace-1");
        ArgumentCaptor<WsFrame> cap = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(org.mockito.ArgumentMatchers.any(), cap.capture());
        WsFrame failedFrame = cap.getAllValues().stream()
                .filter(f -> ImConstants.WS_AI_TURN_FAILED.equals(f.getType()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) failedFrame.getData();
        assertThat(data.get("errorCode")).isEqualTo(ImConstants.AI_ERR_RATE_LIMITED);
        assertThat(data.get("turnId")).isEqualTo("turn-1");
        assertThat(registry.get("turn-1").orElseThrow().getStatus())
                .isEqualTo(com.auraboot.framework.conversation.turn.TurnStatus.FAILED);
    }

    @Test
    void onTurnCancelledIsDefaultNoopInG1() {
        sink.onTurnBegin("turn-1", 100L, 88L, 42L, 11L);
        org.mockito.Mockito.clearInvocations(broadcaster);
        sink.onTurnCancelled("turn-1");
        // G1 design: Controller broadcasts ai_turn_cancelled directly; sink does NOT also broadcast (avoid double).
        verify(broadcaster, never()).publish(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(f -> ImConstants.WS_AI_TURN_CANCELLED.equals(f.getType())));
    }
}
