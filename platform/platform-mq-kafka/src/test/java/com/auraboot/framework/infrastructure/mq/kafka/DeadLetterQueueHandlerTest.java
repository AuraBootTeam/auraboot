package com.auraboot.framework.infrastructure.mq.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeadLetterQueueHandler}.
 */
class DeadLetterQueueHandlerTest {

    @SuppressWarnings("unchecked")
    private final KafkaProducer<String, String> producer = mock(KafkaProducer.class);

    private DeadLetterQueueHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeadLetterQueueHandler(producer, ".DLT", 3, true);
    }

    @Test
    @DisplayName("currentRetryCount: returns 0 when header absent")
    void retryCountAbsent() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        assertEquals(0, DeadLetterQueueHandler.currentRetryCount(rec));
    }

    @Test
    @DisplayName("currentRetryCount: parses header value")
    void retryCountParsed() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        rec.headers().add(DeadLetterQueueHandler.RETRY_COUNT_HEADER,
                "2".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, DeadLetterQueueHandler.currentRetryCount(rec));
    }

    @Test
    @DisplayName("shouldRouteToDlt: true when attempts reach maxAttempts")
    void shouldRouteToDltAtThreshold() {
        assertFalse(handler.shouldRouteToDlt(1));
        assertFalse(handler.shouldRouteToDlt(2));
        assertTrue(handler.shouldRouteToDlt(3));
        assertTrue(handler.shouldRouteToDlt(4));
    }

    @Test
    @DisplayName("shouldRouteToDlt: false when DLQ disabled")
    void shouldRouteToDltDisabled() {
        DeadLetterQueueHandler disabled = new DeadLetterQueueHandler(producer, ".DLT", 3, false);
        assertFalse(disabled.shouldRouteToDlt(10));
    }

    @Test
    @DisplayName("routeToDeadLetter: publishes record with error metadata + DLT topic")
    void routesToDltWithMetadata() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("orders", 1, 42L, "k1", "payload");
        rec.headers().add("trace-id", "abc".getBytes(StandardCharsets.UTF_8));

        RuntimeException boom = new IllegalStateException("boom");
        handler.routeToDeadLetter(rec, boom, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture(), any());
        ProducerRecord<String, String> sent = captor.getValue();

        assertEquals("orders.DLT", sent.topic());
        assertEquals("k1", sent.key());
        assertEquals("payload", sent.value());

        assertEquals("orders", headerValue(sent, DeadLetterQueueHandler.ORIGINAL_TOPIC_HEADER));
        assertEquals("1", headerValue(sent, DeadLetterQueueHandler.ORIGINAL_PARTITION_HEADER));
        assertEquals("42", headerValue(sent, DeadLetterQueueHandler.ORIGINAL_OFFSET_HEADER));
        assertEquals("3", headerValue(sent, DeadLetterQueueHandler.RETRY_COUNT_HEADER));
        assertEquals(IllegalStateException.class.getName(),
                headerValue(sent, DeadLetterQueueHandler.ERROR_CLASS_HEADER));
        assertEquals("boom", headerValue(sent, DeadLetterQueueHandler.ERROR_MESSAGE_HEADER));
        assertEquals("abc", headerValue(sent, "trace-id"));
    }

    @Test
    @DisplayName("routeToDeadLetter: no-op when DLQ disabled")
    void routeDoesNothingWhenDisabled() {
        DeadLetterQueueHandler disabled = new DeadLetterQueueHandler(producer, ".DLT", 3, false);
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("t", 0, 0L, "k", "v");
        disabled.routeToDeadLetter(rec, new RuntimeException("x"), 3);
        verifyNoInteractions(producer);
    }

    private static String headerValue(ProducerRecord<String, String> r, String key) {
        Header h = r.headers().lastHeader(key);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
