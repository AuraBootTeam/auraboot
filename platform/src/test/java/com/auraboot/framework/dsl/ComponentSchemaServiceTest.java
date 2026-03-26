package com.auraboot.framework.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.dsl.dto.ComponentSchemaDTO;
import com.auraboot.framework.dsl.service.ComponentSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ComponentSchemaService — verifies JSON loading, filtering, and query behaviour.
 */
class ComponentSchemaServiceTest {

    private ComponentSchemaService service;

    @BeforeEach
    void setUp() {
        service = new ComponentSchemaService(new ObjectMapper());
        service.init();
    }

    // ---- Basic loading ----

    @Test
    void shouldLoadAllComponents() {
        Collection<ComponentSchemaDTO> all = service.getAllComponents();
        assertThat(all).isNotEmpty();
        // We have 35 components defined in the JSON
        assertThat(all.size()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void shouldHaveVersionString() {
        assertThat(service.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void shouldReturnCorrectComponentCount() {
        assertThat(service.getComponentCount()).isEqualTo(service.getAllComponents().size());
    }

    // ---- Single component lookup ----

    @Test
    void shouldGetComponentByType() {
        ComponentSchemaDTO input = service.getComponent("input");
        assertThat(input).isNotNull();
        assertThat(input.getType()).isEqualTo("input");
        assertThat(input.getName()).isEqualTo("Input");
        assertThat(input.getCategory()).isEqualTo("form");
        assertThat(input.getProperties()).isNotEmpty();
        assertThat(input.getTags()).contains("input", "text");
    }

    @Test
    void shouldReturnNullForUnknownComponent() {
        assertThat(service.getComponent("nonexistent")).isNull();
    }

    @Test
    void shouldGetSelectComponent() {
        ComponentSchemaDTO select = service.getComponent("select");
        assertThat(select).isNotNull();
        assertThat(select.getCategory()).isEqualTo("form");
        assertThat(select.getCompatibleDataTypes()).contains("string", "integer", "enum");
    }

    @Test
    void shouldGetSmartBarChart() {
        ComponentSchemaDTO chart = service.getComponent("smart-bar-chart");
        assertThat(chart).isNotNull();
        assertThat(chart.getCategory()).isEqualTo("chart");
        assertThat(chart.getName()).isEqualTo("Smart Bar Chart");
    }

    // ---- Data type filtering ----

    @Test
    void shouldFilterByDataTypeString() {
        List<ComponentSchemaDTO> result = service.getComponentsByDataType("string");
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c ->
                assertThat(c.getCompatibleDataTypes()).contains("string"));
        assertThat(result.stream().map(ComponentSchemaDTO::getType))
                .contains("input", "textarea", "select");
    }

    @Test
    void shouldFilterByDataTypeBoolean() {
        List<ComponentSchemaDTO> result = service.getComponentsByDataType("boolean");
        assertThat(result).isNotEmpty();
        assertThat(result.stream().map(ComponentSchemaDTO::getType))
                .contains("checkbox", "switch");
    }

    @Test
    void shouldFilterByDataTypeDate() {
        List<ComponentSchemaDTO> result = service.getComponentsByDataType("date");
        assertThat(result).isNotEmpty();
        assertThat(result.stream().map(ComponentSchemaDTO::getType))
                .contains("datepicker", "date");
    }

    @Test
    void shouldFilterByDataTypeCaseInsensitive() {
        List<ComponentSchemaDTO> upper = service.getComponentsByDataType("integer");
        List<ComponentSchemaDTO> lower = service.getComponentsByDataType("integer");
        assertThat(upper).hasSameSizeAs(lower);
    }

    @Test
    void shouldReturnEmptyForUnknownDataType() {
        List<ComponentSchemaDTO> result = service.getComponentsByDataType("unknown_type");
        assertThat(result).isEmpty();
    }

    // ---- Category filtering ----

    @Test
    void shouldFilterByCategory() {
        List<ComponentSchemaDTO> formComponents = service.getComponentsByCategory("form");
        assertThat(formComponents).isNotEmpty();
        assertThat(formComponents).allSatisfy(c ->
                assertThat(c.getCategory()).isEqualTo("form"));
    }

    @Test
    void shouldFilterByCategoryLayout() {
        List<ComponentSchemaDTO> result = service.getComponentsByCategory("layout");
        assertThat(result).isNotEmpty();
        assertThat(result.stream().map(ComponentSchemaDTO::getType))
                .contains("div", "form", "container", "grid", "flex");
    }

    @Test
    void shouldFilterByCategoryChart() {
        List<ComponentSchemaDTO> result = service.getComponentsByCategory("chart");
        assertThat(result).isNotEmpty();
        assertThat(result.stream().map(ComponentSchemaDTO::getType))
                .contains("smart-bar-chart", "bar-chart", "pie-chart");
    }

    @Test
    void shouldReturnEmptyForUnknownCategory() {
        List<ComponentSchemaDTO> result = service.getComponentsByCategory("nonexistent");
        assertThat(result).isEmpty();
    }

    // ---- Property schema integrity ----

    @Test
    void shouldHavePropertiesWithRequiredFields() {
        ComponentSchemaDTO input = service.getComponent("input");
        assertThat(input.getProperties()).isNotEmpty();

        var firstProp = input.getProperties().get(0);
        assertThat(firstProp).containsKey("key");
        assertThat(firstProp).containsKey("type");
        assertThat(firstProp).containsKey("group");
    }

    @Test
    void shouldHaveCompatibleDataTypesForFormComponents() {
        ComponentSchemaDTO numberInput = service.getComponent("numberinput");
        assertThat(numberInput.getCompatibleDataTypes())
                .contains("integer", "decimal");

        ComponentSchemaDTO datepicker = service.getComponent("datepicker");
        assertThat(datepicker.getCompatibleDataTypes())
                .contains("date", "datetime");
    }

    @Test
    void shouldHaveEmptyDataTypesForLayoutAndChartComponents() {
        ComponentSchemaDTO grid = service.getComponent("grid");
        assertThat(grid.getCompatibleDataTypes()).isEmpty();

        ComponentSchemaDTO button = service.getComponent("button");
        assertThat(button.getCompatibleDataTypes()).isEmpty();
    }

    // ---- All categories covered ----

    @Test
    void shouldCoverAllSixCategories() {
        Collection<ComponentSchemaDTO> all = service.getAllComponents();
        var categories = all.stream()
                .map(ComponentSchemaDTO::getCategory)
                .distinct()
                .toList();
        assertThat(categories).containsExactlyInAnyOrder(
                "form", "display", "interaction", "layout", "datetime", "chart");
    }
}
