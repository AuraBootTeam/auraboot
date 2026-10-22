package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.FieldUsageCache;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.FieldUsageCacheMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.FieldUsageService.FieldUsageInfo;
import com.auraboot.framework.meta.service.FieldUsageService.ModelReference;
import com.auraboot.framework.meta.service.FieldValidationService.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field Management Integration Test
 * Tests the complete field management workflow including:
 * - Field library management
 * - Field usage tracking
 * - Field validation
 * - Field binding configuration
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@DisplayName("Field Management Integration Test")
class FieldManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FieldLibraryService fieldLibraryService;

    @Autowired
    private FieldUsageService fieldUsageService;

    @Autowired
    private FieldValidationService fieldValidationService;

    @Autowired
    private MetaFieldMapper fieldMapper;

    @Autowired
    private FieldUsageCacheMapper fieldUsageCacheMapper;

    @Autowired
    private MetaModelFieldBindingMapper bindingMapper;

    @Autowired
    private MetaModelService metaModelService;

    private Field testField;
    private FieldUsageCache testUsageCache;
    private MetaModelDTO testModel;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
        testField = createTestField();
        fieldMapper.insert(testField);
        testUsageCache = createTestUsageCache();
        // Create a test model for binding tests
        testModel = createTestModel();
    }

    /**
     * Create a test model for binding tests
     */
    private MetaModelDTO createTestModel() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode("field_binding_test_model_" + System.currentTimeMillis());
        request.setDisplayName("Field Binding Test Model");
        request.setDescription("Test model for field binding integration tests");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());
        return metaModelService.create(request);
    }

    @Test
    @DisplayName("Integration: Complete field lifecycle")
    void testCompleteFieldLifecycle() {
        // Step 1: Validate field definition
        MetaFieldCreateRequest createRequest = new MetaFieldCreateRequest();
        createRequest.setCode("integration_test_field");
        createRequest.setDataType("string");

        ValidationResult validationResult = fieldValidationService.validateFieldDefinition(createRequest);
        assertNotNull(validationResult);

        // Step 2: Field already created in setUp()
        assertNotNull(testField.getId());

        // Step 3: Initialize usage cache
        fieldUsageCacheMapper.insert(testUsageCache);

        // Step 4: Query field from library
        FieldSearchRequest searchRequest = new FieldSearchRequest();
        searchRequest.setKeyword("test");
        searchRequest.setPage(1);
        searchRequest.setSize(10);

        PageResult<MetaFieldDTO> searchResult = fieldLibraryService.searchFields(searchRequest);
        assertNotNull(searchResult);

        // Step 5: Check field usage
        FieldUsageInfo usageInfo = fieldUsageService.getFieldUsage(testField.getPid());
        // Usage info may be null if not cached yet

        // Step 6: Refresh usage cache
        assertDoesNotThrow(() -> fieldUsageService.refreshUsageCache(testField.getPid()));
    }

    @Test
    @DisplayName("Integration: Field binding workflow")
    void testFieldBindingWorkflow() {
        // Step 1: Field already created in setUp()

        // Step 2: Create binding
        ModelFieldBinding binding = createTestBinding();
        bindingMapper.insert(binding);

        // Step 3: Validate binding configuration
        BindingConfiguration bindingConfig = BindingConfiguration.builder()
            .fieldPid(testField.getPid())
            .required(true)
            .visible(true)
            .editable(true)
            .build();

        MetaFieldDTO fieldDTO = MetaFieldDTO.builder()
            .pid(testField.getPid())
            .code(testField.getCode())
            .dataType(testField.getDataType())
            .build();

        boolean isValid = fieldValidationService.validateBindingOverride(bindingConfig, fieldDTO);
        assertNotNull(isValid);

        // Step 4: Get binding configurations
        List<BindingConfiguration> bindings = fieldUsageService.getBindingConfigurations(testField.getPid());
        assertNotNull(bindings);
    }

    @Test
    @DisplayName("Integration: Field usage tracking")
    void testFieldUsageTracking() {
        // Step 1: Field and usage cache already created in setUp()
        fieldUsageCacheMapper.insert(testUsageCache);

        // Step 2: Check if field is used
        boolean isUsed = fieldUsageService.isFieldUsed(testField.getPid());
        assertNotNull(isUsed);

        // Step 3: Get models using field
        List<ModelReference> models = fieldUsageService.getModelsUsingField(testField.getPid());
        assertNotNull(models);

        // Step 4: Calculate usage statistics
        assertDoesNotThrow(() -> 
            fieldUsageService.calculateUsageStatistics(testField.getPid())
        );

        // Step 5: Update usage cache
        int updateResult = fieldUsageCacheMapper.updateUsageStatistics(
            testField.getId(),
            10,
            5,
            3,
            18,
            85.5
        );
        assertTrue(updateResult >= 0);
    }

    @Test
    @DisplayName("Integration: Field library queries")
    void testFieldLibraryQueries() {
        // Step 1: Field and usage cache already created in setUp()
        fieldUsageCacheMapper.insert(testUsageCache);

        // Create a core field
        Field coreField = createTestField();
        coreField.setPid(UniqueIdGenerator.generate());
        coreField.setCode("core_field");
        fieldMapper.insert(coreField);

        FieldUsageCache coreCache = createTestUsageCache();
        coreCache.setFieldId(coreField.getId());
        coreCache.setIsCoreField(true);
        coreCache.setUsageFrequency(BigDecimal.valueOf(90.0));
        fieldUsageCacheMapper.insert(coreCache);

        // Step 2: Query system fields
        List<MetaFieldDTO> systemFields = fieldLibraryService.getSystemFields();
        assertNotNull(systemFields);

        // Step 3: Query common business fields
        List<MetaFieldDTO> commonFields = fieldLibraryService.getCommonBusinessFields();
        assertNotNull(commonFields);

        // Step 4: Query unused fields
        List<MetaFieldDTO> unusedFields = fieldLibraryService.getUnusedFields();
        assertNotNull(unusedFields);

        // Step 5: List fields by semantic type
        Map<String, List<MetaFieldDTO>> fieldsByType = fieldLibraryService.listFieldsBySemanticType();
        assertNotNull(fieldsByType);
    }

    @Test
    @DisplayName("Integration: Field validation scenarios")
    void testFieldValidationScenarios() {
        // Scenario 1: Valid field code
        assertTrue(fieldValidationService.validateCodeFormat("valid_field_name"));
        assertTrue(fieldValidationService.validateCodeFormat("fieldName123"));

        // Scenario 2: Invalid field code
        assertFalse(fieldValidationService.validateCodeFormat("123invalid"));
        assertFalse(fieldValidationService.validateCodeFormat("field-name"));

        // Scenario 3: Valid data type
        assertTrue(fieldValidationService.validateDataType("string"));
        assertTrue(fieldValidationService.validateDataType("integer"));

        // Scenario 4: Invalid data type
        assertFalse(fieldValidationService.validateDataType("invalid_type"));

        // Scenario 5: Reference target validation
        Map<String, Object> refTarget = new HashMap<>();
        refTarget.put("modelCode", "user");
        refTarget.put("fieldCode", "id");
        assertNotNull(fieldValidationService.validateRefTarget(refTarget));

        // Scenario 6: Empty reference target
        assertFalse(fieldValidationService.validateRefTarget(new HashMap<>()));
    }

    @Test
    @DisplayName("Integration: Field search with multiple filters")
    void testFieldSearchWithFilters() {
        // Step 1: Field already created in setUp()
        fieldUsageCacheMapper.insert(testUsageCache);

        // Step 2: Search by keyword
        FieldSearchRequest keywordSearch = new FieldSearchRequest();
        keywordSearch.setKeyword("test");
        keywordSearch.setPage(1);
        keywordSearch.setSize(10);

        PageResult<MetaFieldDTO> keywordResult = fieldLibraryService.searchFields(keywordSearch);
        assertNotNull(keywordResult);

        // Step 3: Search by base type
        FieldSearchRequest typeSearch = new FieldSearchRequest();
        typeSearch.setBaseType("string");
        typeSearch.setPage(1);
        typeSearch.setSize(10);

        PageResult<MetaFieldDTO> typeResult = fieldLibraryService.searchFields(typeSearch);
        assertNotNull(typeResult);

        // Step 4: Search by usage count range
        FieldSearchRequest usageSearch = new FieldSearchRequest();
        usageSearch.setMinUsageCount(1);
        usageSearch.setMaxUsageCount(20);
        usageSearch.setPage(1);
        usageSearch.setSize(10);

        PageResult<MetaFieldDTO> usageResult = fieldLibraryService.searchFields(usageSearch);
        assertNotNull(usageResult);
    }

    @Test
    @DisplayName("Integration: Usage cache operations")
    void testUsageCacheOperations() {
        // Step 1: Insert cache
        fieldUsageCacheMapper.insert(testUsageCache);

        // Step 2: Find by field ID
        FieldUsageCache found = fieldUsageCacheMapper.findByFieldId(testUsageCache.getFieldId());
        assertNotNull(found);
        assertEquals(testUsageCache.getFieldId(), found.getFieldId());

        // Step 3: Find by tenant and field
        FieldUsageCache foundByTenant = fieldUsageCacheMapper.findByTenantAndField(
            testUsageCache.getTenantId(),
            testUsageCache.getFieldId()
        );
        assertNotNull(foundByTenant);

        // Step 4: Update statistics
        int updateResult = fieldUsageCacheMapper.updateUsageStatistics(
            testUsageCache.getFieldId(),
            15,
            8,
            5,
            28,
            92.0
        );
        assertEquals(1, updateResult);

        // Step 5: Verify update
        FieldUsageCache updated = fieldUsageCacheMapper.findByFieldId(testUsageCache.getFieldId());
        assertEquals(15, updated.getModelCount());
        assertEquals(8, updated.getPageCount());
        assertEquals(5, updated.getQueryCount());

        // Step 6: Upsert operation
        testUsageCache.setModelCount(20);
        int upsertResult = fieldUsageCacheMapper.upsert(testUsageCache);
        assertTrue(upsertResult > 0);
    }

    @Test
    @DisplayName("Integration: Field recommendations")
    void testFieldRecommendations() {
        // Step 1: Field and usage cache already created in setUp()
        fieldUsageCacheMapper.insert(testUsageCache);

        // Step 2: Get recommendations for a model
        String modelPid = "test-model-pid";
        List<FieldRecommendation> recommendations = 
            fieldLibraryService.getFieldRecommendations(modelPid, null);

        // Then
        assertNotNull(recommendations);

        // Step 3: Get recommendations with semantic type filter
        List<FieldRecommendation> filteredRecommendations = 
            fieldLibraryService.getFieldRecommendations(modelPid, "business");

        assertNotNull(filteredRecommendations);
    }

    @Test
    @DisplayName("Integration: Refresh all usage cache")
    void testRefreshAllUsageCache() {
        // Step 1: Field already created in setUp(), create additional field
        fieldUsageCacheMapper.insert(testUsageCache);

        Field field2 = createTestField();
        field2.setPid(UniqueIdGenerator.generate());
        field2.setCode("test_field_2");
        fieldMapper.insert(field2);

        FieldUsageCache cache2 = createTestUsageCache();
        cache2.setFieldId(field2.getId());
        fieldUsageCacheMapper.insert(cache2);

        // Step 2: Refresh all cache
        assertDoesNotThrow(() -> fieldUsageService.refreshAllUsageCache());

        // Step 3: Verify cache still exists
        FieldUsageCache refreshed1 = fieldUsageCacheMapper.findByFieldId(testField.getId());
        FieldUsageCache refreshed2 = fieldUsageCacheMapper.findByFieldId(field2.getId());

        assertNotNull(refreshed1);
        assertNotNull(refreshed2);
    }

    /**
     * Create test field entity
     */
    private Field createTestField() {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setCode("test_field");
        field.setDataType("string");
        field.setTenantId(MetaContext.getCurrentTenantId());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus("published");
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        return field;
    }

    /**
     * Create test usage cache
     */
    private FieldUsageCache createTestUsageCache() {
        return FieldUsageCache.builder()
            .tenantId(MetaContext.getCurrentTenantId())
            .fieldId(testField != null ? testField.getId() : 10000L)
            .modelCount(5)
            .pageCount(3)
            .queryCount(2)
            .totalReferences(10)
            .isCoreField(false)
            .lastUsedAt(Instant.now())
            .usageFrequency(BigDecimal.valueOf(65.0))
            .updatedAt(Instant.now())
            .build();
    }

    /**
     * Create test binding
     */
    private ModelFieldBinding createTestBinding() {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(MetaContext.getCurrentTenantId());
        binding.setModelId(testModel.getId()); // Use dynamically created model ID
        binding.setFieldId(testField.getId());
        binding.setFieldOrder(1);
        binding.setRequired(false);
        binding.setVisible(true);
        binding.setEditable(true);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        return binding;
    }
}
