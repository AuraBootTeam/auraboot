package com.auraboot.framework.event;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuraEventTest {

    static class TestEvent extends AuraEvent {
        TestEvent(Long tenantId) {
            super(tenantId, "test:created", null, null, null);
        }
    }

    static class TestEventWithModel extends AuraEvent {
        TestEventWithModel(Long tenantId, String modelCode, String recordId, Map<String, Object> payload) {
            super(tenantId, "test:updated", modelCode, recordId, payload);
        }
    }

    @Test
    void shouldAutoGenerateEventId() {
        var event = new TestEvent(1L);
        assertThat(event.getEventId()).isNotNull().isNotEmpty();
    }

    @Test
    void shouldSetOccurredAt() {
        var event = new TestEvent(1L);
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void shouldPreserveFields() {
        var payload = Map.<String, Object>of("key", "value");
        var event = new TestEventWithModel(1L, "order", "123", payload);
        assertThat(event.getTenantId()).isEqualTo(1L);
        assertThat(event.getEventType()).isEqualTo("test:updated");
        assertThat(event.getModelCode()).isEqualTo("order");
        assertThat(event.getRecordId()).isEqualTo("123");
        assertThat(event.getPayload()).containsEntry("key", "value");
    }

    @Test
    void shouldMakePayloadImmutableCopy() {
        var payload = new HashMap<String, Object>();
        payload.put("key", "value");
        var event = new TestEventWithModel(1L, "order", "123", payload);
        payload.put("key2", "value2");
        assertThat(event.getPayload()).doesNotContainKey("key2");
    }

    @Test
    void shouldHandleNullPayload() {
        var event = new TestEventWithModel(1L, "order", "123", null);
        assertThat(event.getPayload()).isNotNull().isEmpty();
    }

    @Test
    void shouldBeApplicationEvent() {
        var event = new TestEvent(1L);
        assertThat(event).isInstanceOf(ApplicationEvent.class);
    }

    @Test
    void shouldSupportMetadata() {
        var event = new TestEvent(1L);
        assertThat(event.getMetadata()).isNotNull().isEmpty();
        event.addMetadata("source", "unit-test");
        assertThat(event.getMetadata()).containsEntry("source", "unit-test");
    }
}
