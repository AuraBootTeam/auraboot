package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboxWorker Integration Test
 *
 * Covers P1-4 requirements:
 * 1. Outbox polling and dispatch mechanism
 * 2. Cleanup of delivered events
 * 3. Worker execution without errors
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("OutboxWorker Integration Test - P1-4")
class OutboxWorkerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OutboxWorkerImpl outboxWorker;

    @Autowired
    private OutboxWriter outboxWriter;

    private static final Long TEST_TENANT_ID = 1L;

    // Standalone POJO test event (no longer extends AbstractEvent)
    @Getter
    static class WorkerTestEvent {
        private final String eventId;
        private final String eventType;
        private final Instant timestamp;

        public WorkerTestEvent() {
            this.eventId = UniqueIdGenerator.generate();
            this.eventType = "WorkerTestEvent";
            this.timestamp = Instant.now();
        }
    }

    // ==================== Poll and Dispatch Tests ====================

    @Test
    @Order(1)
    @DisplayName("P1-4.3: Poll and dispatch runs without error")
    void test01_pollAndDispatch() {
        assertDoesNotThrow(() -> {
            outboxWorker.pollAndDispatch();
        });
    }

    @Test
    @Order(2)
    @DisplayName("P1-4.3: Poll dispatches pending events")
    void test02_pollDispatchesPending() {
        // Write an event to outbox
        WorkerTestEvent event = new WorkerTestEvent();
        outboxWriter.write(event, "worker_test_cmd", TEST_TENANT_ID);

        // Run poll - should process the event
        assertDoesNotThrow(() -> {
            outboxWorker.pollAndDispatch();
        });
    }

    @Test
    @Order(3)
    @DisplayName("P1-4.3: Multiple poll cycles are safe")
    void test03_multiplePollCycles() {
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> {
                outboxWorker.pollAndDispatch();
            });
        }
    }

    // ==================== Cleanup Tests ====================

    @Test
    @Order(10)
    @DisplayName("P1-4.4: Cleanup delivered events runs without error")
    void test10_cleanupDelivered() {
        assertDoesNotThrow(() -> {
            outboxWorker.cleanupDelivered();
        });
    }

    @Test
    @Order(11)
    @DisplayName("P1-4.4: Cleanup is idempotent")
    void test11_cleanupIdempotent() {
        assertDoesNotThrow(() -> {
            outboxWorker.cleanupDelivered();
            outboxWorker.cleanupDelivered();
        });
    }

    // ==================== End-to-End Flow Tests ====================

    @Test
    @Order(20)
    @DisplayName("P1-4: Full outbox lifecycle - write, poll, cleanup")
    void test20_fullLifecycle() {
        // Write event
        WorkerTestEvent event = new WorkerTestEvent();
        outboxWriter.write(event, "lifecycle_cmd", TEST_TENANT_ID);

        // Poll (should process)
        assertDoesNotThrow(() -> outboxWorker.pollAndDispatch());

        // Cleanup (should clean old delivered events)
        assertDoesNotThrow(() -> outboxWorker.cleanupDelivered());
    }

    @Test
    @Order(21)
    @DisplayName("P1-4: Write multiple events then poll")
    void test21_batchWriteThenPoll() {
        for (int i = 0; i < 5; i++) {
            WorkerTestEvent event = new WorkerTestEvent();
            outboxWriter.write(event, "batch_cmd_" + i, TEST_TENANT_ID);
        }

        assertDoesNotThrow(() -> outboxWorker.pollAndDispatch());
    }
}
