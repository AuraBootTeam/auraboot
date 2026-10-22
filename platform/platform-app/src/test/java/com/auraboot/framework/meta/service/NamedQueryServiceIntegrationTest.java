package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NamedQueryService Integration Test
 *
 * Covers P0-1 requirements:
 * 1. CRUD operations (create, read, update, delete)
 * 2. Field management (add, update, delete, batch)
 * 3. Query execution and testing
 * 4. SQL safety validation (dangerous SQL detection)
 * 5. Status management
 * 6. Pagination and filtering
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("NamedQueryService Integration Test - P0-1")
class NamedQueryServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NamedQueryService namedQueryService;

    // ==================== Helper Methods ====================

    private String generateQueryCode() {
        return "test_query_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 10000);
    }

    private NamedQueryDTO createTestQuery(String code) {
        return createTestQuery(code, "draft");
    }

    private NamedQueryDTO createTestQuery(String code, String status) {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(code);
        request.setTitle("Test Query " + code);
        request.setDescription("Integration test query");
        request.setFromSql("SELECT id, name, status FROM mt_product");
        request.setStatus(status);
        return namedQueryService.create(request);
    }

    // ==================== CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("P0-1.1: Create named query with valid parameters")
    void test01_createNamedQuery_success() {
        String queryCode = generateQueryCode();
        
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(queryCode);
        request.setTitle("Test Query");
        request.setDescription("Integration test query");
        request.setFromSql("SELECT id, name, status FROM mt_product");
        request.setStatus("published");

        NamedQueryDTO result = namedQueryService.create(request);

        assertNotNull(result);
        assertNotNull(result.getPid());
        assertEquals(queryCode, result.getCode());
        assertEquals("Test Query", result.getTitle());
        assertEquals("published", result.getStatus());

        log.info("Created named query: pid={}", result.getPid());
    }

    @Test
    @Order(2)
    @DisplayName("P0-1.1: Create named query with duplicate code should fail")
    void test02_createNamedQuery_duplicateCode() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(queryCode); // Same code
        request.setTitle("Duplicate Query");
        request.setFromSql("SELECT id FROM mt_test");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "Duplicate code should throw exception");
    }

    @Test
    @Order(3)
    @DisplayName("P0-1.4: Create named query with dangerous SQL should fail")
    void test03_createNamedQuery_dangerousSql_dropTable() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(generateQueryCode());
        request.setTitle("Dangerous Query");
        request.setFromSql("DROP TABLE users; SELECT id FROM mt_test");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "DROP TABLE should be rejected");
    }

    @Test
    @Order(4)
    @DisplayName("P0-1.4: Create named query with TRUNCATE SQL should fail")
    void test04_createNamedQuery_dangerousSql_truncate() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(generateQueryCode());
        request.setTitle("Truncate Query");
        request.setFromSql("TRUNCATE TABLE users");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "TRUNCATE should be rejected");
    }

    @Test
    @Order(5)
    @DisplayName("P0-1.4: Create named query with ALTER TABLE SQL should fail")
    void test05_createNamedQuery_dangerousSql_alter() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(generateQueryCode());
        request.setTitle("Alter Query");
        request.setFromSql("ALTER TABLE users ADD COLUMN hacked VARCHAR(255)");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "ALTER TABLE should be rejected");
    }

    @Test
    @Order(6)
    @DisplayName("P0-1.4: Create named query with DELETE FROM SQL should fail")
    void test06_createNamedQuery_dangerousSql_delete() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(generateQueryCode());
        request.setTitle("Delete Query");
        request.setFromSql("DELETE FROM users WHERE 1=1");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "DELETE FROM should be rejected");
    }

    @Test
    @Order(7)
    @DisplayName("P0-1.4: Create named query with INSERT INTO SQL should fail")
    void test07_createNamedQuery_dangerousSql_insert() {
        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(generateQueryCode());
        request.setTitle("Insert Query");
        request.setFromSql("INSERT INTO users (name) VALUES ('hacked')");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.create(request);
        }, "INSERT INTO should be rejected");
    }

    @Test
    @Order(10)
    @DisplayName("P0-1.1: Find named query by PID")
    void test10_findByPid() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode);

        NamedQueryDTO result = namedQueryService.findByPid(created.getPid());

        assertNotNull(result);
        assertEquals(created.getPid(), result.getPid());
        assertEquals(queryCode, result.getCode());
    }

    @Test
    @Order(11)
    @DisplayName("P0-1.1: Find named query by code")
    void test11_findByCode() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryDTO result = namedQueryService.findByCode(queryCode);

        assertNotNull(result);
        assertEquals(queryCode, result.getCode());
    }

    @Test
    @Order(12)
    @DisplayName("P0-1.1: Find named query by non-existent PID should fail")
    void test12_findByPid_notFound() {
        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.findByPid("non_existent_pid_" + System.currentTimeMillis());
        }, "Non-existent PID should throw exception");
    }

    @Test
    @Order(13)
    @DisplayName("P0-1.1: Find named query by non-existent code should fail")
    void test13_findByCode_notFound() {
        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.findByCode("non_existent_code_" + System.currentTimeMillis());
        }, "Non-existent code should throw exception");
    }

    @Test
    @Order(20)
    @DisplayName("P0-1.1: Update named query title and description")
    void test20_updateNamedQuery() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setTitle("Updated Test Query");
        request.setDescription("Updated description");

        NamedQueryDTO result = namedQueryService.update(created.getPid(), request);

        assertNotNull(result);
        assertEquals("Updated Test Query", result.getTitle());
    }

    @Test
    @Order(21)
    @DisplayName("P0-1.1: Update named query fromSql with safe SQL")
    void test21_updateNamedQuery_safeSql() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setFromSql("SELECT id, name, status, created_at FROM mt_product");

        NamedQueryDTO result = namedQueryService.update(created.getPid(), request);
        assertNotNull(result);
    }

    @Test
    @Order(22)
    @DisplayName("P0-1.1: Update named query fromSql with dangerous SQL should fail")
    void test22_updateNamedQuery_dangerousSql() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode);

        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setFromSql("DROP TABLE users");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.update(created.getPid(), request);
        }, "Update with dangerous SQL should be rejected");
    }

    @Test
    @Order(23)
    @DisplayName("P0-1.1: Update non-existent named query should fail")
    void test23_updateNamedQuery_notFound() {
        NamedQueryUpdateRequest request = new NamedQueryUpdateRequest();
        request.setTitle("Should Fail");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.update("non_existent_pid_" + System.currentTimeMillis(), request);
        });
    }

    // ==================== Status Management Tests ====================

    @Test
    @Order(30)
    @DisplayName("P0-1.1: Update named query status to DEPRECATED")
    void test30_updateStatus_deprecate() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode, "published");

        NamedQueryDTO result = namedQueryService.updateStatus(created.getPid(), "deprecated");

        assertNotNull(result);
        assertEquals("deprecated", result.getStatus());
    }

    @Test
    @Order(31)
    @DisplayName("P0-1.1: Update named query status back to PUBLISHED")
    void test31_updateStatus_republish() {
        String queryCode = generateQueryCode();
        NamedQueryDTO created = createTestQuery(queryCode, "published");
        namedQueryService.updateStatus(created.getPid(), "deprecated");

        NamedQueryDTO result = namedQueryService.updateStatus(created.getPid(), "published");

        assertNotNull(result);
        assertEquals("published", result.getStatus());
    }

    // ==================== Field Management Tests ====================

    @Test
    @Order(40)
    @DisplayName("P0-1.2: Add field to named query")
    void test40_addField() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest fieldReq = new NamedQueryFieldRequest();
        fieldReq.setFieldCode("product_name");
        fieldReq.setColumnExpr("name");
        fieldReq.setDataType("string");
        fieldReq.setSortable(true);
        fieldReq.setSearchable(true);
        fieldReq.setOperators(List.of("eq", "like", "contains"));

        NamedQueryFieldDTO result = namedQueryService.addField(queryCode, fieldReq);

        assertNotNull(result);
        assertEquals("product_name", result.getFieldCode());
        assertEquals("string", result.getDataType());
    }

    @Test
    @Order(41)
    @DisplayName("P0-1.2: Add duplicate field code should fail")
    void test41_addField_duplicate() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest fieldReq = new NamedQueryFieldRequest();
        fieldReq.setFieldCode("dup_field");
        fieldReq.setColumnExpr("name");
        fieldReq.setDataType("string");
        namedQueryService.addField(queryCode, fieldReq);

        NamedQueryFieldRequest dupReq = new NamedQueryFieldRequest();
        dupReq.setFieldCode("dup_field"); // Same code
        dupReq.setColumnExpr("name");
        dupReq.setDataType("string");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.addField(queryCode, dupReq);
        }, "Duplicate field code should throw exception");
    }

    @Test
    @Order(42)
    @DisplayName("P0-1.2: Add multiple fields")
    void test42_addMultipleFields() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest statusField = new NamedQueryFieldRequest();
        statusField.setFieldCode("product_status");
        statusField.setColumnExpr("status");
        statusField.setDataType("string");
        statusField.setSortable(false);
        statusField.setSearchable(true);
        statusField.setOperators(List.of("eq", "in"));

        NamedQueryFieldDTO result1 = namedQueryService.addField(queryCode, statusField);
        assertNotNull(result1);

        NamedQueryFieldRequest idField = new NamedQueryFieldRequest();
        idField.setFieldCode("product_id");
        idField.setColumnExpr("id");
        idField.setDataType("string");
        idField.setSortable(true);
        idField.setSearchable(false);

        NamedQueryFieldDTO result2 = namedQueryService.addField(queryCode, idField);
        assertNotNull(result2);
    }

    @Test
    @Order(43)
    @DisplayName("P0-1.2: Get all fields of a query")
    void test43_getFields() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        // Add fields
        NamedQueryFieldRequest f1 = new NamedQueryFieldRequest();
        f1.setFieldCode("field_a");
        f1.setColumnExpr("col_a");
        f1.setDataType("string");
        namedQueryService.addField(queryCode, f1);

        NamedQueryFieldRequest f2 = new NamedQueryFieldRequest();
        f2.setFieldCode("field_b");
        f2.setColumnExpr("col_b");
        f2.setDataType("integer");
        namedQueryService.addField(queryCode, f2);

        List<NamedQueryFieldDTO> fields = namedQueryService.getFields(queryCode);

        assertNotNull(fields);
        assertTrue(fields.size() >= 2, "Should have at least 2 fields");

        List<String> fieldCodes = fields.stream()
                .map(NamedQueryFieldDTO::getFieldCode)
                .toList();
        assertTrue(fieldCodes.contains("field_a"));
        assertTrue(fieldCodes.contains("field_b"));
    }

    @Test
    @Order(44)
    @DisplayName("P0-1.2: Update field properties")
    void test44_updateField() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest fieldReq = new NamedQueryFieldRequest();
        fieldReq.setFieldCode("update_field");
        fieldReq.setColumnExpr("name");
        fieldReq.setDataType("string");
        namedQueryService.addField(queryCode, fieldReq);

        NamedQueryFieldRequest updateReq = new NamedQueryFieldRequest();
        updateReq.setDataType("text");
        updateReq.setSortable(false);
        updateReq.setSearchable(true);

        NamedQueryFieldDTO result = namedQueryService.updateField(queryCode, "update_field", updateReq);

        assertNotNull(result);
        assertEquals("text", result.getDataType());
    }

    @Test
    @Order(45)
    @DisplayName("P0-1.2: Update non-existent field should fail")
    void test45_updateField_notFound() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest updateReq = new NamedQueryFieldRequest();
        updateReq.setDataType("integer");

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.updateField(queryCode, "non_existent_field_" + System.currentTimeMillis(), updateReq);
        });
    }

    @Test
    @Order(46)
    @DisplayName("P0-1.2: Delete field from query")
    void test46_deleteField() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryFieldRequest fieldReq = new NamedQueryFieldRequest();
        fieldReq.setFieldCode("to_delete_field");
        fieldReq.setColumnExpr("temp");
        fieldReq.setDataType("string");
        namedQueryService.addField(queryCode, fieldReq);

        assertDoesNotThrow(() -> {
            namedQueryService.deleteField(queryCode, "to_delete_field");
        });

        List<NamedQueryFieldDTO> fields = namedQueryService.getFields(queryCode);
        boolean stillExists = fields.stream()
                .anyMatch(f -> "to_delete_field".equals(f.getFieldCode()));
        assertFalse(stillExists, "Deleted field should not exist");
    }

    @Test
    @Order(47)
    @DisplayName("P0-1.2: Delete non-existent field should fail")
    void test47_deleteField_notFound() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.deleteField(queryCode, "non_existent_field_" + System.currentTimeMillis());
        });
    }

    // ==================== Batch Field Operations ====================

    @Test
    @Order(50)
    @DisplayName("P0-1.2: Batch save fields - SET operation with clearExisting")
    void test50_batchSaveFields_set() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        List<NamedQueryFieldRequest> fields = new ArrayList<>();

        NamedQueryFieldRequest f1 = new NamedQueryFieldRequest();
        f1.setFieldCode("batch_field_1");
        f1.setColumnExpr("col1");
        f1.setDataType("string");
        fields.add(f1);

        NamedQueryFieldRequest f2 = new NamedQueryFieldRequest();
        f2.setFieldCode("batch_field_2");
        f2.setColumnExpr("col2");
        f2.setDataType("integer");
        fields.add(f2);

        NamedQueryFieldBatchRequest request = new NamedQueryFieldBatchRequest();
        request.setOperationType("set");
        request.setFields(fields);
        request.setClearExisting(true);

        NamedQueryFieldBatchResult result = namedQueryService.batchSaveFields(queryCode, request);

        assertNotNull(result);
        assertEquals(2, result.getTotalCount());
        assertTrue(result.getSuccessCount() >= 2);
    }

    // ==================== List and Pagination Tests ====================

    @Test
    @Order(60)
    @DisplayName("P0-1.3: List named queries with pagination")
    void test60_listWithPagination() {
        // Create a query to ensure at least one exists
        createTestQuery(generateQueryCode());

        NamedQueryQueryRequest request = new NamedQueryQueryRequest();
        request.setPage(1);
        request.setSize(10);

        PaginationResult<NamedQueryDTO> result = namedQueryService.list(request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getTotal() >= 1, "Should have at least 1 query");
    }

    @Test
    @Order(61)
    @DisplayName("P0-1.3: List named queries with code filter")
    void test61_listWithCodeFilter() {
        String queryCode = generateQueryCode();
        createTestQuery(queryCode);

        NamedQueryQueryRequest request = new NamedQueryQueryRequest();
        request.setPage(1);
        request.setSize(10);
        request.setCode(queryCode);

        PaginationResult<NamedQueryDTO> result = namedQueryService.list(request);

        assertNotNull(result);
        assertTrue(result.getTotal() >= 1);
        assertTrue(result.getRecords().stream()
                .anyMatch(q -> q.getCode().contains(queryCode)));
    }

    // ==================== Create with Fields ====================

    @Test
    @Order(90)
    @DisplayName("P0-1.1: Create named query with fields in one request")
    void test90_createWithFields() {
        String code = generateQueryCode();

        List<NamedQueryFieldRequest> fields = new ArrayList<>();
        NamedQueryFieldRequest f1 = new NamedQueryFieldRequest();
        f1.setFieldCode("id_field");
        f1.setColumnExpr("id");
        f1.setDataType("string");
        f1.setSortable(true);
        fields.add(f1);

        NamedQueryFieldRequest f2 = new NamedQueryFieldRequest();
        f2.setFieldCode("name_field");
        f2.setColumnExpr("name");
        f2.setDataType("string");
        f2.setSearchable(true);
        f2.setOperators(List.of("eq", "like"));
        fields.add(f2);

        NamedQueryCreateRequest request = new NamedQueryCreateRequest();
        request.setCode(code);
        request.setTitle("Query With Fields");
        request.setFromSql("SELECT id, name FROM mt_test");
        request.setFields(fields);

        NamedQueryDTO result = namedQueryService.create(request);

        assertNotNull(result);
        assertEquals(code, result.getCode());

        List<NamedQueryFieldDTO> createdFields = namedQueryService.getFields(code);
        assertTrue(createdFields.size() >= 2, "Should have created 2 fields");
    }

    // ==================== Delete Tests ====================

    @Test
    @Order(100)
    @DisplayName("P0-1.1: Delete named query and associated fields")
    void test100_deleteNamedQuery() {
        String code = generateQueryCode();
        NamedQueryDTO created = createTestQuery(code);

        // Add a field
        NamedQueryFieldRequest fieldReq = new NamedQueryFieldRequest();
        fieldReq.setFieldCode("delete_field");
        fieldReq.setColumnExpr("id");
        fieldReq.setDataType("string");
        namedQueryService.addField(code, fieldReq);

        // Delete the query
        assertDoesNotThrow(() -> {
            namedQueryService.delete(created.getPid());
        });

        // Verify query is deleted
        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.findByPid(created.getPid());
        });

        // Verify fields are also deleted
        List<NamedQueryFieldDTO> fields = namedQueryService.getFields(code);
        assertTrue(fields.isEmpty(), "Fields should be deleted with query");
    }

    @Test
    @Order(101)
    @DisplayName("P0-1.1: Delete non-existent named query should fail")
    void test101_deleteNamedQuery_notFound() {
        assertThrows(MetaServiceException.class, () -> {
            namedQueryService.delete("non_existent_pid_" + System.currentTimeMillis());
        });
    }
}
