package com.auraboot.framework.meta.event;

import com.auraboot.framework.event.AuraEventBus;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.auraboot.module.meta.event.DomainEventPublisher;
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
 * Tests that DomainEventPublisher correctly publishes CommandCompletedEvent
 * via AuraEventBus with the expected fields - validating the wiring contract
 * used by CommandExecutorImpl after the EFFECT phase.
 *
 * <p>CommandExecutorImpl has ~20 dependencies making full-mock construction
 * brittle. Instead we verify the publisher contract directly: if the publisher
 * receives the correct arguments, the event will carry the correct data to
 * all @EventListener subscribers (including CommandEventLogger and the
 * finance voucher engine).</p>
 */
@ExtendWith(MockitoExtension.class)
class DomainEventWiringTest {

    @Mock
    private AuraEventBus auraEventBus;

    @InjectMocks
    private DomainEventPublisher domainEventPublisher;

    /**
     * Simulates the call that CommandExecutorImpl makes after the EFFECT phase
     * and verifies the event carries all fields needed by downstream listeners.
     */
    @Test
    void publishCommandCompleted_carriesAllFieldsForDownstreamListeners() {
        // Given - the exact shape of data CommandExecutorImpl passes
        String commandCode = "pe_so_create";
        String operationType = "create";
        Long tenantId = 42L;
        String recordId = "rec-abc-123";
        String modelCode = "pe_sales_order";
        Map<String, Object> payload = Map.of(
                "customer_name", "Acme Corp",
                "total_amount", 1500
        );

        // When - mirrors the try block added in CommandExecutorImpl
        domainEventPublisher.publishCommandCompleted(
                commandCode, operationType, tenantId, recordId, modelCode, payload);

        // Then - capture and assert
        ArgumentCaptor<CommandCompletedEvent> captor =
                ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus, times(1)).publish(captor.capture());

        CommandCompletedEvent event = captor.getValue();
        assertEquals(commandCode, event.getCommandCode());
        assertEquals(operationType, event.getOperationType());
        assertEquals(tenantId, event.getTenantId());
        assertEquals(recordId, event.getRecordId());
        assertEquals(modelCode, event.getModelCode());
        assertEquals("Acme Corp", event.getPayload().get("customer_name"));
        assertEquals(1500, event.getPayload().get("total_amount"));
        assertNotNull(event.getOccurredAt(), "occurredAt must be set for audit trail");
        assertNotNull(event.getEventId(), "eventId (ULID) must be generated");
        assertEquals("command:completed", event.getEventType());
    }

    /**
     * When request is null (e.g., standalone commands), recordId should be null
     * and the event should still publish successfully.
     */
    @Test
    void publishCommandCompleted_withNullRecordId_stillPublishes() {
        domainEventPublisher.publishCommandCompleted(
                "system_cleanup", "delete", 1L, null, "sys_task", Map.of());

        ArgumentCaptor<CommandCompletedEvent> captor =
                ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        CommandCompletedEvent event = captor.getValue();
        assertNull(event.getRecordId());
        assertEquals("system_cleanup", event.getCommandCode());
    }

    /**
     * Verifies that if the AuraEventBus throws, the exception propagates
     * to the caller (CommandExecutorImpl wraps this in try-catch).
     */
    @Test
    void publisherException_doesNotPropagate_whenCaughtByCaller() {
        // Simulate what happens when AuraEventBus.publish() throws
        doThrow(new RuntimeException("listener blew up"))
                .when(auraEventBus).publish(any());

        // The publisher itself will throw; CommandExecutorImpl wraps this in try-catch.
        // Here we verify the exception IS thrown (so the caller's catch block fires).
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                domainEventPublisher.publishCommandCompleted(
                        "bad_cmd", "create", 1L, "rec-x", "model", Map.of()));
        assertEquals("listener blew up", ex.getMessage());
    }
}
