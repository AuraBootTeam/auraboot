package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase D.1 unit tests for {@link BroadcastResponseSink}. Verifies the
 * sink-to-WebSocket-frame contract for IM-driven entry points (Q-D.2=α
 * "BroadcastResponseSink — buffer onTextChunk into onDone for one-shot IM
 * write + broadcast"):
 *
 * <ol>
 *     <li>onTextChunk buffers text + emits TYPING_INDICATOR(state=typing) frames</li>
 *     <li>onDone emits ONE MESSAGE frame with the full text + a stop typing frame</li>
 *     <li>onError emits ERROR frame; null message falls back to "Unknown error"</li>
 *     <li>onToolStart emits TYPING_INDICATOR with phase="tool:..." (no MESSAGE flood)</li>
 *     <li>onToolResult is a no-op at this transport (no MESSAGE frame)</li>
 *     <li>onConfirmRequired emits MESSAGE frame with cardType=confirm_required + pendingTurnId</li>
 *     <li>onResultContract emits MESSAGE frame with cardType=result_contract + contract object</li>
 *     <li>onTextChunk(null/"") is a no-op (no broadcast, no buffer growth)</li>
 *     <li>isClientConnected returns true unconditionally (fire-and-forget transport)</li>
 * </ol>
 */
@DisplayName("BroadcastResponseSink — IM WebSocket frame shape")
class BroadcastResponseSinkTest {

    private ImMessageBroadcaster broadcaster;
    private BroadcastResponseSink sink;

    private static final List<Long> TARGETS = List.of(100L, 101L);
    private static final Long CONV_ID = 9999L;

    @BeforeEach
    void setUp() {
        broadcaster = mock(ImMessageBroadcaster.class);
        sink = new BroadcastResponseSink(broadcaster, TARGETS, CONV_ID);
    }

    private WsFrame captureSingleFrame() {
        ArgumentCaptor<WsFrame> captor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, times(1)).publish(eq(TARGETS), captor.capture());
        return captor.getValue();
    }

    private List<WsFrame> captureAllFrames() {
        ArgumentCaptor<WsFrame> captor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(eq(TARGETS), captor.capture());
        return captor.getAllValues();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("1) onTextChunk buffers text and emits TYPING_INDICATOR(state=typing)")
    void onTextChunk_buffersAndTypes() {
        sink.onTextChunk("Hello, ");
        sink.onTextChunk("world!");

        assertThat(sink.bufferedText()).isEqualTo("Hello, world!");
        List<WsFrame> frames = captureAllFrames();
        assertThat(frames).hasSize(2);
        for (WsFrame f : frames) {
            assertThat(f.getType()).isEqualTo("TYPING_INDICATOR");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) f.getData();
            assertThat(data).containsEntry("conversationId", CONV_ID).containsEntry("state", "typing");
        }
    }

    @Test
    @DisplayName("2) onDone emits ONLY TYPING_INDICATOR(state=stopped); MESSAGE frame deferred to caller post-persist")
    void onDone_emitsOnlyStopTyping() {
        // Phase D.2 contract refinement: BroadcastResponseSink does NOT emit a
        // MESSAGE frame on onDone — that responsibility moved to the IM-event
        // caller (e.g. ImAiService) which broadcasts AFTER persistOutbound has
        // written the row, so the WS frame can carry {messageId, seq, ...}.
        sink.onDone("The full answer.", "trace-abc");

        WsFrame f = captureSingleFrame();
        assertThat(f.getType()).isEqualTo("TYPING_INDICATOR");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data)
                .containsEntry("conversationId", CONV_ID)
                .containsEntry("state", "stopped");
    }

    @Test
    @DisplayName("2b) onDone preserves bufferedText for diagnostics; only stop-typing emitted")
    void onDone_preservesBufferForDiagnostics() {
        sink.onTextChunk("buffered ");
        sink.onTextChunk("answer");
        assertThat(sink.bufferedText()).isEqualTo("buffered answer");

        sink.onDone(null, null);

        // 2 chunk-typing frames + 1 stop-typing frame = 3 total.
        List<WsFrame> frames = captureAllFrames();
        assertThat(frames).hasSize(3);
        assertThat(frames.get(2).getType()).isEqualTo("TYPING_INDICATOR");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) frames.get(2).getData();
        assertThat(data).containsEntry("state", "stopped");
        // The buffered text is still accessible for callers that need it as
        // a fallback (e.g. persistOutbound returned null and we want to log
        // what the LLM actually produced).
        assertThat(sink.bufferedText()).isEqualTo("buffered answer");
    }

    @Test
    @DisplayName("3) onError emits ERROR frame; null message becomes 'Unknown error'")
    void onError_emitsErrorFrame() {
        sink.onError(null, "trace-x");

        WsFrame f = captureSingleFrame();
        assertThat(f.getType()).isEqualTo("ERROR");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data)
                .containsEntry("conversationId", CONV_ID)
                .containsEntry("error", "Unknown error")
                .containsEntry("traceId", "trace-x");
    }

    @Test
    @DisplayName("4) onToolStart emits TYPING_INDICATOR with phase=tool:<name>")
    void onToolStart_emitsTypingWithPhase() {
        sink.onToolStart("t1", "search_records", Map.of("q", "x"));

        WsFrame f = captureSingleFrame();
        assertThat(f.getType()).isEqualTo("TYPING_INDICATOR");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data)
                .containsEntry("state", "typing")
                .containsEntry("phase", "tool:search_records");
    }

    @Test
    @DisplayName("5) onToolResult is a no-op (no broadcast frame)")
    void onToolResult_isNoOp() {
        sink.onToolResult("t1", Map.of("rows", 3), true);

        verify(broadcaster, never()).publish(any(), any());
    }

    @Test
    @DisplayName("6) onConfirmRequired emits MESSAGE card with cardType=confirm_required + pendingTurnId")
    void onConfirmRequired_emitsCard() {
        sink.onConfirmRequired("t-9", "delete_record", "Confirm deletion?",
                Map.of("id", 42), "APPROVAL_PID_123");

        WsFrame f = captureSingleFrame();
        assertThat(f.getType()).isEqualTo("MESSAGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data)
                .containsEntry("messageType", "card")
                .containsEntry("cardType", "confirm_required")
                .containsEntry("toolId", "t-9")
                .containsEntry("toolName", "delete_record")
                .containsEntry("description", "Confirm deletion?")
                .containsEntry("pendingTurnId", "APPROVAL_PID_123");
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) data.get("input");
        assertThat(input).containsEntry("id", 42);
    }

    @Test
    @DisplayName("6b) onConfirmRequired with null pendingTurnId omits the field; null description -> empty string")
    void onConfirmRequired_nullsHandled() {
        sink.onConfirmRequired("t-1", "x", null, Map.of(), null);

        WsFrame f = captureSingleFrame();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data).containsEntry("description", "");
        assertThat(data).doesNotContainKey("pendingTurnId");
    }

    @Test
    @DisplayName("7) onResultContract emits MESSAGE card with cardType=result_contract + contract object")
    void onResultContract_emitsCard() {
        ResultContract rc = ResultContract.builder()
                .skillCode("crm.lookup")
                .status("success")
                .renderHint("table")
                .outputType("structured_result")
                .durationMs(120L)
                .build();
        sink.onResultContract(rc);

        WsFrame f = captureSingleFrame();
        assertThat(f.getType()).isEqualTo("MESSAGE");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) f.getData();
        assertThat(data)
                .containsEntry("messageType", "card")
                .containsEntry("cardType", "result_contract")
                .containsEntry("contract", rc);
    }

    @Test
    @DisplayName("7b) onResultContract(null) is a no-op")
    void onResultContract_nullIsNoOp() {
        sink.onResultContract(null);
        verify(broadcaster, never()).publish(any(), any());
    }

    @Test
    @DisplayName("8) onTextChunk(null) and onTextChunk(\"\") are silent no-ops")
    void onTextChunk_nullOrEmpty_noOp() {
        sink.onTextChunk(null);
        sink.onTextChunk("");

        assertThat(sink.bufferedText()).isEmpty();
        verify(broadcaster, never()).publish(any(), any());
    }

    @Test
    @DisplayName("9) isClientConnected returns true unconditionally")
    void isClientConnected_alwaysTrue() {
        assertThat(sink.isClientConnected()).isTrue();
    }
}
