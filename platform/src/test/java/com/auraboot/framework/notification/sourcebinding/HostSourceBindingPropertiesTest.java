package com.auraboot.framework.notification.sourcebinding;

import static com.auraboot.framework.notification.sourcebinding.SourceBindingTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Actual Spring property binding, without a database, recipient resolution or delivery. */
class HostSourceBindingPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(HostSourceBindingProperties.class);

    private Binding binding(boolean shipment, boolean optionalMaps) {
        var names = new ArrayList<>(List.of("source", "target", "owner", "principal", "contact"));
        if (shipment) names.add("line");
        var approvals = names.stream().map(name -> new Approval("METADATA_SHAPE_V1", name, "0".repeat(64),
                List.of(new Owner("plugin", "com.example.bound", "bound", "1.0.0")),
                optionalMaps ? Set.<String>of() : Set.of("native_id"))).toList();
        var target = new Target("targets", new Entity("target", List.of(new Exact("managed", true), new Exact("state", "open"))), "owner",
                shipment ? null : new Deadline("cutoff", DeadlineRelation.AFTER_HOST_NOW),
                shipment ? new RemainingChild(new Entity("line", List.of(new Exact("managed", true))), "parent", "ordered", "submitted", 1000) : null);
        return new Binding(1, "notice", 7, approvals,
                new Source(new Entity("source", List.of(new Exact("kind", "quote"), new Exact("state", "pending"))), "pid", "owner", "occurred_at", target),
                new Entity("owner", List.of(new Exact("state", "active"))),
                new Principal(new Entity("principal", List.of(new Exact("state", "active"))), "user", "owner", "contact"),
                new Verification(new Entity("contact", List.of(new Exact("state", "active"))), "owner"),
                10, "template", optionalMaps ? Map.of() : Map.of("slot", "occurred_at"));
    }

    private void flatten(String prefix, JsonNode node, List<String> properties) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> flatten(prefix + "."
                + entry.getKey().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT), entry.getValue(), properties));
        else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) flatten(prefix + "[" + i + "]", node.get(i), properties);
        } else if (!node.isNull()) properties.add(prefix + "=" + node.asText());
    }

    private List<String> properties(Binding binding) {
        var properties = new ArrayList<>(List.of("aura.notification.source-bindings.enabled=true"));
        flatten("aura.notification.source-bindings.bindings[0]", SourceBindingCanonical.tree(binding), properties);
        return properties;
    }

    @Test
    void quoteDescriptorBindsCanonicalConstructorsAndRetainsTypedConditions() {
        var expected = binding(false, false);
        runner.withPropertyValues(properties(expected).toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            var actual = context.getBean(HostSourceBindingProperties.class);
            assertThat(actual.isEnabled()).isTrue();
            assertThat(actual.getBindings()).containsExactly(expected);
            assertThat(actual.getBindings().getFirst().source().targets().entity().equals().getFirst().value().raw()).isEqualTo(true);
        });
    }

    @Test
    void shipmentDescriptorBindsRemainingChildWithoutInventingDeadline() {
        var expected = binding(true, false);
        runner.withPropertyValues(properties(expected).toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HostSourceBindingProperties.class).getBindings()).containsExactly(expected);
        });
    }

    @Test
    void absentOptionalMappingsBindAsEmptyWithoutWeakeningRequiredFields() {
        var expected = binding(false, true);
        runner.withPropertyValues(properties(expected).toArray(String[]::new)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(HostSourceBindingProperties.class).getBindings()).containsExactly(expected);
        });
        var missing = properties(expected);
        missing.removeIf(value -> value.startsWith("aura.notification.source-bindings.bindings[0].tenant-id="));
        runner.withPropertyValues(missing.toArray(String[]::new)).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidTypedScalarAndUnknownDeploymentFlagStillRejectStartup() {
        var invalid = properties(binding(false, false));
        invalid.replaceAll(value -> value.replace(".value.type=BOOLEAN", ".value.type=UNTRUSTED"));
        runner.withPropertyValues(invalid.toArray(String[]::new)).run(context -> assertThat(context).hasFailed());
        var unknown = properties(binding(false, false));
        unknown.add("aura.notification.source-bindings.bindings[0].approved=true");
        runner.withPropertyValues(unknown.toArray(String[]::new)).run(context -> assertThat(context).hasFailed());
    }
}
