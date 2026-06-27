package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * C1: import normalizes every historical reference writing style to the single canonical
 * {@code refTarget.targetEntity}. This locks that {@code resolveFieldRefTarget} collapses
 * top-level referenceModelCode / refTarget.modelCode / refTarget.targetModel / extension.refTarget
 * into one shape and drops the alias keys, so runtime has one shape to read (no compatibility layer).
 */
class PluginResourceImporterRefTargetNormalizationTest {

    // resolveFieldRefTarget is a stateless private helper — a real-methods mock avoids the
    // (large, evolving) @RequiredArgsConstructor argument list.
    private final PluginResourceImporterImpl importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(FieldDefinitionDTO dto) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(importer, "resolveFieldRefTarget", dto);
    }

    private FieldDefinitionDTO refField() {
        FieldDefinitionDTO dto = new FieldDefinitionDTO();
        dto.setCode("some_ref");
        dto.setDataType("reference");
        return dto;
    }

    @Test
    @DisplayName("style A: top-level referenceModelCode -> canonical targetEntity")
    void normalizesTopLevelReferenceModelCode() {
        FieldDefinitionDTO dto = refField();
        dto.setReferenceModelCode("crm_account_common");
        Map<String, Object> rt = resolve(dto);
        assertThat(rt).containsEntry("targetEntity", "crm_account_common");
        assertThat(rt).doesNotContainKeys("modelCode", "targetModel");
    }

    @Test
    @DisplayName("style B: refTarget.modelCode -> canonical targetEntity, alias dropped")
    void normalizesRefTargetModelCode() {
        FieldDefinitionDTO dto = refField();
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("modelCode", "crm_account_common");
        src.put("displayField", "crm_acc_name");
        dto.setRefTarget(src);
        Map<String, Object> rt = resolve(dto);
        assertThat(rt).containsEntry("targetEntity", "crm_account_common");
        assertThat(rt).containsEntry("displayField", "crm_acc_name");
        assertThat(rt).doesNotContainKey("modelCode");
    }

    @Test
    @DisplayName("style C: refTarget.targetModel -> canonical targetEntity, alias dropped")
    void normalizesRefTargetTargetModel() {
        FieldDefinitionDTO dto = refField();
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("targetModel", "bom_conversion_task_pcba");
        dto.setRefTarget(src);
        Map<String, Object> rt = resolve(dto);
        assertThat(rt).containsEntry("targetEntity", "bom_conversion_task_pcba");
        assertThat(rt).doesNotContainKey("targetModel");
    }

    @Test
    @DisplayName("nested extension.refTarget.modelCode -> canonical targetEntity (the persisted shape)")
    void normalizesNestedExtensionRefTarget() {
        FieldDefinitionDTO dto = refField();
        Map<String, Object> ext = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("modelCode", "crm_account_common");
        nested.put("displayField", "crm_acc_name");
        ext.put("refTarget", nested);
        dto.setExtension(ext);
        Map<String, Object> rt = resolve(dto);
        assertThat(rt).containsEntry("targetEntity", "crm_account_common");
        assertThat(rt).containsEntry("displayField", "crm_acc_name");
        assertThat(rt).doesNotContainKey("modelCode");
    }

    @Test
    @DisplayName("preserves non-alias keys (valueField, relationship)")
    void preservesOtherKeys() {
        FieldDefinitionDTO dto = refField();
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("modelCode", "crm_account_common");
        src.put("valueField", "pid");
        src.put("relationship", "many-to-one");
        dto.setRefTarget(src);
        Map<String, Object> rt = resolve(dto);
        assertThat(rt).containsEntry("targetEntity", "crm_account_common");
        assertThat(rt).containsEntry("valueField", "pid");
        assertThat(rt).containsEntry("relationship", "many-to-one");
    }

    @Test
    @DisplayName("non-reference field with no target -> null")
    void nonReferenceReturnsNull() {
        FieldDefinitionDTO dto = new FieldDefinitionDTO();
        dto.setCode("plain");
        dto.setDataType("string");
        assertThat(resolve(dto)).isNull();
    }
}
