package com.auraboot.framework.conversation;

import com.auraboot.framework.application.TestApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.2 integration test for {@link SpringEventEmitter}. Verifies that
 * {@link TurnSideEffects.EventEmitter#emit} actually delivers
 * {@link TurnCompletedEvent} and {@link TurnSuspendedEvent} to registered
 * Spring {@code @EventListener} beans — i.e. the chokepoint event surface
 * is publish-real, not the Phase A NOOP stub.
 */
@Slf4j
@SpringBootTest(classes = {TestApplication.class, SpringEventEmitterTest.TestEventCapture.class})
@ActiveProfiles("integration-test")
@DisplayName("SpringEventEmitter — Spring ApplicationEventPublisher integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpringEventEmitterTest {

    @Autowired
    private TurnSideEffects.EventEmitter eventEmitter;

    @Autowired
    private TestEventCapture capture;

    @BeforeEach
    void resetCapture() {
        capture.clear();
    }

    @AfterEach
    void verifyNoLeakBetweenTests() {
        capture.clear();
    }

    private TurnContext newCtx() {
        return new TurnContext(
                "test-turn-" + System.nanoTime(),
                1L, 2L, 3L,
                null,                                // agentId
                null,                                // agentCode (DC.3c)
                null,                                // channelSessionId
                10L,                                 // conversationId
                null, null, null,
                null,                                // taskPid (DC.3c)
                Instant.now());
    }

    @Test
    @DisplayName("emit(TurnCompletedEvent) -> Spring delivers to @EventListener")
    void emitsTurnCompletedToListeners() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success("ok", Map.of());
        TurnCompletedEvent event = new TurnCompletedEvent(ctx, success);

        eventEmitter.emit(event);

        assertThat(capture.completed).hasSize(1);
        assertThat(capture.completed.get(0)).isSameAs(event);
        assertThat(capture.suspended).isEmpty();
    }

    @Test
    @DisplayName("emit(TurnSuspendedEvent) -> Spring delivers to @EventListener")
    void emitsTurnSuspendedToListeners() {
        TurnContext ctx = newCtx();
        TurnOutcome.PendingConfirmation pc =
                new TurnOutcome.PendingConfirmation("session-1", "partial", "tool-1");
        TurnSuspendedEvent event = new TurnSuspendedEvent(ctx, pc);

        eventEmitter.emit(event);

        assertThat(capture.suspended).hasSize(1);
        assertThat(capture.suspended.get(0)).isSameAs(event);
        assertThat(capture.completed).isEmpty();
    }

    @Test
    @DisplayName("emit(null) -> silently ignored, no listener fired")
    void nullEvent_silentlyIgnored() {
        eventEmitter.emit(null);

        assertThat(capture.completed).isEmpty();
        assertThat(capture.suspended).isEmpty();
    }

    /** Test-only Spring component that captures both event types into lists for assertion. */
    @Component
    static class TestEventCapture {
        final List<TurnCompletedEvent> completed = new CopyOnWriteArrayList<>();
        final List<TurnSuspendedEvent> suspended = new CopyOnWriteArrayList<>();

        @EventListener
        public void onCompleted(TurnCompletedEvent event) {
            completed.add(event);
        }

        @EventListener
        public void onSuspended(TurnSuspendedEvent event) {
            suspended.add(event);
        }

        void clear() {
            completed.clear();
            suspended.clear();
        }
    }
}
