package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class EventAndProjectionContractTest {
    @ParameterizedTest
    @CsvSource({"*,order:created,true", "order:*,order:created,true", "order:*,orders:created,false",
            "*:created,order:created,true", "*:created,order:createdExtra,false",
            "order:created,order:created,true", "order:created,order:updated,false",
            "order:*,user:created,false", "*:created,order:updated,false"})
    void subscriptionsMatchExactDomainAndEventBoundaries(String pattern, String event, boolean expected) {
        EventListenerExtension listener = listener(Set.of(pattern));
        assertThat(listener.isInterestedIn(event)).isEqualTo(expected);
    }

    @Test
    void listenerDefaultsAndMultipleSubscriptionsRemainDeterministic() {
        EventListenerExtension listener = listener(Set.of("order:created", "user:*"));
        assertThat(listener.isInterestedIn("user:deleted")).isTrue();
        assertThat(listener.isInterestedIn("invoice:created")).isFalse();
        assertThat(listener.getOrder()).isEqualTo(100);
        assertThat(listener.isAsync()).isFalse();
        assertThat(listener(Set.of()).isInterestedIn("order:created")).isFalse();
    }

    @Test
    void eventContextBuilderPreservesHostMetadataAndExplicitTimestamp() {
        var context = EventListenerExtension.EventContext.builder().tenantId(7L).pluginId("plugin")
                .namespace("ns").eventType("order:updated").sourceModel("order").recordId("row")
                .eventData(Map.of("status", "done")).previousData(Map.of("status", "pending"))
                .timestamp(123L).build();
        assertThat(context).isEqualTo(new EventListenerExtension.EventContext(7L, "plugin", "ns", "order:updated",
                "order", "row", Map.of("status", "done"), Map.of("status", "pending"), 123L));
        long before = System.currentTimeMillis();
        var defaults = EventListenerExtension.EventContext.builder().build();
        assertThat(defaults.timestamp()).isBetween(before, System.currentTimeMillis());
        assertThat(defaults.tenantId()).isNull();
        assertThat(defaults.eventData()).isNull();
    }

    @Test
    void projectionQueryInDeduplicatesWithoutAddingTenantOrMutationCapabilities() {
        List<Map<String, Object>> calls = new ArrayList<>();
        TenantProjectionAccessor accessor = new TenantProjectionAccessor() {
            public Map<String, Object> getById(String model, String record) { throw new AssertionError("Unexpected lookup"); }
            public List<Map<String, Object>> query(String model, Map<String, Object> filters) {
                assertThat(model).isEqualTo("order");
                calls.add(filters);
                return List.of(Map.of("pid", filters.get("status")));
            }
        };
        assertThat(accessor.queryIn("order", "status", Arrays.asList("pending", null, "done", "pending")))
                .containsExactly(Map.of("pid", "pending"), Map.of("pid", "done"));
        assertThat(calls).containsExactly(Map.of("status", "pending"), Map.of("status", "done"));
        calls.clear();
        assertThat(accessor.queryIn("order", "status", null)).isEmpty();
        assertThat(accessor.queryIn("order", "status", List.of())).isEmpty();
        assertThat(accessor.queryIn("order", "status", Arrays.asList(null, null))).isEmpty();
        for (String field : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> accessor.queryIn("order", field, List.of("value")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).isEmpty();
    }

    private static EventListenerExtension listener(Set<String> patterns) {
        return new EventListenerExtension() {
            public Set<String> getSubscribedEvents() { return patterns; }
            public void onEvent(EventContext context) { throw new AssertionError("Matching must not dispatch"); }
        };
    }
}
