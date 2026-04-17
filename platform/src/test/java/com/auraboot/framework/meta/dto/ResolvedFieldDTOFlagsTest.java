package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.payload.ComputedFieldOverride;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ResolvedFieldDTO editor flag population
 * (sortable / filterable / writable).
 *
 * <p>Designer UI reads these flags to prefill toggle state; at
 * MetaModelServiceImpl.saveDefinition time (T3) the toggles get
 * normalized into ModelCapabilities whitelist.
 */
class ResolvedFieldDTOFlagsTest {

    @Test
    void from_field_populates_flags_using_feature_values_when_present() {
        Field field = new Field();
        field.setCode("name");
        field.setDataType("string");
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setSortable(true);
        feature.setFilterable(true);
        feature.setReadonly(false);
        field.setFeature(feature);

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getSortable()).isTrue();
        assertThat(dto.getFilterable()).isTrue();
        assertThat(dto.getWritable()).isTrue();
    }

    @Test
    void from_field_defaults_sortable_filterable_true_for_scalar_types() {
        Field field = new Field();
        field.setCode("age");
        field.setDataType("integer");
        // Feature with no sortable/filterable → must use dataType-based default.
        field.setFeature(new FieldFeatureBean());

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getSortable()).isTrue();
        assertThat(dto.getFilterable()).isTrue();
        assertThat(dto.getWritable()).isTrue();
    }

    @Test
    void from_field_defaults_sortable_filterable_false_for_non_scalar_types() {
        Field field = new Field();
        field.setCode("payload");
        field.setDataType("json");
        field.setFeature(new FieldFeatureBean());

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getSortable()).isFalse();
        assertThat(dto.getFilterable()).isFalse();
    }

    @Test
    void from_field_marks_readonly_field_as_not_writable() {
        Field field = new Field();
        field.setCode("updated_at");
        field.setDataType("datetime");
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setReadonly(true);
        field.setFeature(feature);

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getWritable()).isFalse();
    }

    @Test
    void from_field_marks_computed_virtual_field_as_not_writable() {
        Field field = new Field();
        field.setCode("full_name");
        field.setDataType("string");
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setVirtualType("computed_readonly");
        feature.setComputeExpression("first_name || ' ' || last_name");
        field.setFeature(feature);

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getWritable()).isFalse();
    }

    @Test
    void from_field_with_null_feature_defaults_writable_true() {
        Field field = new Field();
        field.setCode("plain");
        field.setDataType("string");
        // feature intentionally null

        ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, null);

        assertThat(dto.getWritable()).isTrue();
        assertThat(dto.getSortable()).isTrue();
        assertThat(dto.getFilterable()).isTrue();
    }

    @Test
    void fromNamedQueryField_populates_flags_with_query_sourced_defaults() {
        NamedQueryField nqField = new NamedQueryField();
        nqField.setFieldCode("order_no");
        nqField.setDataType("string");
        nqField.setSortable(true);

        ResolvedFieldDTO dto = ResolvedFieldDTO.fromNamedQueryField(nqField);

        assertThat(dto.getSortable()).isTrue();
        assertThat(dto.getFilterable()).isTrue(); // string → default true
        // Named query-sourced fields are never user-writable.
        assertThat(dto.getWritable()).isFalse();
    }

    @Test
    void fromVirtual_marks_computed_field_as_not_writable() {
        ComputedFieldOverride override = new ComputedFieldOverride();
        override.setExpression("a + b");
        override.setReturnType("decimal");
        override.setLabel("Total");

        ResolvedFieldDTO dto = ResolvedFieldDTO.fromVirtual("total", override);

        assertThat(dto.getSortable()).isTrue(); // decimal → scalar
        assertThat(dto.getFilterable()).isTrue();
        assertThat(dto.getWritable()).isFalse();
    }
}
