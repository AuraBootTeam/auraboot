package com.auraboot.module.meta.event;

import com.auraboot.framework.event.AuraEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DomainEventPublisher.
 * Verifies events are published via AuraEventBus with correct fields.
 */
@ExtendWith(MockitoExtension.class)
class DomainEventPublisherTest {

    @Mock
    private AuraEventBus auraEventBus;

    @InjectMocks
    private DomainEventPublisher domainEventPublisher;

    @Test
    void shouldPublishCommandCompletedEventViaAuraEventBus() {
        domainEventPublisher.publishCommandCompleted(
                "createOrder", "create", 1L, "rec-1", "order", Map.of("title", "Test"));

        var captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        var event = captor.getValue();
        assertThat(event.getCommandCode()).isEqualTo("createOrder");
        assertThat(event.getOperationType()).isEqualTo("create");
        assertThat(event.getTenantId()).isEqualTo(1L);
        assertThat(event.getRecordId()).isEqualTo("rec-1");
        assertThat(event.getModelCode()).isEqualTo("order");
        assertThat(event.getPayload()).containsEntry("title", "Test");
        assertThat(event.getEventType()).isEqualTo("command:completed");
    }

    @Test
    void shouldHandleNullPayload() {
        domainEventPublisher.publishCommandCompleted(
                "deleteOrder", "delete", 1L, "rec-2", "order", null);

        var captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());
        assertThat(captor.getValue().getPayload()).isNotNull().isEmpty();
    }

    @Test
    void shouldPublishMultipleEventsIndependently() {
        domainEventPublisher.publishCommandCompleted(
                "create_item", "create", 1L, "rec-A", "pm_item", Map.of());
        domainEventPublisher.publishCommandCompleted(
                "delete_item", "delete", 1L, "rec-B", "pm_item", Map.of());

        verify(auraEventBus, times(2)).publish(any(CommandCompletedEvent.class));
    }

    @Test
    void shouldPopulateEventIdAndOccurredAt() {
        domainEventPublisher.publishCommandCompleted(
                "update_order", "update", 2L, "rec-002", "pm_order",
                Map.of("amount", 100, "status", "draft"));

        var captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());

        var event = captor.getValue();
        assertThat(event.getEventId()).isNotNull().isNotEmpty();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getPayload()).containsEntry("amount", 100);
        assertThat(event.getPayload()).containsEntry("status", "draft");
    }
    @Test
    void shouldPublishRealCommandEventWithNullableCapabilitySnapshot() {
        var payload = new HashMap<String, Object>();
        payload.put("usableLengthMeters", null);
        payload.put("enabled", false);
        payload.put("count", 0);
        payload.put("note", "");

        domainEventPublisher.publishCommandCompleted(
                "anc_resource:publish_capability", "update", 7L, "resource-1", "anc_resource",
                payload, 42L, "operator", Map.of("traceId", "null-payload-regression"));

        var captor = ArgumentCaptor.forClass(CommandCompletedEvent.class);
        verify(auraEventBus).publish(captor.capture());
        var event = captor.getValue();
        payload.put("usableLengthMeters", 100);
        payload.remove("enabled");
        payload.put("later", true);

        assertThat(event.getPayload()).hasSize(4)
                .containsEntry("usableLengthMeters", null)
                .containsEntry("enabled", false)
                .containsEntry("count", 0)
                .containsEntry("note", "")
                .doesNotContainKeys("absent", "later");
        assertThat(event.getCommandCode()).isEqualTo("anc_resource:publish_capability");
        assertThat(event.getOperationType()).isEqualTo("update");
        assertThat(event.getTenantId()).isEqualTo(7L);
        assertThat(event.getRecordId()).isEqualTo("resource-1");
        assertThat(event.getModelCode()).isEqualTo("anc_resource");
        assertThat(event.getMetadata()).containsEntry("actorId", 42L)
                .containsEntry("actorName", "operator")
                .containsEntry("traceId", "null-payload-regression");
        assertThatThrownBy(() -> event.getPayload().put("note", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoMoreInteractions(auraEventBus);
    }

    @Test
    void shouldRejectNullKeyBeforePublishing() {
        var payload = new HashMap<String, Object>();
        payload.put(null, "invalid");

        assertThatThrownBy(() -> domainEventPublisher.publishCommandCompleted(
                "anc_resource:publish_capability", "update", 7L, "resource-1", "anc_resource", payload))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(auraEventBus);
    }

}
