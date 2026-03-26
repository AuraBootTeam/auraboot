package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DataPermissionEngine and DataPermissionPolicyService.
 * 
 * Fully self-contained - creates its own test data to avoid conflicts.
 *
 * @since 5.1.0
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("P5-1: Data Permission Engine Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataPermissionEngineIntegrationTest {

    @Autowired
    private DataPermissionPolicyService policyService;

    @Autowired
    private DataPermissionEngine dataPermissionEngine;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private TenantService tenantService;
    
    @Autowired
    private TenantMemberService tenantMemberService;
    
    @Autowired
    private RoleService roleService;
    
    @Autowired
    private UserRoleService userRoleService;
    
    // Test-specific data
    private String testSuffix;
    private User localUser;
    private Tenant localTenant;
    private Role localRole;
    
    @BeforeAll
    void initTestSuffix() {
        testSuffix = "_dp_" + System.currentTimeMillis();
    }

    @BeforeEach
    void setUp() {
        localUser = ensureTestUser();
        localTenant = ensureTestTenant();
        ensureTestTenantMember();
        localRole = createFreshRole();
        ensureUserRoleBinding();
        
        MetaContext.setContext(
            localTenant.getId(),
            localUser.getId(),
            localUser.getPid(),
            localUser.getUserName()
        );
    }
    
    private User ensureTestUser() {
        String email = "dp-test" + testSuffix + "@auraboot.com";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        return userService.signUp(email, "test-password-123");
    }
    
    private Tenant ensureTestTenant() {
        String tenantName = "dp-test-tenant" + testSuffix;
        Tenant existing = tenantService.findByName(tenantName);
        if (existing != null) {
            return existing;
        }
        
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(tenantName);
        tenant.setDisplayName("Data Permission Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@dp-test.com");
        tenant.setDescription("Data permission integration test tenant");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }
    
    private void ensureTestTenantMember() {
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
            localTenant.getId(), localUser.getId());
        if (existing == null) {
            tenantMemberService.addMember(localUser.getId(), localTenant.getId(), "active");
        }
    }
    
    private Role createFreshRole() {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("dp_test_role" + testSuffix + "_" + System.nanoTime());
        role.setCode("dp_test_role" + testSuffix + "_" + System.nanoTime());
        role.setDescription("Data permission test role");
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(localTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return roleService.createRole(role);
    }
    
    private void ensureUserRoleBinding() {
        UserRole existing = userRoleService.findByUserIdAndRoleIdAndTenantId(
            localUser.getId(), localRole.getId(), localTenant.getId());
        if (existing == null) {
            userRoleService.assignRolesToUser(
                localUser.getId(),
                Arrays.asList(localRole.getId()),
                localTenant.getId(),
                null
            );
        }
    }

    // ==================== Policy CRUD ====================

    @Test
    @DisplayName("Create ROW policy with SELF scope")
    void testCreateRowPolicySelfScope() {
        DataPermissionPolicyCreateRequest request = new DataPermissionPolicyCreateRequest();
        request.setName("Self Data Only");
        request.setModelCode("order");
        request.setPolicyType("row");
        request.setScopeType("self");
        request.setPriority(10);

        DataPermissionPolicy policy = policyService.create(request);

        assertNotNull(policy);
        assertNotNull(policy.getPid());
        assertEquals("Self Data Only", policy.getName());
        assertEquals("order", policy.getModelCode());
        assertEquals("row", policy.getPolicyType());
        assertEquals("self", policy.getScopeType());
        assertTrue(policy.getEnabled());
    }

    @Test
    @DisplayName("Create COLUMN policy with PARTIAL mask")
    void testCreateColumnPolicyPartialMask() {
        DataPermissionPolicyCreateRequest request = new DataPermissionPolicyCreateRequest();
        request.setName("Mask Phone Number");
        request.setModelCode("customer");
        request.setPolicyType("column");
        request.setFieldCode("phone");
        request.setMaskType("partial");
        request.setPriority(5);

        DataPermissionPolicy policy = policyService.create(request);

        assertNotNull(policy);
        assertEquals("column", policy.getPolicyType());
        assertEquals("phone", policy.getFieldCode());
        assertEquals("partial", policy.getMaskType());
    }

    @Test
    @DisplayName("Get policy by PID")
    void testGetByPid() {
        DataPermissionPolicy created = createTestRowPolicy("test-get", "product");
        DataPermissionPolicy found = policyService.getByPid(created.getPid());

        assertNotNull(found);
        assertEquals(created.getPid(), found.getPid());
        assertEquals(created.getName(), found.getName());
    }

    @Test
    @DisplayName("List policies by model code")
    void testListByModelCode() {
        createTestRowPolicy("policy-a", "invoice");
        createTestRowPolicy("policy-b", "invoice");
        createTestRowPolicy("policy-c", "order");

        List<DataPermissionPolicy> invoicePolicies = policyService.listByModelCode("invoice");
        assertTrue(invoicePolicies.size() >= 2);
        invoicePolicies.forEach(p -> assertEquals("invoice", p.getModelCode()));
    }

    @Test
    @DisplayName("Update policy")
    void testUpdatePolicy() {
        DataPermissionPolicy created = createTestRowPolicy("update-me", "product");

        DataPermissionPolicyCreateRequest updateReq = new DataPermissionPolicyCreateRequest();
        updateReq.setName("Updated Name");
        updateReq.setModelCode("product");
        updateReq.setPolicyType("row");
        updateReq.setScopeType("all");
        updateReq.setPriority(20);

        DataPermissionPolicy updated = policyService.update(created.getPid(), updateReq);

        assertEquals("Updated Name", updated.getName());
        assertEquals("all", updated.getScopeType());
        assertEquals(20, updated.getPriority());
    }

    @Test
    @DisplayName("Delete policy removes bindings")
    void testDeletePolicy() {
        DataPermissionPolicy created = createTestRowPolicy("delete-me", "product");
        policyService.bindToRole(created.getPid(), localRole.getPid());

        policyService.delete(created.getPid());

        assertNull(policyService.getByPid(created.getPid()));
    }

    @Test
    @DisplayName("Enable and disable policy")
    void testEnableDisable() {
        DataPermissionPolicy created = createTestRowPolicy("toggle-me", "product");

        policyService.disable(created.getPid());
        DataPermissionPolicy disabled = policyService.getByPid(created.getPid());
        assertFalse(disabled.getEnabled());

        policyService.enable(created.getPid());
        DataPermissionPolicy enabled = policyService.getByPid(created.getPid());
        assertTrue(enabled.getEnabled());
    }

    // ==================== Role Binding ====================

    @Test
    @DisplayName("Bind and unbind policy to role")
    void testBindUnbindRole() {
        DataPermissionPolicy policy = createTestRowPolicy("bind-test", "order");

        assertDoesNotThrow(() -> policyService.bindToRole(policy.getPid(), localRole.getPid()));
        assertDoesNotThrow(() -> policyService.bindToRole(policy.getPid(), localRole.getPid()));
        assertDoesNotThrow(() -> policyService.unbindFromRole(policy.getPid(), localRole.getPid()));
    }

    // ==================== Engine: Row Filter ====================

    @Test
    @DisplayName("Engine: buildRowFilter returns empty for ALL scope")
    void testBuildRowFilterAll() {
        DataPermissionPolicy policy = createTestRowPolicy("all-scope", "test_model");
        policy = policyService.update(policy.getPid(), buildRowRequest("all-scope", "test_model", "all"));
        policyService.bindToRole(policy.getPid(), localRole.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "test_model", userId);

        assertTrue(filter == null || filter.isBlank());
    }

    @Test
    @DisplayName("Engine: buildRowFilter returns created_by condition for SELF scope")
    void testBuildRowFilterSelf() {
        DataPermissionPolicyCreateRequest req = buildRowRequest("self-scope", "self_model", "self");
        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), localRole.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "self_model", userId);

        assertNotNull(filter);
        assertTrue(filter.contains("created_by"));
        assertTrue(filter.contains(String.valueOf(userId)));
    }

    @Test
    @DisplayName("Engine: buildRowFilter returns empty when no policies match")
    void testBuildRowFilterNoPolicies() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, "nonexistent_model", userId);

        assertTrue(filter == null || filter.isBlank());
    }

    // ==================== Engine: Field Masking ====================

    @Test
    @DisplayName("Engine: getFieldMaskRules returns rules for COLUMN policies")
    void testGetFieldMaskRules() {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("Mask Email");
        req.setModelCode("mask_model");
        req.setPolicyType("column");
        req.setFieldCode("email");
        req.setMaskType("partial");
        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), localRole.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        List<FieldMaskRule> rules = dataPermissionEngine.getFieldMaskRules(tenantId, "mask_model", userId);

        assertFalse(rules.isEmpty());
        assertEquals("email", rules.get(0).getFieldCode());
        assertEquals("partial", rules.get(0).getMaskType());
    }

    @Test
    @DisplayName("Engine: applyFieldMasking HIDE sets value to null")
    void testApplyFieldMaskingHide() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("secret").maskType("hide").build()
        );

        List<Map<String, Object>> records = List.of(
                Map.of("id", 1, "name", "test", "secret", "sensitive-data")
        );

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(records, rules);

        assertNull(masked.get(0).get("secret"));
        assertEquals("test", masked.get(0).get("name"));
    }

    @Test
    @DisplayName("Engine: applyFieldMasking PARTIAL masks middle chars")
    void testApplyFieldMaskingPartial() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("phone").maskType("partial").build()
        );

        List<Map<String, Object>> records = List.of(
                Map.of("phone", "13812345678")
        );

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(records, rules);

        String maskedPhone = (String) masked.get(0).get("phone");
        assertNotNull(maskedPhone);
        assertTrue(maskedPhone.contains("****"));
        assertNotEquals("13812345678", maskedPhone);
    }

    @Test
    @DisplayName("Engine: applyFieldMasking HASH produces hex string")
    void testApplyFieldMaskingHash() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("ssn").maskType("hash").build()
        );

        List<Map<String, Object>> records = List.of(
                Map.of("ssn", "123-45-6789")
        );

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(records, rules);

        String hashed = (String) masked.get(0).get("ssn");
        assertNotNull(hashed);
        assertEquals(16, hashed.length());
        assertNotEquals("123-45-6789", hashed);
    }

    @Test
    @DisplayName("Engine: applyFieldMasking handles null records gracefully")
    void testApplyFieldMaskingNullRecords() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("x").maskType("hide").build()
        );

        List<Map<String, Object>> result = dataPermissionEngine.applyFieldMasking(null, rules);
        assertNull(result);

        result = dataPermissionEngine.applyFieldMasking(List.of(), rules);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Engine: applyFieldMasking skips fields not in record")
    void testApplyFieldMaskingMissingField() {
        List<FieldMaskRule> rules = List.of(
                FieldMaskRule.builder().fieldCode("nonexistent").maskType("hide").build()
        );

        Map<String, Object> record = new HashMap<>();
        record.put("name", "visible");
        List<Map<String, Object>> records = List.of(record);

        List<Map<String, Object>> masked = dataPermissionEngine.applyFieldMasking(records, rules);
        assertEquals("visible", masked.get(0).get("name"));
    }

    // ==================== Helpers ====================

    private DataPermissionPolicy createTestRowPolicy(String name, String modelCode) {
        DataPermissionPolicyCreateRequest req = buildRowRequest(name, modelCode, "self");
        return policyService.create(req);
    }

    private DataPermissionPolicyCreateRequest buildRowRequest(String name, String modelCode, String scopeType) {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName(name);
        req.setModelCode(modelCode);
        req.setPolicyType("row");
        req.setScopeType(scopeType);
        req.setPriority(10);
        return req;
    }
}
