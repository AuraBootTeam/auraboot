package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PluginResourceImporterFieldReimportTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PluginResourceImporterImpl importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
    private final MetaFieldMapper fields = mock(MetaFieldMapper.class);
    private final MetaModelFieldBindingMapper bindings = mock(MetaModelFieldBindingMapper.class);
    private final SchemaManagementService schemas = mock(SchemaManagementService.class);
    private final MetaModelService models = mock(MetaModelService.class);
    private final Field existing = new Field();

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(importer, "objectMapper", json);
        ReflectionTestUtils.setField(importer, "metaFieldMapper", fields);
        ReflectionTestUtils.setField(importer, "fieldBindingMapper", bindings);
        ReflectionTestUtils.setField(importer, "schemaManagementService", schemas);
        ReflectionTestUtils.setField(importer, "metaModelService", models);
        ReflectionTestUtils.setField(importer, "extensionConverter", new ExtensionConverter());
        existing.setId(17L);
        existing.setPid("field-pid");
        existing.setDataType("decimal");
        existing.setExtension(json.readValue("""
                {"displayName":"Unit Price","description":null,"constraints":{"precision":18,"scale":6}}
                """, ExtensionBean.class));
        when(fields.findCurrentByCode("unit_price")).thenReturn(existing);
        when(fields.updateFieldInPlace(eq("field-pid"), any(), any(), any(), any(), eq("plugin-pid")))
                .thenReturn(1);
        when(bindings.findPublishedModelCodesByFieldId(17L)).thenReturn(List.of("quote_line"));
        when(schemas.updateTableByModel("quote_line"))
                .thenReturn(SchemaOperationResult.builder().success(true).build());
    }

    private FieldDefinitionDTO definition() {
        var dto = new FieldDefinitionDTO();
        dto.setCode("unit_price");
        dto.setDisplayName("Unit Price");
        dto.setDataType("decimal");
        var constraints = new FieldDefinitionDTO.FieldConstraints();
        constraints.setPrecision(18);
        constraints.setScale(6);
        dto.setConstraints(constraints);
        return dto;
    }

    private PluginResource reimport(FieldDefinitionDTO dto) {
        Map<String, Object> extension = ReflectionTestUtils.invokeMethod(importer, "buildFieldExtension", dto);
        return ReflectionTestUtils.invokeMethod(importer, "updateFieldForReimport",
                dto, "plugin-pid", "import-id", 42L, extension);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedDefinitionRetainsMetadataImportWithoutRepeatedSchemaWork(boolean nestedExtension) throws Exception {
        if (nestedExtension) {
            existing.setExtension(json.readValue("""
                    {"extension":{"displayName":"Unit Price","description":null,
                    "constraints":{"precision":18,"scale":6}}}
                    """, ExtensionBean.class));
        }
        PluginResource result = reimport(definition());
        assertThat(result.getResourcePid()).isEqualTo("field-pid");
        verify(fields).updateFieldInPlace(eq("field-pid"), eq("decimal"), any(), any(), any(), eq("plugin-pid"));
        verify(models).clearAllCache();
        verifyNoInteractions(bindings, schemas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "precision", "feature", "reference"})
    void changedDefinitionStillSynchronizesPublishedModels(String change) {
        var dto = definition();
        switch (change) {
            case "type" -> dto.setDataType("string");
            case "precision" -> dto.getConstraints().setPrecision(20);
            case "feature" -> dto.setFeature(Map.of("unique", true));
            case "reference" -> dto.setRefTarget(Map.of("targetEntity", "supplier"));
            default -> throw new IllegalArgumentException(change);
        }
        assertThat(reimport(dto).getResourcePid()).isEqualTo("field-pid");
        verify(schemas).updateTableByModel("quote_line");
    }

    @Test
    void changedDefinitionDoesNotSwallowSchemaFailure() {
        var dto = definition();
        dto.getConstraints().setScale(7);
        when(schemas.updateTableByModel("quote_line"))
                .thenReturn(SchemaOperationResult.builder().success(false).errorMessage("schema unavailable").build());
        assertThatThrownBy(() -> reimport(dto)).isInstanceOf(PluginException.class)
                .hasMessageContaining("schema unavailable");
    }
}
