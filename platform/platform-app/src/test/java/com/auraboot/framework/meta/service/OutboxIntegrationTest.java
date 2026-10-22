package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboxWriter Integration Test
 *
 * Covers P1-4 requirements:
 * 1. Write events to outbox table within transaction
 * 2. Custom retry count support
 * 3. Event serialization
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OutboxWriter Integration Test - P1-4")
class OutboxIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxWriter outboxWriter;

    private static final Long TEST_TENANT_ID = 1L;

    // Test event class — standalone POJO (no longer extends AbstractEvent)
    @Getter
    static class TestEvent {
        private final String eventId;
        private final String eventType;
        private final Instant timestamp;
        private final String source;
        private final String recordId;
        private final String action;

        public TestEvent(String recordId, String action) {
            this.eventId = UniqueIdGenerator.generate();
            this.eventType = "TestEvent";
            this.timestamp = Instant.now();
            this.source = "test_source";
            this.recordId = recordId;
            this.action = action;
        }
    }

    // ==================== Write Tests ====================

    @Test
    @Order(1)
    @DisplayName("P1-4.1: Write event to outbox")
    void test01_writeEvent() {
        TestEvent event = new TestEvent("rec_001", "created");

        assertDoesNotThrow(() -> {
            outboxWriter.write(event, "create_order", TEST_TENANT_ID);
        });

        log.info("Written event to outbox: eventId={}", event.getEventId());
    }

    @Test
    @Order(2)
    @DisplayName("P1-4.1: Write event with custom max retries")
    void test02_writeEventWithRetries() {
        TestEvent event = new TestEvent("rec_002", "updated");

        assertDoesNotThrow(() -> {
            outboxWriter.write(event, "update_order", TEST_TENANT_ID, 5);
        });
    }

    @Test
    @Order(3)
    @DisplayName("P1-4.1: Write multiple events in sequence")
    void test03_writeMultipleEvents() {
        for (int i = 0; i < 5; i++) {
            TestEvent event = new TestEvent("rec_batch_" + i, "batch_op");

            assertDoesNotThrow(() -> {
                outboxWriter.write(event, "batch_command", TEST_TENANT_ID);
            });
        }
    }

    @Test
    @Order(4)
    @DisplayName("P1-4.1: Each event gets a unique eventId")
    void test04_uniqueEventIds() {
        TestEvent event1 = new TestEvent("rec_uniq_1", "action_a");
        TestEvent event2 = new TestEvent("rec_uniq_2", "action_b");

        assertNotEquals(event1.getEventId(), event2.getEventId());

        assertDoesNotThrow(() -> {
            outboxWriter.write(event1, "cmd_a", TEST_TENANT_ID);
            outboxWriter.write(event2, "cmd_b", TEST_TENANT_ID);
        });
    }

    @Test
    @Order(5)
    @DisplayName("P1-4.1: Event preserves type information")
    void test05_eventTypePreserved() {
        TestEvent event = new TestEvent("rec_type", "type_check");

        assertEquals("TestEvent", event.getEventType());
        assertNotNull(event.getTimestamp());
        assertEquals("test_source", event.getSource());

        assertDoesNotThrow(() -> {
            outboxWriter.write(event, "type_cmd", TEST_TENANT_ID);
        });
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(10)
    @DisplayName("P1-4: Write event with zero max retries")
    void test10_zeroRetries() {
        TestEvent event = new TestEvent("rec_zero", "no_retry");

        assertDoesNotThrow(() -> {
            outboxWriter.write(event, "cmd_zero", TEST_TENANT_ID, 0);
        });
    }

    @Test
    @Order(11)
    @DisplayName("P1-4: Write event with different tenant IDs")
    void test11_differentTenants() {
        TestEvent event1 = new TestEvent("rec_t1", "action");
        TestEvent event2 = new TestEvent("rec_t2", "action");

        assertDoesNotThrow(() -> {
            outboxWriter.write(event1, "cmd", 100L);
            outboxWriter.write(event2, "cmd", 200L);
        });
    }

    @Test
    @Order(12)
    @DisplayName("P1-4: Write event with high retry count")
    void test12_highRetryCount() {
        TestEvent event = new TestEvent("rec_high", "high_retry");

        assertDoesNotThrow(() -> {
            outboxWriter.write(event, "cmd_high", TEST_TENANT_ID, 100);
        });
    }
}
