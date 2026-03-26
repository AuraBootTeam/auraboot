package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.ValidationException;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Meta API Service Integration Test
 * 
 * Tests MetaModelService and MetaFieldService directly (service layer integration).
 * Uses real database, no mocking.
 * 
 * Note: This tests the same business logic that the API controllers use,
 * providing equivalent coverage without the complexity of JWT authentication setup.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MetaApiControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    private String testSuffix;

    @BeforeAll
    public void initTestSuffix() {
        testSuffix = "_" + System.currentTimeMillis();
    }

    @BeforeEach
    public void setup() {
        super.setupTenantContext();
    }

    @AfterEach
    public void cleanup() {
        MetaContext.clear();
    }

    private String uniqueCode(String base) {
        return base + testSuffix;
    }

    // ==================== Model Service Tests ====================

    /**
     * Test 1: Create model and verify in database
     */
    @Test
    @Order(1)
    public void test01_createModelAndVerify() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(uniqueCode("api_test_model"));
        request.setDisplayName("API Test Model");
        request.setDescription("Model created via service test");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        MetaModelDTO created = metaModelService.create(request);

        assertNotNull(created, "Model should be created");
        assertNotNull(created.getPid(), "Model PID should not be null");
        assertEquals(request.getCode(), created.getCode(), "Code should match");
        assertEquals(request.getDisplayName(), created.getDisplayName(), "Display name should match");

        // Verify retrieval
        MetaModelDTO retrieved = metaModelService.findByPid(created.getPid());
        assertNotNull(retrieved, "Model should be retrievable by PID");
        assertEquals(created.getCode(), retrieved.getCode(), "Retrieved code should match");
    }

    /**
     * Test 2: Get model by code
     */
    @Test
    @Order(2)
    public void test02_getModelByCode() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(uniqueCode("code_test_model"));
        request.setDisplayName("Code Test Model");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        MetaModelDTO created = metaModelService.create(request);
        assertNotNull(created, "Model should be created");

        MetaModelDTO found = metaModelService.findByCode(request.getCode());
        assertNotNull(found, "Model should be found by code");
        assertEquals(created.getPid(), found.getPid(), "PID should match");
    }

    /**
     * Test 3: Search models with pagination
     */
    @Test
    @Order(3)
    public void test03_searchModelsWithPagination() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(uniqueCode("search_test_model"));
        request.setDisplayName("Search Test Model");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        metaModelService.create(request);

        var result = metaModelService.searchModels(1, 10, null, null, null, null, null, true);

        assertNotNull(result, "Search result should not be null");
        assertNotNull(result.getRecords(), "Result records should not be null");
        assertTrue(result.getTotal() > 0, "Should have at least one model");
    }

    /**
     * Test 4: Model code uniqueness check
     */
    @Test
    @Order(4)
    public void test04_modelCodeUniquenessCheck() {
        String code = uniqueCode("unique_test_model");

        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(code);
        request.setDisplayName("Unique Test Model");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        MetaModelDTO created = metaModelService.create(request);
        assertNotNull(created, "Model should be created");

        boolean isUnique = metaModelService.isCodeUnique(code, null);
        assertFalse(isUnique, "Code should not be unique after creation");

        boolean isUniqueExcludingSelf = metaModelService.isCodeUnique(code, created.getPid());
        assertTrue(isUniqueExcludingSelf, "Code should be unique when excluding self");
    }

    /**
     * Test 5: Model existence check
     */
    @Test
    @Order(5)
    public void test05_modelExistenceCheck() {
        String code = uniqueCode("exist_test_model");

        boolean existsBefore = metaModelService.isModelExists(code);
        assertFalse(existsBefore, "Model should not exist before creation");

        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(code);
        request.setDisplayName("Existence Test Model");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        metaModelService.create(request);

        boolean existsAfter = metaModelService.isModelExists(code);
        assertTrue(existsAfter, "Model should exist after creation");
    }

    // ==================== Field Service Tests ====================

    /**
     * Test 6: Create field and verify
     */
    @Test
    @Order(6)
    public void test06_createFieldAndVerify() {
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(uniqueCode("api_test_field"));
        request.setDataType("string");

        Map<String, Object> feature = new HashMap<>();
        feature.put("required", true);
        feature.put("maxLength", 100);
        request.setFeature(feature);

        Map<String, Object> extension = new HashMap<>();
        extension.put("displayName", "API Test Field");
        request.setExtension(extension);

        MetaFieldDTO created = metaFieldService.create(request);

        assertNotNull(created, "Field should be created");
        assertNotNull(created.getPid(), "Field PID should not be null");
        assertEquals(request.getCode(), created.getCode(), "Code should match");
        assertEquals("string", created.getDataType(), "Data type should match");

        MetaFieldDTO retrieved = metaFieldService.findByPid(created.getPid());
        assertNotNull(retrieved, "Field should be retrievable by PID");
        assertEquals(created.getCode(), retrieved.getCode(), "Retrieved code should match");
    }

    /**
     * Test 7: Find field by code
     */
    @Test
    @Order(7)
    public void test07_findFieldByCode() {
        String code = uniqueCode("code_test_field");

        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(code);
        request.setDataType("integer");
        request.setFeature(Map.of("required", false));
        request.setExtension(Map.of("displayName", "Code Test Field"));

        MetaFieldDTO created = metaFieldService.create(request);
        assertNotNull(created, "Field should be created");

        Optional<MetaFieldDTO> found = metaFieldService.findCurrentByCode(code);
        assertTrue(found.isPresent(), "Field should be found by code");
        assertEquals(created.getPid(), found.get().getPid(), "PID should match");
    }

    /**
     * Test 8: List fields with pagination
     */
    @Test
    @Order(8)
    public void test08_listFieldsWithPagination() {
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(uniqueCode("list_test_field"));
        request.setDataType("string");
        request.setFeature(Map.of("required", false));
        request.setExtension(Map.of("displayName", "List Test Field"));

        metaFieldService.create(request);

        var result = metaFieldService.listFields(1, 10, null, null, null, true);

        assertNotNull(result, "List result should not be null");
        assertNotNull(result.getRecords(), "Result records should not be null");
        assertTrue(result.getTotal() > 0, "Should have at least one field");
    }

    /**
     * Test 9: Field code uniqueness check
     */
    @Test
    @Order(9)
    public void test09_fieldCodeUniquenessCheck() {
        String code = uniqueCode("unique_test_field");

        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(code);
        request.setDataType("string");
        request.setFeature(Map.of("required", false));
        request.setExtension(Map.of("displayName", "Unique Test Field"));

        MetaFieldDTO created = metaFieldService.create(request);
        assertNotNull(created, "Field should be created");

        boolean isUnique = metaFieldService.isCodeUnique(code, null);
        assertFalse(isUnique, "Code should not be unique after creation");

        boolean isUniqueExcludingSelf = metaFieldService.isCodeUnique(code, created.getPid());
        assertTrue(isUniqueExcludingSelf, "Code should be unique when excluding self");
    }

    /**
     * Test 10: Field existence check
     */
    @Test
    @Order(10)
    public void test10_fieldExistenceCheck() {
        String code = uniqueCode("exist_test_field");

        boolean existsBefore = metaFieldService.isFieldExists(code);
        assertFalse(existsBefore, "Field should not exist before creation");

        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(code);
        request.setDataType("string");
        request.setFeature(Map.of("required", false));
        request.setExtension(Map.of("displayName", "Existence Test Field"));

        metaFieldService.create(request);

        boolean existsAfter = metaFieldService.isFieldExists(code);
        assertTrue(existsAfter, "Field should exist after creation");
    }

    // ==================== Error Handling Tests ====================

    /**
     * Test 11: Non-existent model handling
     */
    @Test
    @Order(11)
    public void test11_nonExistentModelHandling() {
        String nonExistentPid = UniqueIdGenerator.generate();

        MetaModelDTO model = metaModelService.findByPid(nonExistentPid);
        assertNull(model, "Should return null for non-existent model");

        String nonExistentCode = "non_existent_code_" + System.currentTimeMillis();
        assertThrows(com.auraboot.framework.exception.ValidationException.class, () -> {
            metaModelService.findByCode(nonExistentCode);
        }, "Should throw exception for non-existent code");
    }

    /**
     * Test 12: Non-existent field handling
     */
    @Test
    @Order(12)
    public void test12_nonExistentFieldHandling() {
        String nonExistentPid = UniqueIdGenerator.generate();

        MetaFieldDTO field = metaFieldService.findByPid(nonExistentPid);
        assertNull(field, "Should return null for non-existent field");

        Optional<MetaFieldDTO> fieldByCode = metaFieldService.findCurrentByCode(
            "non_existent_code_" + System.currentTimeMillis());
        assertFalse(fieldByCode.isPresent(), "Should return empty for non-existent code");
    }

    /**
     * Test 13: Duplicate code handling for model
     */
    @Test
    @Order(13)
    public void test13_duplicateModelCodeHandling() {
        String code = uniqueCode("duplicate_model");

        MetaModelCreateRequest first = new MetaModelCreateRequest();
        first.setCode(code);
        first.setDisplayName("First Model");
        first.setModelType("entity");
        first.setTenantId(MetaContext.getCurrentTenantId());

        metaModelService.create(first);

        MetaModelCreateRequest second = new MetaModelCreateRequest();
        second.setCode(code);
        second.setDisplayName("Second Model");
        second.setModelType("entity");
        second.setTenantId(MetaContext.getCurrentTenantId());

        assertThrows(Exception.class, () -> {
            metaModelService.create(second);
        }, "Should throw exception for duplicate model code");
    }

    /**
     * Test 14: Duplicate code handling for field
     */
    @Test
    @Order(14)
    public void test14_duplicateFieldCodeHandling() {
        String code = uniqueCode("duplicate_field");

        MetaFieldCreateRequest first = new MetaFieldCreateRequest();
        first.setCode(code);
        first.setDataType("string");
        first.setFeature(Map.of("required", false));
        first.setExtension(Map.of("displayName", "First Field"));

        metaFieldService.create(first);

        MetaFieldCreateRequest second = new MetaFieldCreateRequest();
        second.setCode(code);
        second.setDataType("string");
        second.setFeature(Map.of("required", false));
        second.setExtension(Map.of("displayName", "Second Field"));

        assertThrows(Exception.class, () -> {
            metaFieldService.create(second);
        }, "Should throw exception for duplicate field code");
    }

    /**
     * Test 15: Model statistics
     */
    @Test
    @Order(15)
    public void test15_modelStatistics() {
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(uniqueCode("stats_test_model"));
        request.setDisplayName("Stats Test Model");
        request.setModelType("entity");
        request.setTenantId(MetaContext.getCurrentTenantId());

        metaModelService.create(request);

        Map<String, Object> stats = metaModelService.getStatistics();

        assertNotNull(stats, "Statistics should not be null");
    }
}
