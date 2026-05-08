package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DataPermissionEngine covering:
 * - Row-level filtering (no policies, SELF, ALL, disabled)
 * - Column-level masking (HIDE, PARTIAL, HASH)
 * - Query path coverage (list, aggregate, getStats)
 * - Policy CRUD lifecycle
 * - Filter preview
 *
 * @since 5.1.0
 */
@Slf4j
@DisplayName("GAP-010: Data Permission Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataPermissionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataPermissionEngine dataPermissionEngine;

    @Autowired
    private DataPermissionPolicyService policyService;

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    private static final String TEST_MODEL = "e2et_record";

    /**
     * Find a published model available in the current tenant, or create one if none exists.
     */
    private String findAvailableModel() {
        try {
            var model = metaModelService.findByCode(TEST_MODEL);
            if (model != null) {
                return TEST_MODEL;
            }
        } catch (Exception e) {
            log.debug("Existing model lookup failed, creating a test model: {}", e.getMessage());
        }

        String suffix = String.valueOf(System.currentTimeMillis());
        String modelCode = "dp_perm_" + suffix;

        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(modelCode);
        request.setDisplayName("Data Permission " + suffix);
        request.setDescription("Integration test model for data permission coverage");
        request.setModelCategory("entity");
        request.setTableName("mt_dp_perm_" + suffix);

        MetaModelDTO created = metaModelService.create(request);
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode("dp_perm_field_" + suffix);
        fieldRequest.setDataType("string");
        fieldRequest.setAutoPublish(true);
        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        metaModelService.bindFieldToModel(
                created.getId(),
                field.getId(),
                1,
                true,
                true,
                false,
                null,
                null,
                null,
                null
        );
        metaModelService.publish(created.getPid(), "integration-test");
        return modelCode;
    }

    // ==================== Row Filter Tests ====================

    @Test
    @Order(1)
    @DisplayName("No policies → buildRowFilter returns null or empty")
    void testNoPolicies_ReturnsEmptyFilter() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        String filter = dataPermissionEngine.buildRowFilter(tenantId, "nonexistent_model_xyz", userId);

        assertTrue(filter == null || filter.isBlank(),
                "buildRowFilter should return null or empty when no policies exist");
    }

    @Test
    @Order(2)
    @DisplayName("SELF scope → generates created_by filter")
    void testSelfScope_GeneratesCreatedByFilter() {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("Self Only - Test");
        req.setModelCode("dp_self_test");
        req.setPolicyType("row");
        req.setScopeType("self");
        req.setPriority(10);

        DataPermissionPolicy policy = policyService.create(req);
        assertNotNull(policy, "Policy should be created");

        // Bind to the test role so it becomes effective
        policyService.bindToRole(policy.getPid(), getTestRole().getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "dp_self_test", userId);

        assertNotNull(filter, "Filter should not be null for SELF scope");
        assertTrue(filter.contains("created_by"),
                "SELF filter should reference created_by, got: " + filter);
        assertTrue(filter.contains(String.valueOf(userId)),
                "SELF filter should contain userId, got: " + filter);
    }

    @Test
    @Order(3)
    @DisplayName("ALL scope → generates empty filter (no restriction)")
    void testAllScope_GeneratesEmptyFilter() {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("All Access - Test");
        req.setModelCode("dp_all_test");
        req.setPolicyType("row");
        req.setScopeType("all");
        req.setPriority(10);

        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), getTestRole().getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "dp_all_test", userId);

        assertTrue(filter == null || filter.isBlank(),
                "ALL scope should produce empty filter, got: " + filter);
    }

    @Test
    @Order(4)
    @DisplayName("Disabled policy is not applied")
    void testDisabledPolicy_NotApplied() {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("Disabled Policy - Test");
        req.setModelCode("dp_disabled_test");
        req.setPolicyType("row");
        req.setScopeType("self");
        req.setPriority(10);

        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), getTestRole().getPid());

        // Disable the policy
        policyService.disable(policy.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "dp_disabled_test", userId);

        assertTrue(filter == null || filter.isBlank(),
                "Disabled policy should not produce a filter, got: " + filter);
    }

    // ==================== Column Masking Tests ====================

    @Test
    @Order(5)
    @DisplayName("HIDE mask → field value becomes null")
    void testHideMask_FieldReturnsNull() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("secret").maskType("hide").build()
        );

        Map<String, Object> record = new HashMap<>();
        record.put("id", 1);
        record.put("name", "visible");
        record.put("secret", "sensitive-data");

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(
                List.of(record), rules);

        assertNotNull(masked);
        assertEquals(1, masked.size());
        assertNull(masked.get(0).get("secret"), "HIDE mask should set field to null");
        assertEquals("visible", masked.get(0).get("name"), "Non-masked fields should remain");
    }

    @Test
    @Order(6)
    @DisplayName("PARTIAL mask → shows first/last chars with asterisks")
    void testPartialMask_ShowsFirstAndLastChars() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("phone").maskType("partial").build()
        );

        Map<String, Object> record = new HashMap<>();
        record.put("phone", "13812345678");

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(
                List.of(record), rules);

        String maskedPhone = (String) masked.get(0).get("phone");
        assertNotNull(maskedPhone, "PARTIAL mask should not return null");
        assertTrue(maskedPhone.contains("****"), "PARTIAL mask should contain asterisks");
        assertNotEquals("13812345678", maskedPhone, "PARTIAL mask should modify the value");
        // For 11-char input: first 3 + **** + last 4 = "138****5678"
        assertTrue(maskedPhone.startsWith("138"), "Should preserve first 3 chars");
        assertTrue(maskedPhone.endsWith("5678"), "Should preserve last 4 chars");
    }

    @Test
    @Order(7)
    @DisplayName("HASH mask → returns 16-char hex string")
    void testHashMask_ReturnsHexString() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("ssn").maskType("hash").build()
        );

        Map<String, Object> record = new HashMap<>();
        record.put("ssn", "123-45-6789");

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(
                List.of(record), rules);

        String hashed = (String) masked.get(0).get("ssn");
        assertNotNull(hashed, "HASH mask should not return null");
        assertEquals(16, hashed.length(), "HASH mask should return 16-char hex (8 bytes)");
        assertTrue(hashed.matches("[0-9a-f]{16}"), "HASH should be lowercase hex chars");
        assertNotEquals("123-45-6789", hashed, "HASH should modify the value");
    }

    // ==================== Query Path Coverage ====================

    @Test
    @Order(8)
    @DisplayName("list() executes without error with permission context")
    void testList_RunsWithPermissions() {
        String modelCode = findAvailableModel();
        assertNotNull(modelCode, "A published model should be available or created for the test");

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(5)
                .build();

        assertDoesNotThrow(() -> {
            PaginationResult<Map<String, Object>> result =
                    dynamicDataService.list(modelCode, request);
            assertNotNull(result, "list() should return a non-null result");
        }, "list() should execute without error");
    }

    @Test
    @Order(9)
    @DisplayName("aggregate() executes without error with permission context")
    void testAggregate_RunsWithPermissions() {
        String modelCode = findAvailableModel();
        assertNotNull(modelCode, "A published model should be available or created for the test");

        AggregateRequest request = AggregateRequest.builder()
                .aggregateFields(List.of(
                        AggregateRequest.AggregateField.builder()
                                .fieldName("id")
                                .function(AggregateRequest.AggregateFunction.COUNT)
                                .alias("total")
                                .build()
                ))
                .build();

        assertDoesNotThrow(() -> {
            Map<String, Object> result = dynamicDataService.aggregate(modelCode, request);
            assertNotNull(result, "aggregate() should return a non-null result");
        }, "aggregate() should execute without error");
    }

    @Test
    @Order(10)
    @DisplayName("getStats() executes without error with permission context")
    void testGetStats_RunsWithPermissions() {
        String modelCode = findAvailableModel();
        assertNotNull(modelCode, "A published model should be available or created for the test");

        Map<String, Object> params = new HashMap<>();

        assertDoesNotThrow(() -> {
            Map<String, Object> result = dynamicDataService.getStats(modelCode, params);
            assertNotNull(result, "getStats() should return a non-null result");
        }, "getStats() should execute without error");
    }

    // ==================== Policy CRUD Lifecycle ====================

    @Test
    @Order(11)
    @DisplayName("Policy CRUD lifecycle: create → read → update → delete")
    void testPolicyCrud_CreateReadUpdateDelete() {
        // CREATE
        DataPermissionPolicyCreateRequest createReq = new DataPermissionPolicyCreateRequest();
        createReq.setName("CRUD Test Policy");
        createReq.setModelCode("crud_test_model");
        createReq.setPolicyType("row");
        createReq.setScopeType("self");
        createReq.setPriority(10);

        DataPermissionPolicy created = policyService.create(createReq);
        assertNotNull(created, "Create should return a policy");
        assertNotNull(created.getPid(), "Created policy should have a PID");
        assertEquals("CRUD Test Policy", created.getName());
        assertEquals("row", created.getPolicyType());
        assertTrue(created.getEnabled(), "Newly created policy should be enabled by default");

        // READ
        DataPermissionPolicy found = policyService.getByPid(created.getPid());
        assertNotNull(found, "Should find policy by PID");
        assertEquals(created.getPid(), found.getPid());
        assertEquals("CRUD Test Policy", found.getName());

        // UPDATE
        DataPermissionPolicyCreateRequest updateReq = new DataPermissionPolicyCreateRequest();
        updateReq.setName("Updated CRUD Policy");
        updateReq.setModelCode("crud_test_model");
        updateReq.setPolicyType("row");
        updateReq.setScopeType("all");
        updateReq.setPriority(20);

        DataPermissionPolicy updated = policyService.update(created.getPid(), updateReq);
        assertEquals("Updated CRUD Policy", updated.getName());
        assertEquals("all", updated.getScopeType());
        assertEquals(20, updated.getPriority());

        // DISABLE / ENABLE
        policyService.disable(created.getPid());
        DataPermissionPolicy disabled = policyService.getByPid(created.getPid());
        assertFalse(disabled.getEnabled(), "Policy should be disabled");

        policyService.enable(created.getPid());
        DataPermissionPolicy enabled = policyService.getByPid(created.getPid());
        assertTrue(enabled.getEnabled(), "Policy should be re-enabled");

        // DELETE
        policyService.delete(created.getPid());
        DataPermissionPolicy deleted = policyService.getByPid(created.getPid());
        assertNull(deleted, "Deleted policy should not be found");
    }

    // ==================== Preview Filter ====================

    @Test
    @Order(12)
    @DisplayName("previewRowFilter returns a non-null string")
    void testPreviewFilter_ReturnsString() {
        Long userId = MetaContext.getCurrentUserId();

        String preview = policyService.previewRowFilter("any_model", userId);

        assertNotNull(preview, "previewRowFilter should return a non-null string");
        // The result may be empty if no policies are bound, but it should not be null
    }
}
