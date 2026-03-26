package com.auraboot.framework.infrastructure.mq.kafka;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KafkaMqProvider}.
 * <p>
 * Uses the package-private constructor to inject a mock producer,
 * avoiding the need for a live Kafka broker.
 */
class KafkaMqProviderTest {

    private KafkaProducer<String, String> mockProducer;
    private KafkaMqProvider provider;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockProducer = mock(KafkaProducer.class);
        provider = new KafkaMqProvider(mockProducer, "localhost:9092", "test-group");
    }

    @AfterEach
    void tearDown() {
        try {
            provider.destroy();
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
    }

    @Test
    @DisplayName("send() should dispatch a ProducerRecord to the Kafka producer")
    void sendDispatchesProducerRecord() {
        // Arrange
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("orders", 0), 0, 0, 0L, 0, 0);
        when(mockProducer.send(any(), any())).thenReturn(CompletableFuture.completedFuture(metadata));

        // Act
        provider.send("orders", "{\"id\":1}", Map.of());

        // Assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());

        ProducerRecord<String, String> captured = captor.getValue();
        assertEquals("orders", captured.topic());
        assertEquals("{\"id\":1}", captured.value());
    }

    @Test
    @DisplayName("send() should serialize headers as UTF-8 bytes on the ProducerRecord")
    void sendSerializesHeadersAsUtf8() {
        // Arrange
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("events", 0), 0, 0, 0L, 0, 0);
        when(mockProducer.send(any(), any())).thenReturn(CompletableFuture.completedFuture(metadata));

        Map<String, String> headers = new HashMap<>();
        headers.put("trace-id", "abc-123");
        headers.put("source", "test-service");

        // Act
        provider.send("events", "payload", headers);

        // Assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());

        ProducerRecord<String, String> captured = captor.getValue();
        Map<String, String> actualHeaders = new HashMap<>();
        for (Header header : captured.headers()) {
            actualHeaders.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }

        assertEquals("abc-123", actualHeaders.get("trace-id"));
        assertEquals("test-service", actualHeaders.get("source"));
    }

    @Test
    @DisplayName("send() with null headers should not add any headers to the record")
    void sendWithNullHeadersAddsNoHeaders() {
        // Arrange
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("topic", 0), 0, 0, 0L, 0, 0);
        when(mockProducer.send(any(), any())).thenReturn(CompletableFuture.completedFuture(metadata));

        // Act
        provider.send("topic", "body", null);

        // Assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture(), any());

        int headerCount = 0;
        for (Header ignored : captor.getValue().headers()) {
            headerCount++;
        }
        assertEquals(0, headerCount);
    }

    @Test
    @DisplayName("subscribe() should register a subscription and track it internally")
    void subscribeRegistersSubscription() {
        // Act — subscribe starts a daemon thread; we verify tracking
        provider.subscribe("orders", "group-a", (topic, body, headers) -> {});

        // Assert
        assertTrue(provider.hasSubscription("orders", "group-a"));
        assertEquals(1, provider.activeSubscriptionCount());
    }

    @Test
    @DisplayName("unsubscribe() should remove the subscription and set running flag to false")
    void unsubscribeRemovesSubscription() {
        // Arrange — subscribe first
        provider.subscribe("orders", "group-a", (topic, body, headers) -> {});
        assertTrue(provider.hasSubscription("orders", "group-a"));

        // Act
        provider.unsubscribe("orders", "group-a");

        // Assert
        assertFalse(provider.hasSubscription("orders", "group-a"));
        assertEquals(0, provider.activeSubscriptionCount());
    }

    @Test
    @DisplayName("destroy() should close producer and clear all subscriptions")
    void destroyCleansUpAllResources() {
        // Arrange — add subscriptions
        provider.subscribe("topic-1", "group-1", (t, b, h) -> {});
        provider.subscribe("topic-2", "group-2", (t, b, h) -> {});
        assertEquals(2, provider.activeSubscriptionCount());

        // Act
        provider.destroy();

        // Assert
        assertEquals(0, provider.activeSubscriptionCount());
        verify(mockProducer).close(any());
    }
}
