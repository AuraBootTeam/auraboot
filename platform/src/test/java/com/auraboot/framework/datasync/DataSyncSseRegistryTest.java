package com.auraboot.framework.datasync;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataSyncSseRegistryTest {

    @Test
    void registerReturnsUniqueConnectionId() {
        var registry = new DataSyncSseRegistry();
        Long id1 = registry.registerEmitter(1L, 100L, new SseEmitter());
        Long id2 = registry.registerEmitter(1L, 100L, new SseEmitter());
        assertNotEquals(id1, id2);
    }

    @Test
    void subscribePushReachesSubscriber() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter = new AtomicInteger(0);
        var emitter = new TestSseEmitter(counter);
        Long connId = registry.registerEmitter(1L, 100L, emitter);
        registry.subscribe(connId, Set.of("crm_opportunity"));

        registry.pushToSubscribers(new DataSyncMessage(100L, "crm_opportunity", "update", "r1", 2L));
        assertEquals(1, counter.get());
    }

    @Test
    void pushDoesNotReachUnsubscribedModel() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter = new AtomicInteger(0);
        var emitter = new TestSseEmitter(counter);
        Long connId = registry.registerEmitter(1L, 100L, emitter);
        registry.subscribe(connId, Set.of("crm_opportunity"));

        registry.pushToSubscribers(new DataSyncMessage(100L, "inventory_item", "update", "r1", 2L));
        assertEquals(0, counter.get());
    }

    @Test
    void tenantIsolation_differentTenantNotPushed() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter = new AtomicInteger(0);
        var emitter = new TestSseEmitter(counter);
        Long connId = registry.registerEmitter(1L, 100L, emitter);
        registry.subscribe(connId, Set.of("crm_opportunity"));

        registry.pushToSubscribers(new DataSyncMessage(200L, "crm_opportunity", "update", "r1", 2L));
        assertEquals(0, counter.get());
    }

    @Test
    void multipleConnections_independentSubscriptions() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter1 = new AtomicInteger(0);
        var counter2 = new AtomicInteger(0);
        Long conn1 = registry.registerEmitter(1L, 100L, new TestSseEmitter(counter1));
        Long conn2 = registry.registerEmitter(1L, 100L, new TestSseEmitter(counter2));
        registry.subscribe(conn1, Set.of("crm_opportunity"));
        registry.subscribe(conn2, Set.of("inventory_item"));

        registry.pushToSubscribers(new DataSyncMessage(100L, "crm_opportunity", "create", "r1", 2L));
        assertEquals(1, counter1.get());
        assertEquals(0, counter2.get());
    }

    @Test
    void resubscribeReplacesOldModelCodes() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter = new AtomicInteger(0);
        Long connId = registry.registerEmitter(1L, 100L, new TestSseEmitter(counter));
        registry.subscribe(connId, Set.of("model_a"));
        registry.subscribe(connId, Set.of("model_b")); // Replace

        registry.pushToSubscribers(new DataSyncMessage(100L, "model_a", "update", "r1", 2L));
        assertEquals(0, counter.get()); // model_a no longer subscribed

        registry.pushToSubscribers(new DataSyncMessage(100L, "model_b", "update", "r1", 2L));
        assertEquals(1, counter.get()); // model_b is subscribed
    }

    @Test
    void removeConnection_cleansUp() throws IOException {
        var registry = new DataSyncSseRegistry();
        var counter = new AtomicInteger(0);
        Long connId = registry.registerEmitter(1L, 100L, new TestSseEmitter(counter));
        registry.subscribe(connId, Set.of("crm_opportunity"));

        registry.removeConnection(connId);

        registry.pushToSubscribers(new DataSyncMessage(100L, "crm_opportunity", "update", "r1", 2L));
        assertEquals(0, counter.get());
        assertEquals(0, registry.getActiveConnectionCount());
    }

    /**
     * Test SseEmitter that counts send() calls instead of actually sending.
     */
    static class TestSseEmitter extends SseEmitter {
        private final AtomicInteger sendCount;

        TestSseEmitter(AtomicInteger sendCount) {
            super(0L);
            this.sendCount = sendCount;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendCount.incrementAndGet();
        }
    }
}
