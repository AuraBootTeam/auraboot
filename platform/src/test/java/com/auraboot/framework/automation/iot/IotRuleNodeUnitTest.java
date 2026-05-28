package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the four IoT rule nodes — each is exercised in isolation
 * against a plain {@code Map} context, mirroring how SmartEngine would invoke
 * the underlying {@link com.auraboot.framework.automation.executor.ActionExecutor}.
 * No Spring, no engine — purely the node behaviour.
 */
class IotRuleNodeUnitTest {

    private static AutomationAction action(String type, Map<String, Object> cfg) {
        return AutomationAction.builder().type(type).config(cfg).build();
    }

    // ---------- IotFilterNode ----------

    @Nested
    class FilterNode {
        private final IotFilterNode node = new IotFilterNode();

        @Test
        @DisplayName("product scope: in-scope device passes")
        void inScopeProductPasses() {
            Map<String, Object> ctx = new HashMap<>(Map.of(
                    IotRuleContextKeys.PRODUCT_ID, "p1",
                    IotRuleContextKeys.DEVICE_ID, "d1"));
            Object result = node.execute(
                    action("iot_filter", Map.of("productIds", List.of("p1", "p2"))), ctx);
            assertThat(result).isEqualTo(Map.of("matched", true));
            assertThat(ctx).containsEntry(IotRuleContextKeys.DROPPED, Boolean.FALSE);
        }

        @Test
        @DisplayName("product scope: out-of-scope device is dropped with reason")
        void outOfScopeProductDropped() {
            Map<String, Object> ctx = new HashMap<>(Map.of(
                    IotRuleContextKeys.PRODUCT_ID, "px"));
            node.execute(action("iot_filter", Map.of("productIds", List.of("p1"))), ctx);
            assertThat(ctx).containsEntry(IotRuleContextKeys.DROPPED, Boolean.TRUE);
            assertThat((String) ctx.get(IotRuleContextKeys.DROP_REASON)).contains("not in scope");
        }

        @Test
        @DisplayName("predicate: SpEL boolean controls pass/drop")
        void predicateSpel() {
            Map<String, Object> ctx = new HashMap<>(Map.of("temperature", 95.0));
            node.execute(action("iot_filter", Map.of("predicate", "#temperature > 80")), ctx);
            assertThat(ctx).containsEntry(IotRuleContextKeys.DROPPED, Boolean.FALSE);

            Map<String, Object> ctx2 = new HashMap<>(Map.of("temperature", 50.0));
            node.execute(action("iot_filter", Map.of("predicate", "#temperature > 80")), ctx2);
            assertThat(ctx2).containsEntry(IotRuleContextKeys.DROPPED, Boolean.TRUE);
        }

        @Test
        @DisplayName("empty config: matches by default")
        void emptyConfigMatches() {
            Map<String, Object> ctx = new HashMap<>();
            node.execute(action("iot_filter", Map.of()), ctx);
            assertThat(ctx).containsEntry(IotRuleContextKeys.DROPPED, Boolean.FALSE);
        }
    }

    // ---------- IotEnrichmentNode ----------

    @Nested
    class EnrichmentNode {

        private IotEnrichmentNode node(BackgroundDeviceAccessor d, BackgroundProductAccessor p) {
            return new IotEnrichmentNode(provider(d), provider(p));
        }

        @SuppressWarnings("unchecked")
        private <T> ObjectProvider<T> provider(T bean) {
            ObjectProvider<T> op = (ObjectProvider<T>) new ObjectProvider<T>() {
                @Override public T getObject() { if (bean == null) throw new IllegalStateException(); return bean; }
                @Override public T getObject(Object... args) { return getObject(); }
                @Override public T getIfAvailable() { return bean; }
                @Override public T getIfUnique() { return bean; }
            };
            return op;
        }

        @Test
        @DisplayName("device accessor promotes productId from device metadata")
        void promotesProductIdFromDevice() {
            IotEnrichmentNode node = node(
                    id -> Map.of("productId", "p-2", "site", "S1"),
                    pid -> Map.of("model", "M1"));
            Map<String, Object> ctx = new HashMap<>(Map.of(IotRuleContextKeys.DEVICE_ID, "d-1"));
            node.execute(action("iot_enrichment", Map.of()), ctx);
            assertThat(ctx).containsEntry(IotRuleContextKeys.PRODUCT_ID, "p-2");
            assertThat(ctx).containsKey(IotRuleContextKeys.DEVICE_META);
            assertThat(ctx).containsKey(IotRuleContextKeys.PRODUCT_META);
        }

        @Test
        @DisplayName("no accessor beans: enrichment is a quiet no-op")
        void noAccessorIsNoop() {
            IotEnrichmentNode node = node(null, null);
            Map<String, Object> ctx = new HashMap<>(Map.of(IotRuleContextKeys.DEVICE_ID, "d-1"));
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) node.execute(
                    action("iot_enrichment", Map.of()), ctx);
            assertThat(result).containsEntry("enriched", false);
            assertThat(ctx).doesNotContainKey(IotRuleContextKeys.DEVICE_META);
        }

        @Test
        @DisplayName("dropped upstream: enrichment skipped")
        void skippedWhenDropped() {
            IotEnrichmentNode node = node(id -> Map.of("site", "S"), pid -> Map.of("m", "M"));
            Map<String, Object> ctx = new HashMap<>(Map.of(
                    IotRuleContextKeys.DROPPED, Boolean.TRUE,
                    IotRuleContextKeys.DEVICE_ID, "d-1"));
            node.execute(action("iot_enrichment", Map.of()), ctx);
            assertThat(ctx).doesNotContainKey(IotRuleContextKeys.DEVICE_META);
        }
    }

    // ---------- IotTransformationNode ----------

    @Nested
    class TransformationNode {
        private final IotTransformationNode node = new IotTransformationNode();

        @Test
        @DisplayName("F to C: assignments evaluate in order, later sees earlier")
        void cascadingAssignments() {
            Map<String, Object> ctx = new HashMap<>(Map.of("temperatureF", 212.0));
            node.execute(action("iot_transformation", Map.of(
                    "assignments", List.of(
                            Map.of("target", "tempC",      "expression", "(#temperatureF - 32) * 5 / 9"),
                            Map.of("target", "isHighTemp", "expression", "#tempC > 80")))), ctx);
            assertThat((Double) ctx.get("tempC")).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.01));
            assertThat(ctx).containsEntry("isHighTemp", Boolean.TRUE);
        }

        @Test
        @DisplayName("missing target or expression: rejected with clear error")
        void rejectsIncompleteAssignment() {
            Map<String, Object> ctx = new HashMap<>();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> node.execute(action("iot_transformation", Map.of(
                            "assignments", List.of(Map.of("target", "x")))), ctx));
        }
    }

    // ---------- IotActionNode ----------

    @Nested
    class ActionNode {

        @Test
        @DisplayName("emits envelope to all wired sinks and appends to outcomes list")
        void emitsToAllSinks() {
            List<Map<String, Object>> sink1Capture = new ArrayList<>();
            List<Map<String, Object>> sink2Capture = new ArrayList<>();
            IotActionSink sink1 = (kind, env) -> sink1Capture.add(env);
            IotActionSink sink2 = (kind, env) -> sink2Capture.add(env);
            IotActionNode node = new IotActionNode(List.of(sink1, sink2));

            Map<String, Object> ctx = new HashMap<>(Map.of(
                    IotRuleContextKeys.DEVICE_ID, "d-1",
                    "temperature", 95.0));
            node.execute(action("iot_action", Map.of(
                    "kind", "alarm",
                    "topic", "iot.alarm.v1",
                    "payload", Map.of(
                            "metric", "temperature",
                            "value", "${temperature}"))), ctx);

            assertThat(sink1Capture).hasSize(1);
            assertThat(sink2Capture).hasSize(1);
            @SuppressWarnings("unchecked")
            List<Object> outcomes = (List<Object>) ctx.get(IotRuleContextKeys.ACTION_OUTCOMES);
            assertThat(outcomes).hasSize(1);

            Map<String, Object> env = sink1Capture.get(0);
            assertThat(env).containsEntry("kind", "alarm");
            assertThat(env).containsEntry("topic", "iot.alarm.v1");
            assertThat(env).containsEntry("deviceId", "d-1");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) env.get("payload");
            // ${var} single-token: preserves Double type
            assertThat(payload).containsEntry("value", 95.0);
        }

        @Test
        @DisplayName("dropped upstream: no sinks invoked")
        void dropSkipsEmission() {
            List<Map<String, Object>> capture = new ArrayList<>();
            IotActionNode node = new IotActionNode(List.of((k, e) -> capture.add(e)));
            Map<String, Object> ctx = new HashMap<>(Map.of(IotRuleContextKeys.DROPPED, Boolean.TRUE));
            node.execute(action("iot_action", Map.of("kind", "alarm", "payload", Map.of())), ctx);
            assertThat(capture).isEmpty();
        }
    }
}
