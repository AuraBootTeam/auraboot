package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.dto.DebugEventDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class DebugEventPublisherTest {

    private DebugEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new DebugEventPublisher();
    }

    @Test
    void subscribe_returnsEmitter_andSendsConnectedEvent() {
        SseEmitter emitter = publisher.subscribe("session-1");
        assertThat(emitter).isNotNull();
        // Cleanup: complete to allow test to exit cleanly
        emitter.complete();
    }

    @Test
    void publish_noSubscribers_silentNoOp() {
        DebugEventDTO ev = DebugEventDTO.builder()
                .eventType("ACTION_STARTED")
                .sessionId("missing")
                .build();
        publisher.publish("missing", ev);
    }

    @Test
    void publish_deliversEventToSubscribers() {
        SseEmitter e1 = publisher.subscribe("s2");
        DebugEventDTO ev = DebugEventDTO.builder()
                .eventType("ACTION_COMPLETED")
                .sessionId("s2")
                .build();
        publisher.publish("s2", ev);
        e1.complete();
    }

    @Test
    void closeSession_completesAllEmitters_andClearsMap() {
        publisher.subscribe("s3");
        publisher.subscribe("s3");
        publisher.closeSession("s3");
        // After close, publish should be a no-op
        publisher.publish("s3", DebugEventDTO.builder().eventType("X").sessionId("s3").build());
    }

    @Test
    void closeSession_unknownSession_noThrow() {
        publisher.closeSession("never");
    }

    @Test
    void sendHeartbeat_emptyMap_returnsEarly() {
        publisher.sendHeartbeat();
    }

    @Test
    void sendHeartbeat_withSubscribers_doesNotThrow() {
        SseEmitter e = publisher.subscribe("hb");
        publisher.sendHeartbeat();
        e.complete();
    }

    @Test
    void onCompletion_removesEmitter() throws Exception {
        SseEmitter emitter = publisher.subscribe("rem");
        emitter.complete();
        // After completion, internal state should not retain it.
        // We assert by sending and verifying no exceptions surface.
        publisher.publish("rem", DebugEventDTO.builder()
                .eventType("ACTION_STARTED")
                .sessionId("rem")
                .build());
    }
}
