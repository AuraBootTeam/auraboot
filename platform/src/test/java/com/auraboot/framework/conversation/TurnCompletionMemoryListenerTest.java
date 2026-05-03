package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.triage.TriageBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase C.2 unit tests for {@link TurnCompletionMemoryListener}. Asserts the
 * filtering rules that gate L1 memory writeback so trivial-chat noise doesn't
 * pollute the memory pool while genuine user-impacting turns get persisted.
 */
@DisplayName("TurnCompletionMemoryListener — Phase C.2 L1 memory writeback gates")
class TurnCompletionMemoryListenerTest {

    private AgentMemoryService memoryService;
    private TurnCompletionMemoryListener listener;

    @BeforeEach
    void setUp() {
        memoryService = mock(AgentMemoryService.class);
        listener = new TurnCompletionMemoryListener(memoryService);
    }

    private TurnContext newCtx(TriageBucket bucket, Long userId, Long tenantId) {
        return new TurnContext(
                "01HW3KTEST",
                tenantId == null ? 0L : tenantId,
                userId == null ? 0L : userId,
                200L,
                null,                                // agentId
                null,                                // agentCode (DC.3c)
                null,                                // channelSessionId
                999L,                                // conversationId
                null,
                bucket,
                null,
                null,                                // taskPid (DC.3c)
                Instant.now());
    }

    private TurnCompletedEvent successEvent(TriageBucket bucket, String finalResponse) {
        return new TurnCompletedEvent(
                newCtx(bucket, 100L, 42L),
                new TurnOutcome.Success(finalResponse, Map.of()));
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("ACP_RUN + non-blank response -> createScopedMemory called with importance=5")
    void acpRunSuccess_writesMemory() {
        TurnCompletedEvent ev = successEvent(TriageBucket.ACP_RUN,
                "Created CRM lead for TestCo with default owner.");

        listener.onTurnCompleted(ev);

        verify(memoryService, times(1)).createScopedMemory(
                eq(42L),                          // tenantId
                eq("aurabot"),                    // agentCode
                eq("turn_summary"),               // memoryType
                eq("conversation_turn"),          // category
                argThat(title -> title.startsWith("Created CRM lead")),
                argThat(content -> content.contains("Created CRM lead for TestCo")),
                eq(5),                            // importance
                eq(false),                        // shareable
                eq("user"),
                eq("100"));
    }

    @Test
    @DisplayName("CONTEXTUAL_ANSWER + non-blank response -> importance=3")
    void contextualSuccess_writesMemoryWithImportance3() {
        listener.onTurnCompleted(successEvent(TriageBucket.CONTEXTUAL_ANSWER,
                "This page shows the customer's outstanding orders."));

        verify(memoryService, times(1)).createScopedMemory(
                anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(),
                eq(3),                            // importance
                anyBoolean(), anyString(), anyString());
    }

    @Test
    @DisplayName("LIGHT_CHAT -> skipped silently")
    void lightChat_skipsMemoryWrite() {
        listener.onTurnCompleted(successEvent(TriageBucket.LIGHT_CHAT, "Hi! How can I help?"));

        verifyNoInteractions(memoryService);
    }

    @Test
    @DisplayName("blank finalResponse -> skipped")
    void blankResponse_skipsMemoryWrite() {
        listener.onTurnCompleted(successEvent(TriageBucket.ACP_RUN, ""));
        listener.onTurnCompleted(successEvent(TriageBucket.ACP_RUN, "   "));

        verifyNoInteractions(memoryService);
    }

    @Test
    @DisplayName("Failed outcome -> skipped (no reliable memory content)")
    void failedOutcome_skipsMemoryWrite() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, 100L, 42L),
                new TurnOutcome.Failed("LLM timeout", null));

        listener.onTurnCompleted(ev);

        verifyNoInteractions(memoryService);
    }

    @Test
    @DisplayName("Interrupted outcome -> skipped")
    void interruptedOutcome_skipsMemoryWrite() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, 100L, 42L),
                new TurnOutcome.Interrupted("partial", "client_disconnect"));

        listener.onTurnCompleted(ev);

        verifyNoInteractions(memoryService);
    }

    @Test
    @DisplayName("null userId -> skipped (system/cron caller)")
    void nullUserId_skipsMemoryWrite() {
        TurnCompletedEvent ev = new TurnCompletedEvent(
                newCtx(TriageBucket.ACP_RUN, null, 42L),
                new TurnOutcome.Success("anything", Map.of()));

        listener.onTurnCompleted(ev);

        verifyNoInteractions(memoryService);
    }

    @Test
    @DisplayName("null triage bucket (pre-C.1 path) -> writes with importance=2 fallback")
    void nullBucket_writesWithFallbackImportance() {
        listener.onTurnCompleted(successEvent(null, "Generic answer"));

        verify(memoryService, times(1)).createScopedMemory(
                anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(),
                eq(2),                            // fallback importance
                anyBoolean(), anyString(), anyString());
    }

    @Test
    @DisplayName("memoryService throws -> exception swallowed; no rethrow")
    void memoryServiceThrows_exceptionSwallowed() {
        doThrow(new RuntimeException("DB down"))
                .when(memoryService)
                .createScopedMemory(anyLong(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyInt(), anyBoolean(),
                        anyString(), anyString());

        // Should NOT throw — the listener catches all exceptions defensively.
        listener.onTurnCompleted(successEvent(TriageBucket.ACP_RUN, "anything"));

        verify(memoryService, times(1)).createScopedMemory(
                anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyBoolean(),
                anyString(), anyString());
    }

    @Test
    @DisplayName("long finalResponse -> content truncated to 800 chars + ellipsis")
    void longResponse_truncated() {
        String longText = "X".repeat(2000);
        listener.onTurnCompleted(successEvent(TriageBucket.ACP_RUN, longText));

        verify(memoryService, times(1)).createScopedMemory(
                anyLong(), anyString(), anyString(), anyString(),
                anyString(),
                argThat(content -> content.length() <= 803 && content.endsWith("...")),
                anyInt(), anyBoolean(), anyString(), anyString());
    }
}
