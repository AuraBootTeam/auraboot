package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRuleSchemaBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaFieldDictBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.meta.validator.MetaFieldValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Published schema reads use the canonical assembler and never bind fields or perform DDL. */
class PublishedFieldDefinitionTest {
    private final MetaFieldMapper mapper = mock(MetaFieldMapper.class);
    private final MetaModelFieldBindingMapper bindings = mock(MetaModelFieldBindingMapper.class);
    private final ModelFieldBindingService bindingService = mock(ModelFieldBindingService.class);
    private final MetaFieldValidator validator = mock(MetaFieldValidator.class);
    private final MetaFieldServiceImpl service = new MetaFieldServiceImpl(mapper, mock(DictService.class),
            mock(ApplicationEventPublisher.class), mock(TransactionTemplate.class), new ExtensionConverter(),
            validator, mock(MetaFieldDictBindingMapper.class), bindings,
            bindingService, new ObjectMapper(), null);

    @BeforeEach void tenant() { MetaContext.setContext(101L, 1L, "test", "tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    private Field field() {
        var field = new Field(); field.setPid("field_capacity_v2"); field.setCode("capacity");
        field.setTenantId(101L); field.setVersion(2); field.setStatus("published"); field.setDataType("decimal");
        var feature = new FieldFeatureBean(); feature.setRequired(true); feature.setSearchable(true); field.setFeature(feature);
        field.setExtension(new ExtensionConverter().toBean(Map.of("displayName", "Capacity", "constraints",
                Map.of("min", 1, "max", 2000, "precision", 5, "scale", 2))));
        return field;
    }

    @Test void resolvesOnlyTheRequestedVersionWithCanonicalConstraintsAndNoWrites() {
        var entity = field(); when(mapper.findByCodeAndVersion("capacity", 2)).thenReturn(entity);
        var result = service.resolvePublishedDefinition("capacity", 2).orElseThrow();
        assertEquals(101L, result.tenantId()); assertEquals("field_capacity_v2", result.pid());
        assertEquals(2, result.version()); assertEquals("capacity", result.code());
        assertEquals("Capacity", result.definition().getDisplayName());
        assertEquals(1, result.definition().getMinValue()); assertEquals(2000, result.definition().getMaxValue());
        assertEquals(5, result.definition().getPrecision()); assertEquals(2, result.definition().getScale());
        assertTrue(result.definition().isRequired());
        result.definition().setRequired(false); assertTrue(entity.getFeature().getRequired());
        verify(mapper).findByCodeAndVersion("capacity", 2); verifyNoMoreInteractions(mapper);
        verifyNoInteractions(bindings, bindingService);
    }

    @Test void rejectsForeignDraftMismatchedOrMissingVersions() {
        when(mapper.findByCodeAndVersion("capacity", 2)).thenReturn(null);
        assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        var entity = field(); when(mapper.findByCodeAndVersion("capacity", 2)).thenReturn(entity);
        entity.setTenantId(102L); assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        entity.setTenantId(101L); entity.setStatus("draft"); assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        entity.setStatus("published"); entity.setVersion(3); assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        entity.setVersion(2); entity.setCode("other"); assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        entity.setCode("capacity"); entity.setDeletedFlag(true); assertTrue(service.resolvePublishedDefinition("capacity", 2).isEmpty());
        verifyNoInteractions(bindings, bindingService);
    }

    @Test void rejectsMissingTenantAndInvalidSelectorsBeforeQuerying() {
        assertThrows(IllegalArgumentException.class, () -> service.resolvePublishedDefinition(" ", 2));
        assertThrows(IllegalArgumentException.class, () -> service.resolvePublishedDefinition("capacity", 0));
        MetaContext.setContext(null, 1L, "test", "tester");
        assertThrows(IllegalStateException.class, () -> service.resolvePublishedDefinition("capacity", 2));
        verifyNoInteractions(mapper, bindings, bindingService);
    }

    @Test void refusesUntranslatedRulesRatherThanSilentlyDiscardingThem() {
        var entity = field(); var rules = new FieldRuleSchemaBean(); var rule = new FieldRuleSchemaBean.ValidationRule();
        rule.setType("custom"); rule.setValue("untranslated"); rules.setValidationRules(List.of(rule)); entity.setRuleSchema(rules);
        when(mapper.findByCodeAndVersion("capacity", 2)).thenReturn(entity);
        assertThrows(UnsupportedOperationException.class, () -> service.resolvePublishedDefinition("capacity", 2));
        verifyNoInteractions(bindings, bindingService);
    }

    @Test void nativeCreatePersistsDeclaredRulesAndExposesThemForFailClosedResolution() {
        var request = new com.auraboot.framework.meta.dto.MetaFieldCreateRequest();
        request.setCode("capacity"); request.setDataType("decimal"); request.setAutoPublish(true);
        request.setRuleSchema(Map.of("validationRules", List.of(Map.of("type", "custom", "value", "declared"))));
        when(validator.validateCreateRequest(request)).thenReturn(new com.auraboot.framework.meta.dto.MetaFieldValidationResult());
        when(mapper.getNextVersion("capacity")).thenReturn(1);
        var result = service.create(request);
        var inserted = org.mockito.ArgumentCaptor.forClass(Field.class); verify(mapper).insert(inserted.capture());
        assertNotNull(inserted.getValue().getRuleSchema(), "Declared rules cannot disappear before publication");
        assertNotNull(result.getRuleSchema(), "Definition reads must disclose declared rules");
        when(mapper.findByCodeAndVersion("capacity", 1)).thenReturn(inserted.getValue());
        assertThrows(UnsupportedOperationException.class, () -> service.resolvePublishedDefinition("capacity", 1));
        verifyNoInteractions(bindings, bindingService);
    }

    @Test void nativeVersionUpdateRetainsOmittedDefinitionsWithoutChangingPublishedMeaning() {
        var existing = field(); var rules = new FieldRuleSchemaBean();
        rules.setExtensions(Map.of("declared", true)); existing.setRuleSchema(rules);
        when(mapper.selectByPidWithContext(existing.getPid(), 101L)).thenReturn(existing);
        when(mapper.getNextVersion("capacity")).thenReturn(3);
        var request = new com.auraboot.framework.meta.dto.MetaFieldUpdateRequest(); request.setDataType("decimal");
        request.setUiSchema(Map.of("widget", "decimal"));
        when(validator.validateUpdateRequest(null, request)).thenReturn(new com.auraboot.framework.meta.dto.MetaFieldValidationResult());
        service.update(existing.getPid(), request);
        var inserted = org.mockito.ArgumentCaptor.forClass(Field.class); verify(mapper).insert(inserted.capture());
        var next = inserted.getValue(); assertEquals(3, next.getVersion()); assertEquals("draft", next.getStatus());
        assertNotNull(next.getFeature(), "Omitted required/search settings must survive versioning");
        assertTrue(next.getFeature().getRequired()); assertNotNull(next.getExtension());
        assertNotNull(next.getRuleSchema()); assertTrue(existing.getFeature().getRequired());
        assertEquals(2, existing.getVersion()); assertEquals("published", existing.getStatus());
        verifyNoInteractions(bindings, bindingService);
    }
}
