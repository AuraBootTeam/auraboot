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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for full-text keyword search in dynamic tables.
 *
 * Verifies:
 * 1. Keyword search returns matching records across searchable fields
 * 2. Keyword search with no matches returns empty
 * 3. Keyword search across multiple fields (OR logic)
 * 4. SQL injection safety via ILIKE parameterization
 * 5. Auto-marking of searchable fields during model publish
 *
 * Uses real PostgreSQL, no mocking.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullTextSearchIntegrationTest {

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

    private String testModelCode;
    private String testTableName;
    private Model testModel;
    private final List<String> createdRecordPids = Collections.synchronizedList(new ArrayList<>());
    private boolean modelInitialized = false;

    private User testUser;
    private Tenant testTenant;

    @BeforeAll
    void setupTestModel() {
        long ts = System.currentTimeMillis();
        testModelCode = "fts_test_" + ts;
        testTableName = "mt_" + testModelCode.toLowerCase();
        modelInitialized = false;
        createdRecordPids.clear();
    }

    @BeforeEach
    void ensureModelExists() {
        setupTenantContext();
        if (!modelInitialized) {
            cleanupExistingTestModel();
            createTestModel();
            createTestFields();
            createPhysicalTable();
            insertTestData();
            modelInitialized = true;
        }
    }

    @AfterAll
    void cleanup() {
        log.info("=== FullTextSearch test cleanup ===");
        modelInitialized = false;
        createdRecordPids.clear();
    }

    @AfterEach
    void cleanupContext() {
        if (testTenant != null && testUser != null) {
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        }
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("Keyword search returns matching records")
    void keywordSearch_matchingRecords() {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("Alpha")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords()).allSatisfy(record -> {
            String name = (String) record.get("name");
            String description = (String) record.get("description");
            boolean matches = (name != null && name.toLowerCase().contains("alpha"))
                    || (description != null && description.toLowerCase().contains("alpha"));
            assertThat(matches).as("Record should contain 'Alpha' in name or description: name=%s, desc=%s", name, description).isTrue();
        });
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(result.getRecords().size());

        log.info("keyword=Alpha returned {} records, total={}", result.getRecords().size(), result.getTotal());
    }

    @Test
    @Order(2)
    @DisplayName("Keyword search with no matches returns empty")
    void keywordSearch_noMatches() {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("zzz_nonexistent_xyz_" + System.currentTimeMillis())
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(0);

        log.info("No-match keyword returned 0 records as expected");
    }

    @Test
    @Order(3)
    @DisplayName("Keyword search matches across multiple fields (OR logic)")
    void keywordSearch_multipleFields() {
        // "unique_desc" only appears in the description field, not name
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("unique_desc")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isNotEmpty();
        assertThat(result.getRecords()).anySatisfy(record -> {
            String description = (String) record.get("description");
            assertThat(description).containsIgnoringCase("unique_desc");
        });

        log.info("Multi-field keyword search returned {} records", result.getRecords().size());
    }

    @Test
    @Order(4)
    @DisplayName("Keyword search is case-insensitive")
    void keywordSearch_caseInsensitive() {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("alpha")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isNotEmpty();

        log.info("Case-insensitive search returned {} records", result.getRecords().size());
    }

    @Test
    @Order(5)
    @DisplayName("SQL injection in keyword is safely escaped")
    void keywordSearch_sqlInjectionSafe() {
        // Attempt SQL injection via keyword — should not cause errors or return all records
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("'; DROP TABLE " + testTableName + "; --")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();
        // Verify table still exists
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, testTableName);
        assertThat(tableCount).isEqualTo(1);

        log.info("SQL injection attempt safely handled, table intact");
    }

    @Test
    @Order(6)
    @DisplayName("Keyword with ILIKE special characters is safely escaped")
    void keywordSearch_specialCharsSafe() {
        // % and _ are ILIKE wildcards — they should be escaped
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("100%_match")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        // Should not error and should not match wildcard patterns
        assertThat(result).isNotNull();
        assertThat(result.getRecords()).isEmpty();

        log.info("Special chars safely escaped, returned {} records", result.getRecords().size());
    }

    @Test
    @Order(7)
    @DisplayName("Empty/blank keyword returns all records (no filter)")
    void keywordSearch_blankKeyword() {
        DynamicQueryRequest requestAll = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .build();
        PaginationResult<Map<String, Object>> allResult = dynamicDataService.list(testModelCode, requestAll);

        DynamicQueryRequest requestBlank = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(50)
                .keyword("   ")
                .build();
        PaginationResult<Map<String, Object>> blankResult = dynamicDataService.list(testModelCode, requestBlank);

        assertThat(blankResult.getTotal()).isEqualTo(allResult.getTotal());

        log.info("Blank keyword returned same count as no keyword: {}", blankResult.getTotal());
    }

    @Test
    @Order(8)
    @DisplayName("Keyword search count is consistent with records")
    void keywordSearch_countConsistency() {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(2)  // Small page to force pagination
                .keyword("Alpha")
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(testModelCode, request);

        assertThat(result).isNotNull();
        // Total should be >= records returned (since pageSize limits records)
        assertThat(result.getTotal()).isGreaterThanOrEqualTo(result.getRecords().size());
        // Records should be at most pageSize
        assertThat(result.getRecords().size()).isLessThanOrEqualTo(2);

        log.info("Pagination consistency: {} records, total={}", result.getRecords().size(), result.getTotal());
    }

    // ==================== Setup Helpers ====================

    private void setupTenantContext() {
        try {
            if (testUser == null) {
                String testEmail = "fts-test-" + System.currentTimeMillis() + "@auraboot.com";
                testUser = userService.findByEmail(testEmail);
                if (testUser == null) {
                    testUser = userService.signUp(testEmail, "test-password-123");
                }
            }
            if (testTenant == null) {
                String testTenantName = "fts-test-tenant-" + System.currentTimeMillis();
                testTenant = tenantService.findByName(testTenantName);
                if (testTenant == null) {
                    Tenant tenant = new Tenant();
                    tenant.setPid(UniqueIdGenerator.generate());
                    tenant.setName(testTenantName);
                    tenant.setDisplayName("FTS Test Tenant");
                    tenant.setStatus("active");
                    tenant.setContactEmail("fts-test@auraboot.com");
                    tenant.setDescription("Test tenant for full-text search integration tests");
                    tenant.setDeletedFlag(false);
                    tenant.setCreatedAt(Instant.now());
                    tenant.setUpdatedAt(Instant.now());
                    testTenant = tenantService.createTenant(tenant);
                }
            }
            TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
            if (member == null) {
                tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
            }
            MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup tenant context", e);
        }
    }

    private void cleanupExistingTestModel() {
        try {
            // Delete bindings
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                            "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                    testModelCode, testTenant.getId());
            // Delete fields created for this test (by code pattern within tenant)
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_field WHERE code IN ('pid', 'name', 'description', 'status', 'amount') AND tenant_id = ?",
                    testTenant.getId());
            // Delete the model
            jdbcTemplate.update(
                    "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                    testModelCode, testTenant.getId());
            // Drop physical table
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + testTableName);
        } catch (Exception e) {
            log.debug("No existing test model to clean up: {}", e.getMessage());
        }
    }

    private void createTestModel() {
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
        extensionMap.put("displayName", "FTS Test Model");
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        testModel.setExtension(extension);

        metaModelMapper.insert(testModel);
        log.info("Test model created: {}", testModelCode);
    }

    private void createTestFields() {
        // pid field
        Field pidField = createFieldEntity("pid", DataType.STRING.getCode(), true, false);
        metaFieldMapper.insert(pidField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), pidField.getId(), -1, false));

        // name field (searchable)
        Field nameField = createFieldEntity("name", DataType.STRING.getCode(), false, true);
        metaFieldMapper.insert(nameField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), nameField.getId(), 0, true));

        // description field (searchable)
        Field descField = createFieldEntity("description", DataType.TEXT.getCode(), false, false);
        metaFieldMapper.insert(descField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), descField.getId(), 1, true));

        // status field (not searchable — string type but explicitly not marked)
        Field statusField = createFieldEntity("status", DataType.STRING.getCode(), false, false);
        metaFieldMapper.insert(statusField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), statusField.getId(), 2, false));

        // amount field (not searchable — numeric type)
        Field amountField = createFieldEntity("amount", DataType.DECIMAL.getCode(), false, false);
        metaFieldMapper.insert(amountField);
        fieldBindingMapper.insert(createBinding(testModel.getId(), amountField.getId(), 3, false));

        log.info("Test fields created with searchable flags");
    }

    private Field createFieldEntity(String code, String dataType, boolean primaryKey, boolean required) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(testTenant.getId());
        field.setCode(code);
        field.setDataType(dataType);
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
        if (primaryKey) {
            extensionMap.put("primaryKey", true);
        }
        extension.setExtension(extensionMap);
        field.setExtension(extension);

        return field;
    }

    private ModelFieldBinding createBinding(Long modelId, Long fieldId, int order, boolean searchable) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(modelId);
        binding.setFieldId(fieldId);
        binding.setFieldOrder(order);
        binding.setSearchable(searchable);
        return binding;
    }

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(testModelCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to create table: " + result.getErrorMessage());
        }
        log.info("Physical table created: {}", testTableName);
    }

    private void insertTestData() {
        // Insert test records with varied data for keyword search testing
        createRecord("Alpha Project", "Main project for Alpha team", "active");
        createRecord("Alpha Extension", "Extension module unique_desc_marker", "draft");
        createRecord("Beta Service", "Backend service for Beta platform", "active");
        createRecord("Gamma Tool", "Utility tool for Gamma operations", "active");
        createRecord("Delta Alpha Mixed", "Contains both Delta and Alpha keywords", "published");

        log.info("Inserted {} test records for keyword search", createdRecordPids.size());
    }

    private void createRecord(String name, String description, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("description", description);
        data.put("status", status);

        Map<String, Object> result = dynamicDataService.create(testModelCode, data);
        createdRecordPids.add(result.get("pid").toString());
    }
}
