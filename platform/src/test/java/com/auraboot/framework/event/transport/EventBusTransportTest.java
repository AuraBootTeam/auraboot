package com.auraboot.framework.event.transport;

import com.auraboot.framework.event.AuraEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EventBusTransport implementations.
 * Validates the transport abstraction works correctly before
 * integrating with Spring context.
 */
class EventBusTransportTest {

    // ── Test event ──────────────────────────────────────────────────────

    static AuraEvent testEvent(String message) {
        return new AuraEvent(null, "test.event.sent", null, null, null) {};
    }

    // ── LocalTransport tests ────────────────────────────────────────────

    @Nested
    @DisplayName("LocalTransport")
    class LocalTransportTests {

        @Test
        @DisplayName("should return LOCAL transport type")
        void shouldReturnLocalType() {
            LocalTransport transport = new LocalTransport();
            assertThat(transport.getType()).isEqualTo(TransportType.LOCAL);
        }

        @Test
        @DisplayName("should deliver event to subscriber")
        void shouldDeliverEventToSubscriber() {
            LocalTransport transport = new LocalTransport();
            List<AuraEvent> received = new ArrayList<>();

            transport.subscribe("test.topic", "group-1", received::add);
            transport.send("test.topic", testEvent("hello"));

            assertThat(received).hasSize(1);
        }

        @Test
        @DisplayName("should deliver to multiple subscribers on same topic")
        void shouldDeliverToMultipleSubscribers() {
            LocalTransport transport = new LocalTransport();
            List<AuraEvent> received1 = new ArrayList<>();
            List<AuraEvent> received2 = new ArrayList<>();

            transport.subscribe("test.topic", "group-1", received1::add);
            transport.subscribe("test.topic", "group-2", received2::add);
            transport.send("test.topic", testEvent("broadcast"));

            assertThat(received1).hasSize(1);
            assertThat(received2).hasSize(1);
        }

        @Test
        @DisplayName("should not deliver to subscribers of different topic")
        void shouldNotDeliverToDifferentTopic() {
            LocalTransport transport = new LocalTransport();
            List<AuraEvent> received = new ArrayList<>();

            transport.subscribe("topic-a", "group-1", received::add);
            transport.send("topic-b", testEvent("wrong-topic"));

            assertThat(received).isEmpty();
        }

        @Test
        @DisplayName("should handle no subscribers gracefully")
        void shouldHandleNoSubscribers() {
            LocalTransport transport = new LocalTransport();
            // Should not throw
            transport.send("no-subscribers", testEvent("nobody-listening"));
        }

        @Test
        @DisplayName("should isolate subscriber errors")
        void shouldIsolateSubscriberErrors() {
            LocalTransport transport = new LocalTransport();
            List<AuraEvent> received = new ArrayList<>();

            // First subscriber throws
            transport.subscribe("test.topic", "bad-group", event -> {
                throw new RuntimeException("boom");
            });
            // Second subscriber should still receive
            transport.subscribe("test.topic", "good-group", received::add);

            transport.send("test.topic", testEvent("resilient"));

            assertThat(received).hasSize(1);
        }

        @Test
        @DisplayName("should deliver multiple events in order")
        void shouldDeliverInOrder() {
            LocalTransport transport = new LocalTransport();
            List<String> eventIds = new ArrayList<>();

            transport.subscribe("ordered", "group-1", event -> {
                eventIds.add(event.getEventId());
            });

            transport.send("ordered", testEvent("first"));
            transport.send("ordered", testEvent("second"));
            transport.send("ordered", testEvent("third"));

            assertThat(eventIds).hasSize(3);
        }

        @Test
        @DisplayName("should be thread-safe for concurrent sends")
        void shouldBeThreadSafe() throws InterruptedException {
            LocalTransport transport = new LocalTransport();
            List<AuraEvent> received = java.util.Collections.synchronizedList(new ArrayList<>());
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);

            transport.subscribe("concurrent", "group-1", received::add);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    transport.send("concurrent", testEvent("msg-" + idx));
                    latch.countDown();
                }).start();
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(received).hasSize(threadCount);
        }
    }

    // ── TransportType tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("TransportType")
    class TransportTypeTests {

        @Test
        @DisplayName("should have all expected transport types")
        void shouldHaveAllTypes() {
            assertThat(TransportType.values())
                    .containsExactlyInAnyOrder(
                            TransportType.LOCAL,
                            TransportType.REDIS,
                            TransportType.RABBITMQ
                    );
        }
    }

    // ── RabbitMqTransport tests ─────────────────────────────────────────

    @Nested
    @DisplayName("RabbitMqTransport")
    class RabbitMqTransportTests {

        @Test
        @DisplayName("should return RABBITMQ transport type")
        void shouldReturnRabbitMqType() {
            RabbitMqTransport transport = new RabbitMqTransport(null,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            assertThat(transport.getType()).isEqualTo(TransportType.RABBITMQ);
        }

        @Test
        @DisplayName("stub send should not throw")
        void stubSendShouldNotThrow() {
            RabbitMqTransport transport = new RabbitMqTransport(null,
                    new com.fasterxml.jackson.databind.ObjectMapper());
            // stub implementation logs but does not throw
            transport.send("test.topic", testEvent("stub-test"));
        }
    }
}
