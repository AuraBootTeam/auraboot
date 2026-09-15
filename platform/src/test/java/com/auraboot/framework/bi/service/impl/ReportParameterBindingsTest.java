package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportParameterBindingsTest {
    private Map<String, Object> report(String type, boolean required) {
        return Map.of("parameters", List.of(Map.of("name", "input", "type", type,
                "required", required, "defaultValue", type.equals("text") ? "default" : "",
                "bindTo", Map.of("dataSource", "orders", "field", "title", "operator", "EQ"))),
                "dataSources", Map.of("orders", Map.of("type", "model", "modelCode", "orders")));
    }

    @SuppressWarnings("unchecked")
    private List<Object> filters(Map<String, Object> report) {
        return (List<Object>) ((Map<?, ?>) ((Map<?, ?>) report.get("dataSources")).get("orders")).get("filters");
    }

    @Test void appliesDefaultsAndOverridesWithoutMutatingDefinition() {
        Map<String, Object> source = report("text", true);
        assertThat(filters(ReportParameterBindings.apply(source, null))).containsExactly(
                Map.of("field", "title", "operator", "EQ", "value", "default"));
        assertThat(filters(ReportParameterBindings.apply(source, Map.of("input", "chosen")))).containsExactly(
                Map.of("field", "title", "operator", "EQ", "value", "chosen"));
        assertThat(filters(source)).isNull();
    }

    @Test void rejectsMissingRequiredAndUnknownValues() {
        assertThatThrownBy(() -> ReportParameterBindings.apply(report("text", true), Map.of("input", "")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Required");
        assertThatThrownBy(() -> ReportParameterBindings.apply(report("text", false), Map.of("other", "value")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Unknown");
    }

    @Test void preservesZeroAndRejectsInvalidNumbers() {
        assertThat(filters(ReportParameterBindings.apply(report("number", true), Map.of("input", 0))))
                .containsExactly(Map.of("field", "title", "operator", "EQ", "value", java.math.BigDecimal.ZERO));
        assertThatThrownBy(() -> ReportParameterBindings.apply(report("number", true), Map.of("input", "NaN")))
                .isInstanceOf(ValidationException.class);
    }

    @Test void resolvesCompleteDateRangesAndRejectsPartialOrReversedRanges() {
        assertThat(filters(ReportParameterBindings.apply(report("date-range", true),
                Map.of("input_start", "2026-09-01", "input_end", "2026-09-12"))))
                .containsExactly(Map.of("field", "title", "operator", "BETWEEN", "values", List.of("2026-09-01", "2026-09-12")));
        assertThatThrownBy(() -> ReportParameterBindings.apply(report("date-range", true), Map.of("input_start", "2026-09-01")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ReportParameterBindings.apply(report("date-range", true),
                Map.of("input_start", "2026-09-12", "input_end", "2026-09-01")))
                .isInstanceOf(ValidationException.class);
    }
}
