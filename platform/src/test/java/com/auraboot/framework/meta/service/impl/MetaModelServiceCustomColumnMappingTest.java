package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class MetaModelServiceCustomColumnMappingTest {

    private final MetaModelServiceImpl service = mock(MetaModelServiceImpl.class, CALLS_REAL_METHODS);

    @Test
    @DisplayName("existing-table fields retain their imported physical column name")
    void usesConfiguredPhysicalColumnName() {
        Field field = field("crm_ic_channel_type");
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of("columnName", "channel_type"));
        field.setExtension(extension);

        assertThat(definition(field).getColumnName()).isEqualTo("channel_type");
    }

    @Test
    @DisplayName("ordinary fields continue to use the generated snake-case column name")
    void fallsBackToGeneratedColumnName() {
        assertThat(definition(field("sampleValue")).getColumnName()).isEqualTo("sample_value");
    }

    @Test
    void persistedFieldQueryFlagsSurviveAssemblyAndCapabilityNormalization() {
        Field field = field("office_name");
        var feature = new com.auraboot.framework.meta.entity.payload.FieldFeatureBean();
        feature.setSearchable(true); feature.setSortable(true); feature.setFilterable(true); field.setFeature(feature);
        var assembled = definition(field);
        assertThat(assembled.getSearchable()).isTrue();
        assertThat(assembled.getSortable()).isTrue();
        assertThat(assembled.getFilterable()).isTrue();
        var projection = new ModelDefinitionProjection(null, null, null, null, null);
        var capabilities = projection.normalizeCapabilities(com.auraboot.framework.meta.dto.ModelDefinition.builder()
                .capabilities(com.auraboot.framework.meta.dto.ModelCapabilities.fullPhysical())
                .fields(java.util.List.of(assembled)).build());
        assertThat(capabilities.getSortableFields()).containsExactly("office_name");
        assertThat(capabilities.getFilterableFields()).containsExactly("office_name");
    }

    @Test
    void missingOrExplicitlyDisabledQueryFlagsDoNotCreateAuthority() {
        var missing = definition(field("unconfigured"));
        assertThat(Boolean.TRUE.equals(missing.getSortable())).isFalse();
        assertThat(Boolean.TRUE.equals(missing.getFilterable())).isFalse();
        Field disabled = field("disabled");
        var feature = new com.auraboot.framework.meta.entity.payload.FieldFeatureBean();
        feature.setSearchable(false); feature.setSortable(false); feature.setFilterable(false); disabled.setFeature(feature);
        var assembled = definition(disabled);
        assertThat(Boolean.TRUE.equals(assembled.getSearchable())).isFalse();
        assertThat(Boolean.TRUE.equals(assembled.getSortable())).isFalse();
        assertThat(Boolean.TRUE.equals(assembled.getFilterable())).isFalse();
    }

    private FieldDefinition definition(Field field) {
        return ReflectionTestUtils.invokeMethod(service, "convertToFieldDefinition", field, 0);
    }

    private static Field field(String code) {
        Field field = new Field();
        field.setCode(code);
        field.setDataType("string");
        return field;
    }
}
