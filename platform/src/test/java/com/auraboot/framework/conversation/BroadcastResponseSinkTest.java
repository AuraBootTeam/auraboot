package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.conversation.turn.TurnRegistry;
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
import static org.mockito.Mockito.verify;

/**
 * G1 unit tests for {@link BroadcastResponseSink}. Tests the retained D.1 behaviors
 * (onToolStart TYPING_INDICATOR, onConfirmRequired, onResultContract, onToolResult no-op,
 * isClientConnected) with the updated 9-arg constructor.
 *
 * <p>The stream-lifecycle tests (onTextChunk buffering, onTurnBegin, onDone stream frames,
 * onError ai_turn_failed) live in {@link BroadcastResponseSinkStreamTest}.
 */
@DisplayName("BroadcastResponseSink — retained D.1 behaviors (tool / card frames)")
class BroadcastResponseSinkTest {

    private ImMessageBroadcaster broadcaster;
    private BroadcastResponseSink sink;

    private static final List<Long> TARGETS = List.of(100L, 101L);
    private static final Long CONV_ID = 9999L;

    @BeforeEach
    void setUp() {
        broadcaster = mock(ImMessageBroadcaster.class);
        TurnRegistry registry = new TurnRegistry();
        sink = new BroadcastResponseSink(broadcaster, TARGETS, CONV_ID,
                "turn-test", 55L, "aurabot", 100L, null, registry);
    }

    private WsFrame captureFirstFrame() {
        ArgumentCaptor<WsFrame> captor = ArgumentCaptor.forClass(WsFrame.class);
        verify(broadcaster, atLeastOnce()).publish(eq(TARGETS), captor.capture());
        return captor.getAllValues().get(0);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("4) onToolStart emits TYPING_INDICATOR with phase=tool:<name>")
    void onToolStart_emitsTypingWithPhase() {
        sink.onToolStart("t1", "search_records", Map.of("q", "x"));

        WsFrame f = captureFirstFrame();
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

        WsFrame f = captureFirstFrame();
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

        WsFrame f = captureFirstFrame();
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

        WsFrame f = captureFirstFrame();
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
    @DisplayName("9) isClientConnected returns true unconditionally")
    void isClientConnected_alwaysTrue() {
        assertThat(sink.isClientConnected()).isTrue();
    }
}
