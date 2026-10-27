package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldDictBinding;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.dto.MetaFieldUpdateRequest;
import com.auraboot.framework.meta.dto.MetaFieldValidationResult;
import com.auraboot.framework.meta.mapper.*;
import com.auraboot.framework.meta.service.impl.MetaFieldServiceImpl;
import com.auraboot.framework.meta.validator.MetaFieldValidator;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

class FieldVersionMetadataTest {
    @BeforeEach void context() { MetaContext.setContext(7L, 99L, "actor", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void editingAFieldPreservesMetadataAndDictionaryForTheNewBoundVersion() {
        var fields = mock(MetaFieldMapper.class); var dictionaries = mock(MetaFieldDictBindingMapper.class);
        var bindings = mock(MetaModelFieldBindingMapper.class); var validator = mock(MetaFieldValidator.class);
        var service = new MetaFieldServiceImpl(fields, null, null, null, new ExtensionConverter(), validator, dictionaries, bindings, null, new ObjectMapper());
        var old = new Field(); old.setId(1L); old.setPid("old-field"); old.setCode("customer"); old.setDataType("enum");
        var feature = new FieldFeatureBean(); feature.setRequired(true); old.setFeature(feature);
        var extension = new ExtensionBean(); extension.setExtension(Map.of("displayName", "客户")); old.setExtension(extension);
        when(fields.selectByPidWithContext("old-field", 7L)).thenReturn(old);
        when(fields.getNextVersion("customer")).thenReturn(2);
        when(validator.validateUpdateRequest(isNull(), any())).thenReturn(MetaFieldValidationResult.builder().build());
        doAnswer(invocation -> { ((Field) invocation.getArgument(0)).setId(2L); return 1; }).when(fields).insert(any(Field.class));
        when(dictionaries.findByFieldPid("old-field", 7L)).thenReturn(FieldDictBinding.builder()
                .fieldId(1L).fieldPid("old-field").dictId(5L).dictCode("customer_type").tenantId(7L).build());
        var request = new MetaFieldUpdateRequest(); request.setDataType("enum");
        var result = service.update("old-field", request);
        assertThat(result.getFeature()).containsEntry("required", true);
        assertThat(result.getExtension()).containsEntry("displayName", "客户");
        verify(bindings).updateFieldIdForBindings(7L, 1L, 2L);
        var copied = org.mockito.ArgumentCaptor.forClass(FieldDictBinding.class);
        verify(dictionaries).insert(copied.capture());
        assertThat(copied.getValue().getFieldPid()).isEqualTo(result.getPid()).isNotEqualTo("old-field");
        assertThat(copied.getValue().getFieldId()).isEqualTo(2L);
        assertThat(copied.getValue().getDictCode()).isEqualTo("customer_type");
        assertThat(copied.getValue().getTenantId()).isEqualTo(7L);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void retainsOrExplicitlyReplacesReferenceMetadata(boolean replace) {
        var fields = mock(MetaFieldMapper.class); var dictionaries = mock(MetaFieldDictBindingMapper.class);
        var validator = mock(MetaFieldValidator.class);
        var service = new MetaFieldServiceImpl(fields, null, null, null, new ExtensionConverter(), validator,
                dictionaries, mock(MetaModelFieldBindingMapper.class), null, new ObjectMapper());
        var old = new Field(); old.setId(1L); old.setPid("old-field"); old.setCode("customer"); old.setDataType("reference");
        var target = new FieldRefTargetBean(); target.setTargetEntity("customer"); target.setDisplayField("name"); old.setRefTarget(target);
        when(fields.selectByPidWithContext("old-field", 7L)).thenReturn(old);
        when(fields.getNextVersion("customer")).thenReturn(2);
        when(validator.validateUpdateRequest(isNull(), any())).thenReturn(MetaFieldValidationResult.builder().build());
        doAnswer(invocation -> { ((Field) invocation.getArgument(0)).setId(2L); return 1; }).when(fields).insert(any(Field.class));
        var request = new MetaFieldUpdateRequest(); request.setDataType("reference");
        if (replace) request.setRefTarget(Map.of("targetEntity", "other_customer", "displayField", "business_name"));
        var result = service.update("old-field", request);
        assertThat(result.getRefTarget()).containsEntry("targetEntity", replace ? "other_customer" : "customer")
                .containsEntry("displayField", replace ? "business_name" : "name");
        verify(dictionaries, never()).insert(any(FieldDictBinding.class));
    }

}
