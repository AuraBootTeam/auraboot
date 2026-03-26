package com.auraboot.module.meta.event;

import com.auraboot.framework.event.AuraEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration-style tests for the event system, covering edge cases:
 * multiple events, payload integrity, and null handling.
 */
@ExtendWith(MockitoExtension.class)
class EventIntegrationTest {

    @Mock
    private AuraEventBus auraEventBus;

    @InjectMocks
    private DomainEventPublisher domainEventPublisher;

    // ========== Test 1: Multiple events all received ==========

    @Test
    void testMultipleEventsPublished() {
        // Publish 3 different events in sequence
        domainEventPublisher.publishCommandCompleted(
                "create_order", "create", 1L, "rec-001",
                "pm_order", Map.of("title", "Order A"));

        domainEventPublisher.publishCommandCompleted(
                "update_order", "update", 1L, "rec-002",
                "pm_order", Map.of("status", "confirmed"));

        domainEventPublisher.publishCommandCompleted(
                "delete_item", "delete", 2L, "rec-003",
                "pm_item", Map.of());

        // All 3 events should be published
        verify(auraEventBus, times(3)).publish(any(CommandCompletedEvent.class));

        // Capture and verify each event was distinct
        ArgumentCaptor<CommandCompletedEvent> captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus, times(3)).publish(captor.capture());

        var events = captor.getAllValues();
        assertEquals(3, events.size());
        assertEquals("create_order", events.get(0).getCommandCode());
        assertEquals("update_order", events.get(1).getCommandCode());
        assertEquals("delete_item", events.get(2).getCommandCode());

        // Verify each event has a unique occurredAt timestamp or is at least non-null
        events.forEach(e -> assertNotNull(e.getOccurredAt()));

        // Verify each event has a unique eventId (ULID)
        events.forEach(e -> assertNotNull(e.getEventId()));
        assertEquals(3, events.stream().map(e -> e.getEventId()).distinct().count());
    }

    // ========== Test 2: Event listener receives correct payload ==========

    @Test
    void testEventListenerReceivesPayload() {
        Map<String, Object> payload = Map.of(
                "amount", 999,
                "currency", "usd",
                "items", 5
        );

        domainEventPublisher.publishCommandCompleted(
                "approve_invoice", "update", 10L, "inv-42",
                "fin_invoice", payload);

        ArgumentCaptor<CommandCompletedEvent> captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        CommandCompletedEvent event = captor.getValue();

        // Verify all event fields
        assertEquals("approve_invoice", event.getCommandCode());
        assertEquals("update", event.getOperationType());
        assertEquals(10L, event.getTenantId());
        assertEquals("inv-42", event.getRecordId());
        assertEquals("fin_invoice", event.getModelCode());

        // Verify payload content is intact
        assertEquals(999, event.getPayload().get("amount"));
        assertEquals("usd", event.getPayload().get("currency"));
        assertEquals(5, event.getPayload().get("items"));
        assertEquals(3, event.getPayload().size());

        // Verify payload is immutable (Map.copyOf in AuraEvent)
        assertThrows(UnsupportedOperationException.class,
                () -> event.getPayload().put("hacked", "value"),
                "Payload should be immutable");
    }

    // ========== Test 3: Null fields handled gracefully ==========

    @Test
    void testNullFieldsHandled() {
        // Null modelCode and null payload
        domainEventPublisher.publishCommandCompleted(
                "cleanup_task", "delete", 1L, "rec-999",
                null, null);

        ArgumentCaptor<CommandCompletedEvent> captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        CommandCompletedEvent event = captor.getValue();

        assertEquals("cleanup_task", event.getCommandCode());
        assertEquals("delete", event.getOperationType());
        assertEquals("rec-999", event.getRecordId());

        // modelCode is null - should pass through
        assertNull(event.getModelCode());

        // Payload should be empty map (not null), due to AuraEvent constructor
        assertNotNull(event.getPayload(), "Payload should never be null");
        assertTrue(event.getPayload().isEmpty(), "Null payload should become empty map");

        // occurredAt should always be set
        assertNotNull(event.getOccurredAt());
    }

    // ========== Test 4: AuraEvent base class fields populated ==========

    @Test
    void testAuraEventBaseFieldsPopulated() {
        domainEventPublisher.publishCommandCompleted(
                "submit_form", "create", 5L, "form-001",
                "pm_form", Map.of("field1", "value1"));

        ArgumentCaptor<CommandCompletedEvent> captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        CommandCompletedEvent event = captor.getValue();

        // AuraEvent base fields
        assertEquals(5L, event.getTenantId());
        assertEquals("form-001", event.getRecordId());
        assertEquals("pm_form", event.getModelCode());
        assertNotNull(event.getOccurredAt());
        assertNotNull(event.getEventId());
        assertEquals("command:completed", event.getEventType());

        // ApplicationEvent fields (source is "AuraEventBus" string in AuraEvent)
        assertNotNull(event.getSource());
        assertTrue(event.getTimestamp() > 0, "ApplicationEvent timestamp should be positive");

        // CommandCompletedEvent-specific fields
        assertEquals("submit_form", event.getCommandCode());
        assertEquals("create", event.getOperationType());
    }
}
