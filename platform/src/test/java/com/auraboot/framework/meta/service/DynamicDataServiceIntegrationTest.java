package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamicDataService Integration Test
 *
 * Test scope:
 * 1. Basic CRUD operations (create, read, update, delete)
 * 2. Batch operations
 * 3. Data validation
 * 4. Tenant isolation
 * 5. Pagination
 *
 * Uses real database, no mocking.
 * 
 * Note: This test class does NOT use @Transactional because:
 * 1. Physical table creation (DDL) requires committed transactions
 * 2. Tests need to share the same model/table across test methods
 * 3. Each test manages its own data cleanup
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)  // Share instance across all tests
class DynamicDataServiceIntegrationTest {

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    // Test model - unique per test run
    private String testModelCode;
    private String testTableName;
    private Model testModel;
    private final List<String> createdRecordPids = Collections.synchronizedList(new ArrayList<>());
    private boolean modelInitialized = false;
    
    // Test context data
    private User testUser;
    private Tenant testTenant;

    @BeforeAll
    void setupTestModel() {
        // Use unique model code for each test run to avoid conflicts
        testModelCode = "dyn_test_" + System.currentTimeMillis();
        testTableName = "mt_" + testModelCode.toLowerCase();
        modelInitialized = false;
        createdRecordPids.clear();
        testModel = null;
        testUser = null;
        testTenant = null;
    }

    @BeforeEach
    void ensureModelExists() {
        // Setup tenant context first
        setupTenantContext();

        if (!modelInitialized) {
            try {
                // Clean up any existing test model with same code (from previous failed runs)
                cleanupExistingTestModel();
                
                // Create fresh model and fields for this test run
                createTestModel();
                createTestFields();
                createPhysicalTable();
                modelInitialized = true;
            } catch (Exception e) {
                log.error("Failed to initialize test model", e);
                throw new RuntimeException("Failed to initialize test model", e);
            }
        }
    }
    
    /**
     * Clean up any existing test model with the same code from previous test runs
     */
    private void cleanupExistingTestModel() {
        try {
            // Delete model field bindings
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                testModelCode, testTenant.getId()
            );
            
            // Delete fields created for this model (by code pattern)
            jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE code IN ('name', 'status', 'pid') AND tenant_id = ?"
                , testTenant.getId()
            );
            
            // Delete the model
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                testModelCode, testTenant.getId()
            );
            
            // Drop the physical table if it exists
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + testTableName);
            } catch (Exception e) {
                log.debug("Table {} does not exist or could not be dropped", testTableName);
            }
            
            log.info("Cleaned up existing test model: {}", testModelCode);
        } catch (Exception e) {
            log.debug("No existing test model to clean up: {}", e.getMessage());
        }
    }

    @AfterAll
    void cleanup() {
        log.info("=== Test cleanup ===");
        modelInitialized = false;
        createdRecordPids.clear();
    }

    @AfterEach
    void cleanupRecords() {
        // Ensure tenant context is set for cleanup
        if (testTenant != null && testUser != null) {
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        }
        
        // Clean up records created in this test using pid
        for (String pid : new ArrayList<>(createdRecordPids)) {
            try {
                dynamicDataService.delete(testModelCode, pid);
            } catch (Exception e) {
                log.warn("Failed to cleanup record with pid: {}", pid, e);
            }
        }
        createdRecordPids.clear();
    }

    /**
     * Setup tenant context for tests
     */
    private void setupTenantContext() {
        try {
            // Create or find test user
            if (testUser == null) {
                String testEmail = "dynamic-data-test@auraboot.com";
                testUser = userService.findByEmail(testEmail);
                if (testUser == null) {
                    testUser = userService.signUp(testEmail, "test-password-123");
                }
            }

            // Create or find test tenant
            if (testTenant == null) {
                String testTenantName = "dynamic-data-test-tenant";
                testTenant = tenantService.findByName(testTenantName);
                if (testTenant == null) {
                    Tenant tenant = new Tenant();
                    tenant.setPid(UniqueIdGenerator.generate());
                    tenant.setName(testTenantName);
                    tenant.setDisplayName("Dynamic Data Test Tenant");
                    tenant.setStatus("active");
                    tenant.setContactEmail("admin@dynamic-test.com");
                    tenant.setDescription("Test tenant for DynamicDataService integration tests");
                    tenant.setDeletedFlag(false);
                    tenant.setCreatedAt(Instant.now());
                    tenant.setUpdatedAt(Instant.now());
                    testTenant = tenantService.createTenant(tenant);
                }
            }

            // Ensure tenant member relationship exists
            TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
            if (member == null) {
                tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
            }

            // Set MetaContext
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup tenant context", e);
        }
    }

    // ==================== Test 1: Basic Create ====================

    @Test
    @Order(1)
    @DisplayName("测试1: 创建记录 - 基本功能")
    void test01_createRecord() {
        log.info("=== Test 1: Create record ===");

        // Given - use name and status fields, let system generate id/pid
        Map<String, Object> data = new HashMap<>();
        data.put("name", "测试记录-1");
        data.put("status", "active");

        // When
        Map<String, Object> result = dynamicDataService.create(testModelCode, data);

        // Then
        assertNotNull(result, "Create result should not be null");
        assertNotNull(result.get("pid"), "pid should be generated");
        
        String pid = result.get("pid").toString();
        createdRecordPids.add(pid);

        // Verify record can be queried
        Map<String, Object> queried = dynamicDataService.getById(testModelCode, pid);
        assertNotNull(queried);
        assertEquals(pid, queried.get("pid"));
        assertEquals("测试记录-1", queried.get("name"));

        log.info("✓ Record created successfully with pid: {}", pid);
    }

    // ==================== Test 2: Pagination ====================

    @Test
    @Order(2)
    @DisplayName("测试2: 分页查询记录")
    void test02_listRecordsWithPagination() {
        log.info("=== Test 2: Pagination ===");

        // Given: Create 3 test records
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", "列表记录-" + i);
            data.put("status", "active");

            Map<String, Object> created = dynamicDataService.create(testModelCode, data);
            createdRecordPids.add(created.get("pid").toString());

            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }

        // When
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        // Then
        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getRecords().size() >= 3, "Should return at least 3 records");
        assertTrue(result.getTotal() >= 3, "Total should be at least 3");

        log.info("✓ Pagination successful: {} records, total {}", result.getRecords().size(), result.getTotal());
    }

    // ==================== Test 3: Get by ID ====================

    @Test
    @Order(3)
    @DisplayName("测试3: 通过ID查询单条记录")
    void test03_getRecordById() {
        log.info("=== Test 3: Get by ID ===");

        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("name", "单条查询测试");
        data.put("status", "active");

        Map<String, Object> created = dynamicDataService.create(testModelCode, data);
        String pid = created.get("pid").toString();
        createdRecordPids.add(pid);

        // When
        Map<String, Object> result = dynamicDataService.getById(testModelCode, pid);

        // Then
        assertNotNull(result);
        assertEquals(pid, result.get("pid"));
        assertEquals("单条查询测试", result.get("name"));

        log.info("✓ Get by ID successful: {}", pid);
    }

    // ==================== Test 4: Update ====================

    @Test
    @Order(4)
    @DisplayName("测试4: 更新记录")
    void test04_updateRecord() {
        log.info("=== Test 4: Update record ===");

        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("name", "更新前");
        data.put("status", "draft");

        Map<String, Object> created = dynamicDataService.create(testModelCode, data);
        String pid = created.get("pid").toString();
        createdRecordPids.add(pid);

        // When
        Map<String, Object> updateData = new HashMap<>();
        updateData.put("name", "更新后");
        updateData.put("status", "published");

        Map<String, Object> result = dynamicDataService.update(testModelCode, pid, updateData);

        // Then
        assertNotNull(result);

        Map<String, Object> updated = dynamicDataService.getById(testModelCode, pid);
        assertEquals("更新后", updated.get("name"));
        assertEquals("published", updated.get("status"));

        log.info("✓ Update successful: {}", pid);
    }

    // ==================== Test 5: Delete ====================

    @Test
    @Order(5)
    @DisplayName("测试5: 删除记录")
    void test05_deleteRecord() {
        log.info("=== Test 5: Delete record ===");

        // Given
        Map<String, Object> data = new HashMap<>();
        data.put("name", "待删除记录");
        data.put("status", "active");

        Map<String, Object> created = dynamicDataService.create(testModelCode, data);
        String pid = created.get("pid").toString();

        // When
        dynamicDataService.delete(testModelCode, pid);

        // Then
        assertThrows(Exception.class, () -> {
            dynamicDataService.getById(testModelCode, pid);
        }, "Query after delete should throw exception");

        log.info("✓ Delete successful: {}", pid);
    }

    // ==================== Test 6: Batch Create ====================

    @Test
    @Order(6)
    @DisplayName("测试6: 批量创建记录")
    void test06_batchCreate() {
        log.info("=== Test 6: Batch create ===");

        // Given
        List<Map<String, Object>> batchData = new ArrayList<>();

        for (int i = 1; i <= 5; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", "批量记录-" + i);
            data.put("status", "active");
            batchData.add(data);
        }

        // When
        DynamicBatchResponse result = dynamicDataService.batchCreate(testModelCode, batchData);

        // Then
        assertNotNull(result);
        assertEquals(5, result.getTotal());
        assertTrue(result.getSuccess() >= 4, "At least 4 should succeed");
        
        // Track created records for cleanup
        if (result.getSuccessItems() != null) {
            for (Map<String, Object> r : result.getSuccessItems()) {
                if (r != null && r.get("pid") != null) {
                    createdRecordPids.add(r.get("pid").toString());
                }
            }
        }

        log.info("✓ Batch create successful: {}/{}", result.getSuccess(), result.getTotal());
    }

    // ==================== Test 7: Required Field Validation ====================

    @Test
    @Order(7)
    @DisplayName("测试7: 必填字段验证")
    void test07_requiredFieldValidation() {
        log.info("=== Test 7: Required field validation ===");

        // Given: Missing required field 'name'
        Map<String, Object> invalidData = new HashMap<>();
        invalidData.put("status", "active");
        // Missing 'name' field which is required

        // When & Then: Should throw exception for missing required field
        com.auraboot.framework.meta.exception.ValidationException exception =
            assertThrows(com.auraboot.framework.meta.exception.ValidationException.class, () -> {
            dynamicDataService.create(testModelCode, invalidData);
        }, "Creating record without required field 'name' should throw exception");
        
        log.info("✓ Required field validation passed - exception thrown: {}", exception.getMessage());
        assertTrue(
            exception.getMessage().contains("name") || 
            exception.getMessage().contains("required"),
            "Exception should indicate missing required field"
        );
    }

    // ==================== Test 8: Tenant Isolation - List ====================

    @Test
    @Order(8)
    @DisplayName("测试8: 租户隔离 - List查询只返回当前租户数据")
    void test08_tenantIsolation_List() {
        log.info("=== Test 8: Tenant isolation - List ===");

        // Given: Create current tenant's record
        Map<String, Object> myData = new HashMap<>();
        myData.put("name", "我的租户记录");
        myData.put("status", "active");

        Map<String, Object> created = dynamicDataService.create(testModelCode, myData);
        String myPid = created.get("pid").toString();
        createdRecordPids.add(myPid);

        // Insert other tenant's record directly (bypass service layer)
        String otherPid = UniqueIdGenerator.generate();
        insertOtherTenantRecord(otherPid, "其他租户记录");

        // When
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(100)
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        // Then
        assertNotNull(result);
        assertTrue(result.getRecords().stream().anyMatch(r -> myPid.equals(r.get("pid"))),
                "Should contain current tenant's record");
        assertFalse(result.getRecords().stream().anyMatch(r -> otherPid.equals(r.get("pid"))),
                "Should not contain other tenant's record");

        // Cleanup
        cleanupOtherTenantRecord(otherPid);

        log.info("✓ Tenant isolation verified - List");
    }

    // ==================== Test 9: Tenant Isolation - GetById ====================

    @Test
    @Order(9)
    @DisplayName("测试9: 租户隔离 - GetById拒绝访问其他租户记录")
    void test09_tenantIsolation_GetById() {
        log.info("=== Test 9: Tenant isolation - GetById ===");

        // Given: Create other tenant's record
        String otherPid = UniqueIdGenerator.generate();
        insertOtherTenantRecord(otherPid, "其他租户记录");

        // When & Then
        assertThrows(MetaServiceException.class, () -> {
            dynamicDataService.getById(testModelCode, otherPid);
        }, "Accessing other tenant's record should throw exception");

        // Cleanup
        cleanupOtherTenantRecord(otherPid);

        log.info("✓ Tenant isolation verified - GetById");
    }

    // ==================== Test 10: Tenant Isolation - Update ====================

    @Test
    @Order(10)
    @DisplayName("测试10: 租户隔离 - Update拒绝更新其他租户记录")
    void test10_tenantIsolation_Update() {
        log.info("=== Test 10: Tenant isolation - Update ===");

        // Given: Create other tenant's record
        String otherPid = UniqueIdGenerator.generate();
        insertOtherTenantRecord(otherPid, "其他租户记录");

        // When & Then
        Map<String, Object> updateData = new HashMap<>();
        updateData.put("name", "尝试更新");

        assertThrows(MetaServiceException.class, () -> {
            dynamicDataService.update(testModelCode, otherPid, updateData);
        }, "Updating other tenant's record should throw exception");

        // Cleanup
        cleanupOtherTenantRecord(otherPid);

        log.info("✓ Tenant isolation verified - Update");
    }

    // ==================== Test 11: Tenant Isolation - Delete ====================

    @Test
    @Order(11)
    @DisplayName("测试11: 租户隔离 - Delete拒绝删除其他租户记录")
    void test11_tenantIsolation_Delete() {
        log.info("=== Test 11: Tenant isolation - Delete ===");

        // Given: Create other tenant's record
        String otherPid = UniqueIdGenerator.generate();
        insertOtherTenantRecord(otherPid, "其他租户记录");

        // When & Then
        assertThrows(MetaServiceException.class, () -> {
            dynamicDataService.delete(testModelCode, otherPid);
        }, "Deleting other tenant's record should throw exception");

        // Cleanup
        cleanupOtherTenantRecord(otherPid);

        log.info("✓ Tenant isolation verified - Delete");
    }

    // ==================== Helper Methods ====================

    private void createTestModel() {
        log.info("Creating test model: {}", testModelCode);

        testModel = new Model();
        testModel.setPid(UniqueIdGenerator.generate());
        testModel.setTenantId(testTenant.getId());
        testModel.setCode(testModelCode);
        testModel.setVersion(1);
        testModel.setIsCurrent(true);
        testModel.setStatus(Status.PUBLISHED.getCode());
        testModel.setCreatedAt(Instant.now());
        testModel.setUpdatedAt(Instant.now());
        testModel.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", "Dynamic Data Test Model");
        extensionMap.put("description", "For DynamicDataService integration tests");
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        testModel.setExtension(extension);

        metaModelMapper.insert(testModel);

        log.info("✓ Test model created");
    }

    private void createTestFields() {
        log.info("Creating test fields");

        // Create pid field (primary key for business logic)
        // Note: The physical table has 'id' as DB primary key, but DynamicDataService uses 'pid' as the record identifier
        // Primary key field should NOT be required since it's auto-generated
        Field pidField = createFieldEntity("pid", true, false);
        metaFieldMapper.insert(pidField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), pidField.getId(), -1));

        // Create name field (required)
        Field nameField = createFieldEntity("name", false, true);
        metaFieldMapper.insert(nameField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), nameField.getId(), 0));

        // Create status field (optional)
        Field statusField = createFieldEntity("status", false, false);
        metaFieldMapper.insert(statusField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), statusField.getId(), 1));

        log.info("✓ Test fields created");
    }

    private Field createFieldEntity(String code, boolean primaryKey, boolean required) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(testTenant.getId());
        field.setCode(code);
        field.setDataType(DataType.STRING.getCode());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        feature.setUnique(primaryKey);
        field.setFeature(feature);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", code.toUpperCase());
        extensionMap.put("description", code + " field for testing");
        if (primaryKey) {
            extensionMap.put("primaryKey", true);
        }
        extension.setExtension(extensionMap);
        field.setExtension(extension);

        return field;
    }

    private ModelFieldBinding createBinding(Long modelId, Long fieldId, int order) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(modelId);
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        return binding;
    }

    private void createPhysicalTable() {
        log.info("Creating physical table: {}", testTableName);

        try {
            SchemaOperationResult result = schemaManagementService.createTableByModel(testModelCode);
            if (!result.isSuccess()) {
                throw new RuntimeException("Failed to create table: " + result.getErrorMessage());
            }
            log.info("✓ Physical table created: {}", result.getTableName());
        } catch (Exception e) {
            log.error("Failed to create physical table", e);
            throw new RuntimeException("Failed to create physical table", e);
        }
    }

    private void insertOtherTenantRecord(String pid, String name) {
        String sql = String.format(
            "INSERT INTO %s (pid, name, status, tenant_id, created_at, created_by, updated_at, updated_by) " +
            "VALUES (?, ?, ?, ?, NOW(), ?, NOW(), ?)",
            testTableName
        );

        Long otherTenantId = testTenant.getId() + 9999;
        Long otherUserId = testUser.getId() + 9999;
        jdbcTemplate.update(sql, pid, name, "active", otherTenantId, otherUserId, otherUserId);
        log.info("Inserted other tenant record with pid: {}", pid);
    }

    private void cleanupOtherTenantRecord(String pid) {
        try {
            String sql = String.format("DELETE FROM %s WHERE pid = ?", testTableName);
            jdbcTemplate.update(sql, pid);
            log.info("Cleaned up other tenant record with pid: {}", pid);
        } catch (Exception e) {
            log.warn("Failed to cleanup other tenant record with pid: {}", pid, e);
        }
    }
}
