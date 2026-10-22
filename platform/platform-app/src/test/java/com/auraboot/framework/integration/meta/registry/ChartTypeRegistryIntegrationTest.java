package com.auraboot.framework.integration.meta.registry;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.registry.ChartTypeRegistry;
import com.auraboot.framework.meta.registry.DslRegistryExporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ChartTypeRegistry} and its interaction with
 * {@link DslRegistryExporter}.
 */
class ChartTypeRegistryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ChartTypeRegistry chartTypeRegistry;

    @Autowired
    private DslRegistryExporter dslRegistryExporter;

    @Test
    void registry_startsWithAllEnumValues() {
        // The registry should contain all 24 built-in ChartType enum values
        assertThat(chartTypeRegistry.size()).isEqualTo(DslRegistry.ChartType.values().length);

        // Verify all enum codes are present
        Set<String> registryCodes = chartTypeRegistry.allCodes();
        for (DslRegistry.ChartType ct : DslRegistry.ChartType.values()) {
            assertThat(registryCodes).contains(ct.code());
        }
    }

    @Test
    void registry_containsOriginal6Types() {
        assertThat(chartTypeRegistry.isKnown("number")).isTrue();
        assertThat(chartTypeRegistry.isKnown("bar")).isTrue();
        assertThat(chartTypeRegistry.isKnown("line")).isTrue();
        assertThat(chartTypeRegistry.isKnown("pie")).isTrue();
        assertThat(chartTypeRegistry.isKnown("table")).isTrue();
        assertThat(chartTypeRegistry.isKnown("gauge")).isTrue();
    }

    @Test
    void registry_containsNewTypes() {
        assertThat(chartTypeRegistry.isKnown("area")).isTrue();
        assertThat(chartTypeRegistry.isKnown("radar")).isTrue();
        assertThat(chartTypeRegistry.isKnown("scatter")).isTrue();
        assertThat(chartTypeRegistry.isKnown("funnel")).isTrue();
        assertThat(chartTypeRegistry.isKnown("heatmap")).isTrue();
        assertThat(chartTypeRegistry.isKnown("treemap")).isTrue();
        assertThat(chartTypeRegistry.isKnown("number-card")).isTrue();
        assertThat(chartTypeRegistry.isKnown("countdown")).isTrue();
        assertThat(chartTypeRegistry.isKnown("calendar")).isTrue();
    }

    @Test
    void register_addsCustomType() {
        String customCode = "custom-chart-" + System.currentTimeMillis();
        int sizeBefore = chartTypeRegistry.size();

        boolean added = chartTypeRegistry.register(customCode, "Custom Chart", "2.0");

        assertThat(added).isTrue();
        assertThat(chartTypeRegistry.isKnown(customCode)).isTrue();
        assertThat(chartTypeRegistry.size()).isEqualTo(sizeBefore + 1);
        assertThat(chartTypeRegistry.allCodes()).contains(customCode);
    }

    @Test
    void register_doesNotOverwriteBuiltIn() {
        // Attempt to register an existing built-in type
        boolean added = chartTypeRegistry.register("bar", "Overwritten Bar", "9.9");

        assertThat(added).isFalse();
        // The original entry should remain unchanged
        assertThat(chartTypeRegistry.isKnown("bar")).isTrue();
    }

    @Test
    void isKnown_returnsFalseForUnregistered() {
        assertThat(chartTypeRegistry.isKnown("nonexistent-chart-type")).isFalse();
    }

    @Test
    void exporter_includesRuntimeRegisteredTypes() {
        // Register a custom chart type
        String customCode = "exporter-test-chart-" + System.currentTimeMillis();
        chartTypeRegistry.register(customCode, "Exporter Test Chart", "2.0");

        // Export the full registry
        Map<String, Object> exported = dslRegistryExporter.export();

        @SuppressWarnings("unchecked")
        Map<String, Object> enums = (Map<String, Object>) exported.get("enums");
        assertThat(enums).containsKey("ChartType");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> chartTypes = (List<Map<String, String>>) enums.get("ChartType");

        // Verify built-in types are exported
        assertThat(chartTypes.stream().map(m -> m.get("code")))
                .contains("bar", "line", "pie", "gauge");

        // Verify runtime-registered type is included in export
        assertThat(chartTypes.stream().map(m -> m.get("code")))
                .contains(customCode);

        // Verify the runtime entry has source=runtime
        Map<String, String> customEntry = chartTypes.stream()
                .filter(m -> customCode.equals(m.get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(customEntry.get("source")).isEqualTo("runtime");
        assertThat(customEntry.get("label")).isEqualTo("Exporter Test Chart");
    }

    @Test
    void exportEntries_ordersBuiltInBeforeRuntime() {
        String customCode = "zzz-last-chart-" + System.currentTimeMillis();
        chartTypeRegistry.register(customCode, "Last Chart", "2.0");

        List<Map<String, String>> entries = chartTypeRegistry.exportEntries();

        // All built-in entries should come before runtime entries
        boolean seenRuntime = false;
        for (Map<String, String> entry : entries) {
            if ("runtime".equals(entry.get("source"))) {
                seenRuntime = true;
            }
            if (seenRuntime && "built-in".equals(entry.get("source"))) {
                throw new AssertionError("Built-in entry found after runtime entry: " + entry.get("code"));
            }
        }
    }
}
