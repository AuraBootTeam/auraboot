package com.auraboot.framework.event;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Test
    void shouldPreserveExplicitNullAndFalsyValuesInIndependentSnapshot() {
        var payload = new HashMap<String, Object>();
        payload.put("unknownCapacity", null);
        payload.put("enabled", false);
        payload.put("count", 0);
        payload.put("note", "");
        var event = new TestEventWithModel(1L, "anc_resource", "resource-1", payload);

        payload.put("unknownCapacity", 100);
        payload.remove("enabled");
        payload.put("later", "change");

        assertThat(event.getPayload()).hasSize(4)
                .containsEntry("unknownCapacity", null)
                .containsEntry("enabled", false)
                .containsEntry("count", 0)
                .containsEntry("note", "")
                .doesNotContainKeys("absent", "later");
        assertThatThrownBy(() -> event.getPayload().put("added", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> event.getPayload().remove("unknownCapacity"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> event.getPayload().entrySet().iterator().next().setValue("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRetainShallowCopySemantics() {
        var nested = new HashMap<String, Object>();
        nested.put("value", "before");
        var event = new TestEventWithModel(1L, "order", "123", Map.of("nested", nested));

        nested.put("value", "after");

        assertThat(event.getPayload().get("nested")).isSameAs(nested);
        assertThat(event.getPayload()).containsEntry("nested", Map.of("value", "after"));
    }

    @Test
    void shouldRejectNullPayloadKeys() {
        var payload = new HashMap<String, Object>();
        payload.put(null, "invalid");

        assertThatThrownBy(() -> new TestEventWithModel(1L, "order", "123", payload))
                .isInstanceOf(NullPointerException.class);
    }

}
