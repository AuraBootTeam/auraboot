package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.payload.ComputedFieldOverride;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.ViewModelConfig;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ViewModelServiceImpl Unit Tests.
 * Tests three-layer field resolution, query proxying, and validation logic.
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewModelServiceImpl Unit Tests")
class ViewModelServiceImplTest {

    @Mock
    private MetaModelMapper metaModelMapper;

    @Mock
    private MetaFieldMapper metaFieldMapper;

    @Mock
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Mock
    private NamedQueryFieldMapper namedQueryFieldMapper;

    @Mock
    private NamedQueryService namedQueryService;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ViewModelServiceImpl viewModelService;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // ObjectMapper.convertValue is used to parse ViewModelConfig
        lenient().when(objectMapper.convertValue(any(Map.class), eq(ViewModelConfig.class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> map = invocation.getArgument(0);
                    ViewModelConfig config = new ViewModelConfig();
                    config.setMode((String) map.get("mode"));
                    config.setBaseEntityCode((String) map.get("baseEntityCode"));
                    config.setNamedQueryCode((String) map.get("namedQueryCode"));
                    if (map.get("excludeFields") != null) {
                        config.setExcludeFields((List<String>) map.get("excludeFields"));
                    }
                    if (map.get("computedFields") != null) {
                        // For simplicity, convert each entry manually
                        Map<String, Object> cfMap = (Map<String, Object>) map.get("computedFields");
                        Map<String, ComputedFieldOverride> computed = new HashMap<>();
                        for (Map.Entry<String, Object> entry : cfMap.entrySet()) {
                            Map<String, Object> overrideMap = (Map<String, Object>) entry.getValue();
                            ComputedFieldOverride override = new ComputedFieldOverride();
                            override.setExpression((String) overrideMap.get("expression"));
                            override.setReturnType((String) overrideMap.get("returnType"));
                            override.setLabel((String) overrideMap.get("label"));
                            override.setVirtual((Boolean) overrideMap.get("virtual"));
                            computed.put(entry.getKey(), override);
                        }
                        config.setComputedFields(computed);
                    }
                    return config;
                });
    }

    // ==================== Helper Methods ====================

    private Model createViewModel(String code, Map<String, Object> viewModelConfig) {
        Model model = new Model();
        model.setId(100L);
        model.setCode(code);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("modelType", "view");
        extMap.put("displayName", "Test View");
        extMap.put("description", "Test description");
        extMap.put("viewModel", viewModelConfig);
        ext.setExtension(extMap);
        model.setExtension(ext);
        model.setStatus("published");

        return model;
    }

    private Model createBaseEntity(String code) {
        Model model = new Model();
        model.setId(200L);
        model.setCode(code);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("modelType", "entity");
        extMap.put("displayName", "Base Entity");
        ext.setExtension(extMap);
        model.setExtension(ext);

        return model;
    }

    private Field createField(Long id, String code, String dataType) {
        Field field = new Field();
        field.setId(id);
        field.setCode(code);
        field.setDataType(dataType);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code + " Display");
        ext.setExtension(extMap);
        field.setExtension(ext);

        return field;
    }

    private ModelFieldBinding createBinding(Long fieldId, int order) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        binding.setRequired(false);
        binding.setVisible(true);
        binding.setEditable(true);
        return binding;
    }

    // ==================== Tests ====================

    @Nested
    @DisplayName("resolveViewFields - Inherit Mode")
    class InheritModeTests {

        @Test
        @DisplayName("Should resolve fields from base entity bindings")
        void testResolveInheritFields_basic() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                // Setup view model
                Map<String, Object> vmConfig = Map.of(
                        "mode", "inherit",
                        "baseEntityCode", "order"
                );
                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                // Setup base entity
                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                // Setup bindings
                ModelFieldBinding binding1 = createBinding(1L, 0);
                ModelFieldBinding binding2 = createBinding(2L, 1);
                when(fieldBindingMapper.findByModelId(200L)).thenReturn(List.of(binding1, binding2));

                // Setup fields
                Field field1 = createField(1L, "name", "string");
                Field field2 = createField(2L, "amount", "decimal");
                when(metaFieldMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(field1, field2));

                // Execute
                List<ResolvedFieldDTO> result = viewModelService.resolveViewFields("order_view");

                // Verify
                assertEquals(2, result.size());
                assertEquals("name", result.get(0).getCode());
                assertEquals("string", result.get(0).getDataType());
                assertEquals("field_binding", result.get(0).getSourceType());
                assertEquals("amount", result.get(1).getCode());
                assertEquals("decimal", result.get(1).getDataType());

                log.info("Inherit mode resolved {} fields", result.size());
            }
        }

        @Test
        @DisplayName("Should exclude fields specified in excludeFields")
        void testResolveInheritFields_withExclusion() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> vmConfig = new HashMap<>();
                vmConfig.put("mode", "inherit");
                vmConfig.put("baseEntityCode", "order");
                vmConfig.put("excludeFields", List.of("internal_notes"));

                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                ModelFieldBinding binding1 = createBinding(1L, 0);
                ModelFieldBinding binding2 = createBinding(2L, 1);
                when(fieldBindingMapper.findByModelId(200L)).thenReturn(List.of(binding1, binding2));

                Field field1 = createField(1L, "name", "string");
                Field field2 = createField(2L, "internal_notes", "text");
                when(metaFieldMapper.selectBatchIds(List.of(1L, 2L))).thenReturn(List.of(field1, field2));

                List<ResolvedFieldDTO> result = viewModelService.resolveViewFields("order_view");

                assertEquals(1, result.size());
                assertEquals("name", result.get(0).getCode());

                log.info("Excluded 'internal_notes', got {} fields", result.size());
            }
        }

        @Test
        @DisplayName("Should apply Layer 3 computed overrides to existing fields")
        void testResolveInheritFields_withComputedOverride() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> computedFields = new HashMap<>();
                computedFields.put("amount", Map.of(
                        "expression", "quantity * unit_price",
                        "label", "Total Amount"
                ));

                Map<String, Object> vmConfig = new HashMap<>();
                vmConfig.put("mode", "inherit");
                vmConfig.put("baseEntityCode", "order");
                vmConfig.put("computedFields", computedFields);

                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                ModelFieldBinding binding = createBinding(1L, 0);
                when(fieldBindingMapper.findByModelId(200L)).thenReturn(List.of(binding));

                Field field = createField(1L, "amount", "decimal");
                when(metaFieldMapper.selectBatchIds(List.of(1L))).thenReturn(List.of(field));

                List<ResolvedFieldDTO> result = viewModelService.resolveViewFields("order_view");

                assertEquals(1, result.size());
                assertEquals("Total Amount", result.get(0).getDisplayName());
                assertEquals("quantity * unit_price", result.get(0).getComputeExpression());

                log.info("Layer 3 override applied: displayName={}", result.get(0).getDisplayName());
            }
        }

        @Test
        @DisplayName("Should add virtual-only computed fields")
        void testResolveInheritFields_withVirtualField() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> computedFields = new HashMap<>();
                computedFields.put("profit_margin", Map.of(
                        "expression", "(revenue - cost) / revenue * 100",
                        "returnType", "decimal",
                        "label", "Profit Margin %",
                        "virtual", true
                ));

                Map<String, Object> vmConfig = new HashMap<>();
                vmConfig.put("mode", "inherit");
                vmConfig.put("baseEntityCode", "order");
                vmConfig.put("computedFields", computedFields);

                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                when(fieldBindingMapper.findByModelId(200L)).thenReturn(List.of());

                List<ResolvedFieldDTO> result = viewModelService.resolveViewFields("order_view");

                assertEquals(1, result.size());
                assertEquals("profit_margin", result.get(0).getCode());
                assertEquals("computed_only", result.get(0).getSourceType());
                assertTrue(result.get(0).getVirtual());
                assertEquals("decimal", result.get(0).getReturnType());

                log.info("Virtual field added: code={}", result.get(0).getCode());
            }
        }
    }

    @Nested
    @DisplayName("resolveViewFields - Compose/Free Mode")
    class ComposeModeTests {

        @Test
        @DisplayName("Should resolve fields from named query")
        void testResolveComposeFields_basic() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> vmConfig = Map.of(
                        "mode", "compose",
                        "namedQueryCode", "product_summary"
                );
                Model viewModel = createViewModel("product_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("product_view")).thenReturn(viewModel);

                NamedQueryField nqf1 = new NamedQueryField(TENANT_ID, "product_summary", "product_name", "p.name", "string");
                NamedQueryField nqf2 = new NamedQueryField(TENANT_ID, "product_summary", "total_sales", "SUM(s.amount)", "decimal");
                when(namedQueryFieldMapper.findByQueryCode(TENANT_ID, "product_summary"))
                        .thenReturn(List.of(nqf1, nqf2));

                List<ResolvedFieldDTO> result = viewModelService.resolveViewFields("product_view");

                assertEquals(2, result.size());
                assertEquals("product_name", result.get(0).getCode());
                assertEquals("named_query_field", result.get(0).getSourceType());
                assertEquals("total_sales", result.get(1).getCode());

                log.info("Compose mode resolved {} fields from named query", result.size());
            }
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should throw when model not found")
        void testResolveViewFields_modelNotFound() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                when(metaModelMapper.findCurrentByCode("nonexistent")).thenReturn(null);

                assertThrows(MetaServiceException.class, () ->
                        viewModelService.resolveViewFields("nonexistent")
                );
            }
        }

        @Test
        @DisplayName("Should throw when model is not VIEW type")
        void testResolveViewFields_notViewType() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Model entityModel = createBaseEntity("regular_entity");
                when(metaModelMapper.findCurrentByCode("regular_entity")).thenReturn(entityModel);

                assertThrows(MetaServiceException.class, () ->
                        viewModelService.resolveViewFields("regular_entity")
                );
            }
        }

        @Test
        @DisplayName("Should throw when extension has no viewModel config")
        void testResolveViewFields_missingViewModelConfig() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Model viewModel = new Model();
                viewModel.setCode("broken_view");
                ExtensionBean ext = new ExtensionBean();
                Map<String, Object> extMap = new HashMap<>();
                extMap.put("modelType", "view");
                // No "viewModel" key
                ext.setExtension(extMap);
                viewModel.setExtension(ext);

                when(metaModelMapper.findCurrentByCode("broken_view")).thenReturn(viewModel);

                assertThrows(MetaServiceException.class, () ->
                        viewModelService.resolveViewFields("broken_view")
                );
            }
        }
    }

    @Nested
    @DisplayName("validateConfig")
    class ValidateConfigTests {

        @Test
        @DisplayName("Should return valid for correct inherit config")
        void testValidateConfig_validInherit() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> vmConfig = Map.of(
                        "mode", "inherit",
                        "baseEntityCode", "order"
                );
                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                ViewModelValidationResult result = viewModelService.validateConfig("order_view");

                assertTrue(result.getValid());
                assertTrue(result.getErrors().isEmpty());
            }
        }

        @Test
        @DisplayName("Should return errors when base entity not found")
        void testValidateConfig_baseEntityNotFound() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> vmConfig = Map.of(
                        "mode", "inherit",
                        "baseEntityCode", "nonexistent_entity"
                );
                Model viewModel = createViewModel("bad_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("bad_view")).thenReturn(viewModel);
                when(metaModelMapper.findCurrentByCode("nonexistent_entity")).thenReturn(null);

                ViewModelValidationResult result = viewModelService.validateConfig("bad_view");

                assertFalse(result.getValid());
                assertFalse(result.getErrors().isEmpty());
                assertTrue(result.getErrors().get(0).contains("not found"));
            }
        }
    }

    @Nested
    @DisplayName("getSummary")
    class GetSummaryTests {

        @Test
        @DisplayName("Should return correct summary for inherit mode")
        void testGetSummary_inheritMode() {
            try (MockedStatic<MetaContext> mockedContext = mockStatic(MetaContext.class)) {
                mockedContext.when(MetaContext::exists).thenReturn(true);
                mockedContext.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
                mockedContext.when(MetaContext::getCurrentUserId).thenReturn(USER_ID);

                Map<String, Object> vmConfig = Map.of(
                        "mode", "inherit",
                        "baseEntityCode", "order"
                );
                Model viewModel = createViewModel("order_view", vmConfig);
                when(metaModelMapper.findCurrentByCode("order_view")).thenReturn(viewModel);

                Model baseEntity = createBaseEntity("order");
                when(metaModelMapper.findCurrentByCode("order")).thenReturn(baseEntity);

                when(fieldBindingMapper.findByModelId(200L)).thenReturn(List.of());

                ViewModelSummaryDTO summary = viewModelService.getSummary("order_view");

                assertEquals("order_view", summary.getCode());
                assertEquals("Test View", summary.getDisplayName());
                assertEquals("inherit", summary.getMode());
                assertEquals("order", summary.getBaseEntityCode());
                assertEquals(0, summary.getFieldCount());
            }
        }
    }
}
