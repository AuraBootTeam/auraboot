package com.auraboot.framework.meta.registry;

import com.auraboot.framework.meta.constant.DslRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenDslEnumRegistry}.
 */
class OpenDslEnumRegistryTest {

    private OpenDslEnumRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new OpenDslEnumRegistry();
    }

    @Test
    void seedFromEnum_populatesAllValues() {
        registry.seedFromEnum(DslRegistry.ChartType.class);

        assertThat(registry.size()).isEqualTo(DslRegistry.ChartType.values().length);
        assertThat(registry.isKnown("bar")).isTrue();
        assertThat(registry.isKnown("line")).isTrue();
        assertThat(registry.isKnown("number")).isTrue();
    }

    @Test
    void register_addsNewEntry() {
        registry.seedFromEnum(DslRegistry.ChartType.class);
        int sizeBefore = registry.size();

        boolean added = registry.register("custom-chart", "Custom Chart", "2.0");

        assertThat(added).isTrue();
        assertThat(registry.size()).isEqualTo(sizeBefore + 1);
        assertThat(registry.isKnown("custom-chart")).isTrue();
    }

    @Test
    void register_doesNotOverwriteBuiltIn() {
        registry.seedFromEnum(DslRegistry.ChartType.class);

        boolean added = registry.register("bar", "Overwritten Bar", "9.9");

        assertThat(added).isFalse();
        // Size unchanged
        assertThat(registry.size()).isEqualTo(DslRegistry.ChartType.values().length);
    }

    @Test
    void register_duplicateRuntimeEntry_returnsFalse() {
        registry.register("my-chart", "My Chart", "1.0");

        boolean added = registry.register("my-chart", "My Chart v2", "2.0");

        assertThat(added).isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void allCodes_returnsAllRegisteredCodes() {
        registry.seedFromEnum(DslRegistry.AggregateFunction.class);
        registry.register("custom", "Custom", "2.0");

        Set<String> codes = registry.allCodes();

        assertThat(codes).contains("sum", "count", "avg", "max", "min", "custom");
    }

    @Test
    void isKnown_returnsFalseForUnknown() {
        registry.seedFromEnum(DslRegistry.ChartType.class);

        assertThat(registry.isKnown("nonexistent")).isFalse();
    }

    @Test
    void exportEntries_containsSourceField() {
        registry.seedFromEnum(DslRegistry.AggregateFunction.class);
        registry.register("custom-fn", "Custom Function", "2.0");

        List<Map<String, String>> entries = registry.exportEntries();

        // Built-in entries should have source=built-in
        Map<String, String> sumEntry = entries.stream()
                .filter(e -> "sum".equals(e.get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(sumEntry.get("source")).isEqualTo("built-in");
        assertThat(sumEntry.get("label")).isEqualTo("Sum");
        assertThat(sumEntry.get("since")).isEqualTo("1.0");

        // Runtime entries should have source=runtime
        Map<String, String> customEntry = entries.stream()
                .filter(e -> "custom-fn".equals(e.get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(customEntry.get("source")).isEqualTo("runtime");
    }

    @Test
    void exportEntries_ordersBuiltInFirst() {
        registry.register("aaa-runtime", "Runtime First", "2.0");
        registry.seedFromEnum(DslRegistry.AggregateFunction.class);

        List<Map<String, String>> entries = registry.exportEntries();

        // First entry should be built-in (alphabetically sorted within group)
        assertThat(entries.get(0).get("source")).isEqualTo("built-in");
        // Last entry should be runtime
        Map<String, String> last = entries.get(entries.size() - 1);
        assertThat(last.get("source")).isEqualTo("runtime");
    }

    @Test
    void emptyRegistry_hasZeroSize() {
        assertThat(registry.size()).isEqualTo(0);
        assertThat(registry.allCodes()).isEmpty();
        assertThat(registry.allEntries()).isEmpty();
        assertThat(registry.exportEntries()).isEmpty();
    }

    @Test
    void seedFromEnum_worksWithDifferentEnumTypes() {
        // Verify the generic approach works with multiple enum types
        OpenDslEnumRegistry reg1 = new OpenDslEnumRegistry();
        reg1.seedFromEnum(DslRegistry.ModelType.class);
        assertThat(reg1.size()).isEqualTo(4);
        assertThat(reg1.isKnown("entity")).isTrue();

        OpenDslEnumRegistry reg2 = new OpenDslEnumRegistry();
        reg2.seedFromEnum(DslRegistry.RiskLevel.class);
        assertThat(reg2.size()).isEqualTo(5);
        assertThat(reg2.isKnown("L0")).isTrue();
    }
}
