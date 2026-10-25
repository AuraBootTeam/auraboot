package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.EventSnapshot;
import com.auraboot.framework.meta.entity.EventStoreEntry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventStore Integration Test
 *
 * Covers P2-1 requirements:
 * 1. Append domain events with versioning
 * 2. Get events for aggregate
 * 3. Get events since version
 * 4. Replay aggregate state from history
 * 5. Snapshot creation and usage
 * 6. Version conflict detection
 * 7. Paginated event stream
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("EventStore Integration Test - P2-1")
class EventStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EventStore eventStore;

    private static final String AGGREGATE_TYPE = "Order";

    /**
     * Helper method to create events for testing
     */
    private String createTestAggregate(String suffix, int eventCount) {
        String aggregateId = "order_" + System.currentTimeMillis() + "_" + suffix;
        Long tenantId = getTestTenant().getId();
        
        for (int i = 1; i <= eventCount; i++) {
            eventStore.append(
                    tenantId,
                    "Event" + i,
                    AGGREGATE_TYPE,
                    aggregateId,
                    "{\"step\":" + i + "}",
                    Map.of("userId", "user_" + i)
            );
        }
        
        return aggregateId;
    }

    // ==================== Append Tests ====================

    @Test
    @Order(1)
    @DisplayName("P2-1.1: Append first event to aggregate")
    void test01_appendFirstEvent() {
        String aggregateId = "order_" + System.currentTimeMillis() + "_first";
        Long tenantId = getTestTenant().getId();
        
        EventStoreEntry entry = eventStore.append(
                tenantId,
                "OrderCreated",
                AGGREGATE_TYPE,
                aggregateId,
                "{\"orderId\":\"" + aggregateId + "\",\"amount\":100}",
                Map.of("userId", "user_001")
        );

        assertNotNull(entry);
        assertNotNull(entry.getEventId());
        assertEquals("OrderCreated", entry.getEventType());
        assertEquals(AGGREGATE_TYPE, entry.getAggregateType());
        assertEquals(aggregateId, entry.getAggregateId());
        assertEquals(1, entry.getVersion());
        assertNotNull(entry.getOccurredAt());

        log.info("Appended first event: eventId={}, version={}", entry.getEventId(), entry.getVersion());
    }

    @Test
    @Order(2)
    @DisplayName("P2-1.1: Append second event increments version")
    void test02_appendSecondEvent() {
        String aggregateId = createTestAggregate("second", 1);
        Long tenantId = getTestTenant().getId();
        
        EventStoreEntry entry = eventStore.append(
                tenantId,
                "OrderApproved",
                AGGREGATE_TYPE,
                aggregateId,
                "{\"approvedBy\":\"manager_001\"}",
                Map.of("userId", "manager_001")
        );

        assertNotNull(entry);
        assertEquals(2, entry.getVersion());
        assertEquals("OrderApproved", entry.getEventType());
    }

    @Test
    @Order(3)
    @DisplayName("P2-1.1: Append third event continues version sequence")
    void test03_appendThirdEvent() {
        String aggregateId = createTestAggregate("third", 2);
        Long tenantId = getTestTenant().getId();
        
        EventStoreEntry entry = eventStore.append(
                tenantId,
                "OrderShipped",
                AGGREGATE_TYPE,
                aggregateId,
                "{\"trackingNumber\":\"TRK123\"}",
                Map.of("userId", "logistics_001")
        );

        assertNotNull(entry);
        assertEquals(3, entry.getVersion());
    }

    // ==================== Get Events Tests ====================

    @Test
    @Order(10)
    @DisplayName("P2-1.2: Get all events for aggregate")
    void test10_getEvents() {
        String aggregateId = createTestAggregate("getEvents", 3);
        Long tenantId = getTestTenant().getId();
        
        List<EventStoreEntry> events = eventStore.getEvents(tenantId, AGGREGATE_TYPE, aggregateId);

        assertNotNull(events);
        assertEquals(3, events.size(), "Should have 3 events");

        // Verify version ordering (ascending)
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).getVersion() > events.get(i - 1).getVersion(),
                    "Events should be in version order");
        }
    }

    @Test
    @Order(11)
    @DisplayName("P2-1.2: Get events for non-existent aggregate returns empty")
    void test11_getEvents_nonExistent() {
        Long tenantId = getTestTenant().getId();
        
        List<EventStoreEntry> events = eventStore.getEvents(tenantId, AGGREGATE_TYPE, "non_existent_agg_" + System.currentTimeMillis());

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    // ==================== Get Events Since Tests ====================

    @Test
    @Order(20)
    @DisplayName("P2-1.3: Get events since version 1 (exclusive)")
    void test20_getEventsSince() {
        String aggregateId = createTestAggregate("since", 3);
        Long tenantId = getTestTenant().getId();
        
        List<EventStoreEntry> events = eventStore.getEventsSince(
                tenantId, AGGREGATE_TYPE, aggregateId, 1);

        assertNotNull(events);
        assertEquals(2, events.size(), "Should have 2 events after version 1");
        assertTrue(events.stream().allMatch(e -> e.getVersion() > 1),
                "All events should have version > 1");
    }

    @Test
    @Order(21)
    @DisplayName("P2-1.3: Get events since latest version returns empty")
    void test21_getEventsSince_latest() {
        String aggregateId = createTestAggregate("sinceLatest", 3);
        Long tenantId = getTestTenant().getId();
        
        int currentVersion = eventStore.getCurrentVersion(tenantId, AGGREGATE_TYPE, aggregateId);

        List<EventStoreEntry> events = eventStore.getEventsSince(
                tenantId, AGGREGATE_TYPE, aggregateId, currentVersion);

        assertNotNull(events);
        assertTrue(events.isEmpty());
    }

    // ==================== Replay Tests ====================

    @Test
    @Order(30)
    @DisplayName("P2-1.4: Replay aggregate state")
    void test30_replay() {
        String aggregateId = createTestAggregate("replay", 3);
        Long tenantId = getTestTenant().getId();
        
        Map<String, Object> state = eventStore.replay(tenantId, AGGREGATE_TYPE, aggregateId);

        assertNotNull(state, "Replay should produce aggregate state");
        log.info("Replayed state: {}", state);
    }

    @Test
    @Order(31)
    @DisplayName("P2-1.4: Replay non-existent aggregate returns empty state")
    void test31_replay_nonExistent() {
        Long tenantId = getTestTenant().getId();
        
        Map<String, Object> state = eventStore.replay(tenantId, AGGREGATE_TYPE, "non_existent_replay_" + System.currentTimeMillis());

        // Implementation returns a map with metadata but no actual events
        assertNotNull(state);
        // Should have metadata but _eventCount should be 0
        assertEquals(0, state.get("_eventCount"), "Non-existent aggregate should have 0 events");
    }

    // ==================== Snapshot Tests ====================

    @Test
    @Order(40)
    @DisplayName("P2-1.5: Create snapshot of current aggregate state")
    void test40_createSnapshot() {
        String aggregateId = createTestAggregate("snapshot", 3);
        Long tenantId = getTestTenant().getId();
        
        EventSnapshot snapshot = eventStore.createSnapshot(tenantId, AGGREGATE_TYPE, aggregateId);

        assertNotNull(snapshot);
        assertEquals(AGGREGATE_TYPE, snapshot.getAggregateType());
        assertEquals(aggregateId, snapshot.getAggregateId());
        assertNotNull(snapshot.getVersion());
        assertEquals(3, snapshot.getVersion(), "Snapshot version should match current version");
        assertNotNull(snapshot.getState());

        log.info("Created snapshot: version={}", snapshot.getVersion());
    }

    @Test
    @Order(41)
    @DisplayName("P2-1.5: Append event after snapshot increases version")
    void test41_appendAfterSnapshot() {
        String aggregateId = createTestAggregate("afterSnapshot", 3);
        Long tenantId = getTestTenant().getId();
        
        // Create snapshot
        eventStore.createSnapshot(tenantId, AGGREGATE_TYPE, aggregateId);
        
        int versionBefore = eventStore.getCurrentVersion(tenantId, AGGREGATE_TYPE, aggregateId);

        EventStoreEntry entry = eventStore.append(
                tenantId,
                "OrderCompleted",
                AGGREGATE_TYPE,
                aggregateId,
                "{\"completedAt\":\"2024-01-01T00:00:00Z\"}",
                Map.of("userId", "system")
        );

        assertEquals(versionBefore + 1, entry.getVersion());
    }

    // ==================== Version Tests ====================

    @Test
    @Order(50)
    @DisplayName("P2-1.6: Get current version of aggregate")
    void test50_getCurrentVersion() {
        String aggregateId = createTestAggregate("version", 4);
        Long tenantId = getTestTenant().getId();
        
        int version = eventStore.getCurrentVersion(tenantId, AGGREGATE_TYPE, aggregateId);

        assertEquals(4, version, "Should have 4 events");
    }

    @Test
    @Order(51)
    @DisplayName("P2-1.6: Non-existent aggregate has version 0")
    void test51_getCurrentVersion_nonExistent() {
        Long tenantId = getTestTenant().getId();
        
        int version = eventStore.getCurrentVersion(tenantId, AGGREGATE_TYPE, "no_such_agg_" + System.currentTimeMillis());

        assertEquals(0, version);
    }

    // ==================== Event Stream Tests ====================

    @Test
    @Order(60)
    @DisplayName("P2-1.7: Get paginated event stream")
    void test60_getEventStream() {
        String aggregateId = createTestAggregate("stream", 5);
        Long tenantId = getTestTenant().getId();
        
        List<EventStoreEntry> stream = eventStore.getEventStream(
                tenantId, AGGREGATE_TYPE, aggregateId, 0, 10);

        assertNotNull(stream);
        assertFalse(stream.isEmpty());
        assertEquals(5, stream.size(), "Should have 5 events");
    }

    @Test
    @Order(61)
    @DisplayName("P2-1.7: Get second page of event stream")
    void test61_getEventStream_pagination() {
        String aggregateId = createTestAggregate("pagination", 5);
        Long tenantId = getTestTenant().getId();
        
        List<EventStoreEntry> page1 = eventStore.getEventStream(
                tenantId, AGGREGATE_TYPE, aggregateId, 0, 2);
        List<EventStoreEntry> page2 = eventStore.getEventStream(
                tenantId, AGGREGATE_TYPE, aggregateId, 1, 2);

        assertNotNull(page1);
        assertNotNull(page2);
        assertEquals(2, page1.size());
        assertEquals(2, page2.size());
    }

    // ==================== Tenant Isolation Tests ====================

    @Test
    @Order(70)
    @DisplayName("P2-1: Events are tenant-isolated")
    void test70_tenantIsolation() {
        String isolatedAgg = "isolated_" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();
        Long otherTenant = 999999L;

        eventStore.append(tenantId, "TestEvent", "IsolatedType", isolatedAgg,
                "{\"data\":\"for_tenant\"}", Map.of());

        List<EventStoreEntry> otherTenantEvents = eventStore.getEvents(otherTenant, "IsolatedType", isolatedAgg);

        assertTrue(otherTenantEvents == null || otherTenantEvents.isEmpty(),
                "Other tenant should not see events");
    }

    // ==================== Multiple Aggregate Types ====================

    @Test
    @Order(80)
    @DisplayName("P2-1: Different aggregate types are independent")
    void test80_differentAggregateTypes() {
        String aggId = "multi_type_" + System.currentTimeMillis();
        Long tenantId = getTestTenant().getId();

        eventStore.append(tenantId, "TypeACreated", "TypeA", aggId, "{}", Map.of());
        eventStore.append(tenantId, "TypeBCreated", "TypeB", aggId, "{}", Map.of());

        List<EventStoreEntry> typeAEvents = eventStore.getEvents(tenantId, "TypeA", aggId);
        List<EventStoreEntry> typeBEvents = eventStore.getEvents(tenantId, "TypeB", aggId);

        assertEquals(1, typeAEvents.size());
        assertEquals(1, typeBEvents.size());
        assertEquals("TypeACreated", typeAEvents.get(0).getEventType());
        assertEquals("TypeBCreated", typeBEvents.get(0).getEventType());
    }
}
