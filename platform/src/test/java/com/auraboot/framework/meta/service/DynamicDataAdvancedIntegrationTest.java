package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DynamicDataService Advanced Features Integration Test
 *
 * Tests advanced CRUD operations: list, create, update, delete, batch operations.
 * Uses a shared model across all tests to avoid field uniqueness constraint issues.
 * 
 * Note: Uses NOT_SUPPORTED propagation because DDL operations (CREATE TABLE) 
 * cannot be rolled back in PostgreSQL within a transaction.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DynamicDataService Advanced Integration Test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicDataAdvancedIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private SchemaManagementService schemaManagementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Shared model for all tests
    private String modelCode;
    private Model model;
    private boolean modelInitialized = false;
    private final List<String> createdRecordPids = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setupSharedModel() {
        modelCode = "adv_test_" + System.currentTimeMillis();
        modelInitialized = false;
        createdRecordPids.clear();
    }

    @BeforeEach
    void ensureModelExists() {
        setupTenantContext();
        
        if (!modelInitialized) {
            try {
                cleanupExistingModel();
                createModel();
                createFields();
                createPhysicalTable();
                modelInitialized = true;
                log.info("Model initialized: {}", modelCode);
            } catch (Exception e) {
                log.error("Failed to initialize model", e);
                throw new RuntimeException("Failed to initialize model", e);
            }
        }
    }

    @AfterEach
    void cleanupRecords() {
        // Clean up records created in this test
        for (String pid : new ArrayList<>(createdRecordPids)) {
            try {
                dynamicDataService.delete(modelCode, pid);
            } catch (Exception e) {
                log.debug("Failed to cleanup record: {}", pid);
            }
        }
        createdRecordPids.clear();
    }

    @AfterAll
    void cleanup() {
        modelInitialized = false;
        createdRecordPids.clear();
    }

    // ==================== Helper Methods ====================

    private void cleanupExistingModel() {
        try {
            Long tenantId = getTestTenant().getId();
            
            // Delete bindings
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                "(SELECT id FROM ab_meta_model WHERE code = ? AND tenant_id = ?)",
                modelCode, tenantId
            );
            
            // Delete fields
            jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE code IN ('pid', 'name', 'status', 'price', 'quantity', 'category') AND tenant_id = ?",
                tenantId
            );
            
            // Delete model
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model WHERE code = ? AND tenant_id = ?",
                modelCode, tenantId
            );
            
            // Drop table
            String tableName = "mt_" + modelCode.toLowerCase();
            try {
                jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            } catch (Exception e) {
                log.debug("Table {} does not exist", tableName);
            }
        } catch (Exception e) {
            log.debug("No existing model to clean up: {}", e.getMessage());
        }
    }

    private void createModel() {
        model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", "Advanced Test Model");
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        model.setExtension(extension);

        metaModelMapper.insert(model);
        trackModel(modelCode);
    }

    private void createFields() {
        // pid field (primary key)
        createAndBindField("pid", DataType.STRING, true, false, -1);
        // Business fields
        createAndBindField("name", DataType.STRING, false, true, 1);
        createAndBindField("status", DataType.STRING, false, false, 2);
        createAndBindField("price", DataType.INTEGER, false, false, 3);
        createAndBindField("quantity", DataType.INTEGER, false, false, 4);
        createAndBindField("category", DataType.STRING, false, false, 5);
    }

    private void createAndBindField(String code, DataType dataType, boolean primaryKey, boolean required, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType.getCode());
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

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", code.toUpperCase());
        if (primaryKey) extMap.put("primaryKey", true);
        ext.setExtension(extMap);
        field.setExtension(ext);

        metaFieldMapper.insert(field);

        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new RuntimeException("Table creation failed: " + result.getErrorMessage());
        }
    }

    private List<String> seedData(int count) {
        List<String> pids = new ArrayList<>();
        String[] categories = {"electronics", "food", "clothing"};
        String[] statuses = {"active", "draft", "archived"};

        for (int i = 0; i < count; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", "Test Item " + i);
            data.put("status", statuses[i % statuses.length]);
            data.put("price", (i + 1) * 50);
            data.put("quantity", (i + 1) * 2);
            data.put("category", categories[i % categories.length]);

            Map<String, Object> created = dynamicDataService.create(modelCode, data);
            if (created != null && created.containsKey("pid")) {
                String pid = created.get("pid").toString();
                pids.add(pid);
                createdRecordPids.add(pid);
            }
        }
        return pids;
    }

    // ==================== Basic CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("Create and retrieve a single record")
    void test01_createAndGetRecord() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Test Product");
        data.put("status", "active");
        data.put("price", 100);
        data.put("quantity", 10);
        data.put("category", "electronics");

        Map<String, Object> created = dynamicDataService.create(modelCode, data);
        
        assertNotNull(created, "Created record should not be null");
        assertNotNull(created.get("pid"), "Created record should have pid");
        
        String pid = created.get("pid").toString();
        createdRecordPids.add(pid);
        
        Map<String, Object> retrieved = dynamicDataService.getById(modelCode, pid);
        
        assertNotNull(retrieved, "Retrieved record should not be null");
        assertEquals(pid, retrieved.get("pid").toString());
        log.info("Created and retrieved record with pid: {}", pid);
    }

    @Test
    @Order(2)
    @DisplayName("List records with pagination")
    void test02_listWithPagination() {
        seedData(5);

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(3)
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getRecords().size() <= 3, "Should return at most 3 records");
        log.info("Listed {} records", result.getRecords().size());
    }

    @Test
    @Order(3)
    @DisplayName("Update a record")
    void test03_updateRecord() {
        List<String> pids = seedData(1);
        assertFalse(pids.isEmpty(), "Should have created at least one record");
        String pid = pids.get(0);

        Map<String, Object> updateData = new HashMap<>();
        updateData.put("name", "Updated Name");
        updateData.put("status", "updated");

        Map<String, Object> updated = dynamicDataService.update(modelCode, pid, updateData);
        
        assertNotNull(updated, "Updated record should not be null");
        log.info("Updated record with pid: {}", pid);
    }

    @Test
    @Order(4)
    @DisplayName("Delete a record")
    void test04_deleteRecord() {
        List<String> pids = seedData(1);
        assertFalse(pids.isEmpty(), "Should have created at least one record");
        String pid = pids.get(0);
        createdRecordPids.remove(pid);  // Remove from cleanup list since we're deleting it

        assertDoesNotThrow(() -> dynamicDataService.delete(modelCode, pid));
        log.info("Deleted record with pid: {}", pid);
    }

    // ==================== Query Tests ====================

    @Test
    @Order(10)
    @DisplayName("Query with filter conditions")
    void test10_queryWithFilters() {
        seedData(6);

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .conditions(List.of(
                        QueryCondition.builder()
                                .fieldName("status")
                                .operator(QueryCondition.Operator.EQ)
                                .value("active")
                                .build()
                ))
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        log.info("Filtered query returned {} records", result.getRecords().size());
    }

    @Test
    @Order(11)
    @DisplayName("Query with sorting")
    void test11_queryWithSorting() {
        seedData(5);

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .sortFields(List.of(
                        SortField.builder()
                                .fieldName("price")
                                .direction(SortField.SortDirection.DESC)
                                .build()
                ))
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        log.info("Sorted query returned {} records", result.getRecords().size());
    }

    @Test
    @Order(12)
    @DisplayName("Query with multiple conditions")
    void test12_queryWithMultipleConditions() {
        seedData(10);

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .conditions(List.of(
                        QueryCondition.builder()
                                .fieldName("status")
                                .operator(QueryCondition.Operator.EQ)
                                .value("active")
                                .build(),
                        QueryCondition.builder()
                                .fieldName("category")
                                .operator(QueryCondition.Operator.EQ)
                                .value("electronics")
                                .build()
                ))
                .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        log.info("Multi-condition query returned {} records", result.getRecords().size());
    }

    // ==================== Batch Operations Tests ====================

    @Test
    @Order(20)
    @DisplayName("Batch update records")
    void test20_batchUpdate() {
        seedData(3);

        DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .build();
        PaginationResult<Map<String, Object>> records = dynamicDataService.list(modelCode, queryRequest);

        List<Map<String, Object>> batchData = new ArrayList<>();
        for (Map<String, Object> record : records.getRecords()) {
            Map<String, Object> updateData = new HashMap<>();
            updateData.put("pid", record.get("pid"));
            updateData.put("status", "batch_updated");
            batchData.add(updateData);
        }

        DynamicBatchResponse result = dynamicDataService.batchUpdate(modelCode, batchData);

        assertNotNull(result);
        log.info("Batch update: {}/{} success", result.getSuccess(), result.getTotal());
    }

    @Test
    @Order(21)
    @DisplayName("Batch delete records")
    void test21_batchDelete() {
        List<String> pids = seedData(3);

        // Remove from cleanup list since we're batch deleting
        createdRecordPids.removeAll(pids);

        assertDoesNotThrow(() -> dynamicDataService.batchDelete(modelCode, pids));
        log.info("Batch deleted {} records", pids.size());
    }

    // ==================== Aggregate Tests ====================

    @Test
    @Order(30)
    @DisplayName("Aggregate - COUNT")
    void test30_aggregateCount() {
        seedData(5);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("pid")
                                .function(AggregateRequest.AggregateFunction.COUNT)
                                .alias("total_count")
                                .build()
                ))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("COUNT aggregate result: {}", result);
    }

    @Test
    @Order(31)
    @DisplayName("Aggregate - SUM")
    void test31_aggregateSum() {
        seedData(5);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("price")
                                .function(AggregateRequest.AggregateFunction.SUM)
                                .alias("total_price")
                                .build()
                ))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("SUM aggregate result: {}", result);
    }

    @Test
    @Order(32)
    @DisplayName("Aggregate - AVG")
    void test32_aggregateAvg() {
        seedData(5);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("price")
                                .function(AggregateRequest.AggregateFunction.AVG)
                                .alias("avg_price")
                                .build()
                ))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("AVG aggregate result: {}", result);
    }

    @Test
    @Order(33)
    @DisplayName("Aggregate - MAX and MIN")
    void test33_aggregateMaxMin() {
        seedData(5);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("price")
                                .function(AggregateRequest.AggregateFunction.MAX)
                                .alias("max_price")
                                .build(),
                        AggregateRequest.AggregateField.builder()
                                .fieldName("price")
                                .function(AggregateRequest.AggregateFunction.MIN)
                                .alias("min_price")
                                .build()
                ))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("MAX/MIN aggregate result: {}", result);
    }

    @Test
    @Order(34)
    @DisplayName("Aggregate - GROUP BY")
    void test34_aggregateGroupBy() {
        seedData(6);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("pid")
                                .function(AggregateRequest.AggregateFunction.COUNT)
                                .alias("count")
                                .build()
                ))
                .groupByFields(List.of("category"))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("GROUP BY aggregate result: {}", result);
    }

    @Test
    @Order(35)
    @DisplayName("Aggregate - with filter conditions")
    void test35_aggregateWithConditions() {
        seedData(5);

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("price")
                                .function(AggregateRequest.AggregateFunction.SUM)
                                .alias("filtered_sum")
                                .build()
                ))
                .conditions(List.of(
                        QueryCondition.builder()
                                .fieldName("status")
                                .operator(QueryCondition.Operator.EQ)
                                .value("active")
                                .build()
                ))
                .build();

        Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);

        assertNotNull(result);
        log.info("Aggregate with conditions result: {}", result);
    }
}
