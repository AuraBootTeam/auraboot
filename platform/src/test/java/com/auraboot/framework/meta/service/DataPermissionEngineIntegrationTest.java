package com.auraboot.framework.meta.service;

import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.dataquality.ge.mapper.AbDataQualityValidationRunMapper;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.Filter;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.ConditionNode;
import com.auraboot.framework.decision.ast.DataType;
import com.auraboot.framework.decision.ast.Operand;
import com.auraboot.framework.decision.ast.Operator;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.PermissionService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
    
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private DataScopeService dataScopeService;
    @Autowired private PermissionService permissionService;
    @Autowired private PermissionMapper qualityPermissionMapper;
    @Autowired private AbDataQualityValidationRunMapper qualityRuns;
    @Autowired private WebApplicationContext qualityWebContext;

    @Autowired private QueryBuilderReadProtection scoringReadProtection;
    @Autowired private com.auraboot.framework.permission.engine.PermissionEvaluator scoringPermissions;
    @Autowired private com.auraboot.framework.permission.service.FieldPermissionService scoringFields;

    // Test-specific data
    private String testSuffix;
    private User localUser;
    private Tenant localTenant;
    private TenantMember localTenantMember;
    private Role localRole;
    
    @BeforeAll
    void initTestSuffix() {
        testSuffix = "_dp_" + System.currentTimeMillis();
    }

    @BeforeEach
    void setUp() {
        localUser = ensureTestUser();
        localTenant = ensureTestTenant();
        localTenantMember = ensureTestTenantMember();
        localRole = createFreshRole();
        ensureUserRoleBinding();
        
        MetaContext.setContext(
            localTenant.getId(),
            localUser.getId(),
            localUser.getPid(),
            localUser.getUserName()
        );
        MetaContext.setMemberId(localTenantMember.getId());
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
    
    private TenantMember ensureTestTenantMember() {
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
            localTenant.getId(), localUser.getId());
        if (existing == null) {
            return tenantMemberService.addMember(localUser.getId(), localTenant.getId(), "active");
        }
        return existing;
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
        UserRole existing = userRoleService.findByMemberIdAndRoleIdAndTenantId(
            localTenantMember.getId(), localRole.getId(), localTenant.getId());
        if (existing == null) {
            userRoleService.assignRolesToMember(
                localTenantMember.getId(),
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

    @ParameterizedTest(name = "read ALL / update SELF / delete NONE; softDelete={0}")
    @ValueSource(booleans = {false, true})
    void dynamicWritesUseTheirOwnScopeAndObserveScopeRevocation(boolean softDelete) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        String modelCode = "scope_write_" + suffix;
        String fieldCode = "scope_value_" + suffix;
        String tableName = "mt_" + modelCode;

        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Action scope write fixture");
        modelRequest.setModelCategory("entity");
        modelRequest.setTableName(tableName);
        modelRequest.setExtension(Map.of("softDelete", softDelete));
        var model = metaModelService.create(modelRequest);
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("string");
        fieldRequest.setAutoPublish(true);
        var field = metaFieldService.create(fieldRequest);
        metaModelService.bindFieldToModel(model.getId(), field.getId(), 1,
                false, true, false, null, null, null, null);
        metaModelService.publish(model.getPid(), "Action scope integration fixture");
        var definition = metaModelService.getModelDefinitionFromDb(modelCode).orElseThrow();
        assertEquals(softDelete, definition.isSoftDelete());
        assertEquals(tableName, definition.getTableName());

        // Public model registration supplies the action catalogue; grant only this fixture.
        List<Long> permissionIds = new ArrayList<>();
        for (String action : List.of("read", "create", "update", "delete")) {
            var permission = permissionService.findByCode("model." + modelCode + "." + action);
            assertNotNull(permission, "Registered action missing: " + action);
            permissionIds.add(permission.getId());
        }
        assertTrue(roleService.assignPermissions(localRole.getId(), permissionIds));
        Long tenantId = localTenant.getId();
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "create", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "update", "self", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "delete", "none", "MAX");

        User actor = userService.signUp("scope-actor-" + suffix + "@auraboot.com", "test-password-123");
        User other = userService.signUp("scope-other-" + suffix + "@auraboot.com", "test-password-123");
        TenantMember actorMember = tenantMemberService.addMember(actor.getId(), tenantId, "active");
        TenantMember otherMember = tenantMemberService.addMember(other.getId(), tenantId, "active");
        assertNotEquals(actor.getId(), other.getId());
        assertNotEquals(actorMember.getId(), otherMember.getId());
        for (TenantMember member : List.of(actorMember, otherMember)) {
            assertTrue(userRoleService.assignRolesToMember(
                    member.getId(), List.of(localRole.getId()), tenantId, localUser.getId()));
            assertEquals(Set.of(localRole.getId()), userRoleService
                    .findByMemberIdAndTenantId(member.getId(), tenantId).stream()
                    .map(UserRole::getRoleId).collect(java.util.stream.Collectors.toSet()));
        }

        try {
            applyScopeActor(actor, actorMember);
            String ownedPid = String.valueOf(dynamicDataService.create(
                    modelCode, Map.of(fieldCode, "own-before")).get("pid"));
            applyScopeActor(other, otherMember);
            String otherPid = String.valueOf(dynamicDataService.create(
                    modelCode, Map.of(fieldCode, "other-before")).get("pid"));
            assertNotEquals(ownedPid, otherPid);
            applyScopeActor(actor, actorMember);

            var listed = dynamicDataService.list(modelCode, DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(10).conditions(List.of()).build());
            assertEquals(2L, listed.getTotal());
            assertEquals(Set.of(ownedPid, otherPid), listed.getRecords().stream()
                    .map(row -> String.valueOf(row.get("pid")))
                    .collect(java.util.stream.Collectors.toSet()));
            assertEquals(actor.getId().longValue(), ((Number) dynamicDataService
                    .getById(modelCode, ownedPid).get("created_by")).longValue());
            assertEquals(other.getId().longValue(), ((Number) dynamicDataService
                    .getById(modelCode, otherPid).get("created_by")).longValue());

            dynamicDataService.update(modelCode, ownedPid, Map.of(fieldCode, "own-updated"));
            assertScopeValue(modelCode, ownedPid, fieldCode, "own-updated");
            assertThrows(MetaServiceException.class, () -> dynamicDataService.update(
                    modelCode, otherPid, Map.of(fieldCode, "forbidden-update")));
            assertScopeValue(modelCode, otherPid, fieldCode, "other-before");
            assertTrue(dynamicDataService.compareAndSet(
                    modelCode, ownedPid, fieldCode, "own-updated", "own-cas"));
            assertFalse(dynamicDataService.compareAndSet(
                    modelCode, otherPid, fieldCode, "other-before", "forbidden-cas"));
            assertScopeValue(modelCode, ownedPid, fieldCode, "own-cas");
            assertScopeValue(modelCode, otherPid, fieldCode, "other-before");

            for (String pid : List.of(ownedPid, otherPid)) {
                assertThrows(MetaServiceException.class, () -> dynamicDataService.delete(modelCode, pid));
            }
            assertThrows(MetaServiceException.class,
                    () -> dynamicDataService.batchDelete(modelCode, List.of(ownedPid, otherPid)));
            assertScopeValue(modelCode, ownedPid, fieldCode, "own-cas");
            assertScopeValue(modelCode, otherPid, fieldCode, "other-before");

            // Tighten the scope through its public service without replacing the caller identity.
            // HTTP action revocation is a separate boundary contract, not claimed by this test.
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "update", "none", "MAX");
            assertEquals("none", dataScopeService.resolveScope(actorMember.getId(), modelCode, "update").scopeType());
            assertThrows(MetaServiceException.class, () -> dynamicDataService.update(
                    modelCode, ownedPid, Map.of(fieldCode, "forbidden-after-revoke")));
            assertFalse(dynamicDataService.compareAndSet(
                    modelCode, ownedPid, fieldCode, "own-cas", "forbidden-after-revoke"));
            assertScopeValue(modelCode, ownedPid, fieldCode, "own-cas");
            assertScopeValue(modelCode, otherPid, fieldCode, "other-before");

            // Read-only SQL verifies the physical rows survived every refused mutation.
            assertEquals(2L, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + tableName + " WHERE tenant_id = ?", Long.class, tenantId));
            if (softDelete) {
                assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName
                        + " WHERE tenant_id = ? AND deleted_flag = true", Long.class, tenantId));
            }
        } finally {
            applyScopeActor(localUser, localTenantMember);
        }
    }

    @Test
    void dataQualityHttpProtectsSourceCountsFieldsAndRevokedHistory() throws Exception {
        String marker = Long.toUnsignedString(System.nanoTime());
        String modelCode = "quality_scope_" + marker;
        String fieldCode = "quality_value_" + marker;
        String tableName = "mt_" + modelCode;
        Long tenantId = localTenant.getId();
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Quality source scope fixture");
        modelRequest.setModelCategory("entity");
        modelRequest.setTableName(tableName);
        var model = metaModelService.create(modelRequest);
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("string");
        fieldRequest.setAutoPublish(true);
        var field = metaFieldService.create(fieldRequest);
        metaModelService.bindFieldToModel(model.getId(), field.getId(), 1,
                false, true, false, null, null, null, null);
        metaModelService.publish(model.getPid(), "Quality source integration fixture");
        assertEquals(tableName, metaModelService.getModelDefinitionFromDb(modelCode).orElseThrow().getTableName());
        Long readId = permissionService.findByCode("model." + modelCode + ".read").getId();
        Long createId = permissionService.findByCode("model." + modelCode + ".create").getId();
        String qualityAction = MetaPermission.META_CHATBI_USE;
        var qualityPermission = qualityPermissionMapper.findByCode(qualityAction);
        Long qualityId;
        if (qualityPermission == null) {
            var request = new PermissionCreateRequest();
            request.setCode(qualityAction);
            request.setName("Quality fixture runtime action");
            request.setResourceType("meta");
            request.setResourceCode("chatbi");
            request.setAction("use");
            qualityId = permissionService.create(request).getId();
        } else {
            qualityId = qualityPermission.getId();
        }
        List<Long> grants = List.of(readId, createId, qualityId);
        assertTrue(roleService.assignPermissions(localRole.getId(), grants));
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "create", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "self", "MAX");
        User actor = userService.signUp("quality-actor-" + marker + "@auraboot.com", "test-password-123");
        User other = userService.signUp("quality-other-" + marker + "@auraboot.com", "test-password-123");
        TenantMember actorMember = tenantMemberService.addMember(actor.getId(), tenantId, "active");
        TenantMember otherMember = tenantMemberService.addMember(other.getId(), tenantId, "active");
        for (TenantMember member : List.of(actorMember, otherMember)) {
            assertTrue(userRoleService.assignRolesToMember(member.getId(), List.of(localRole.getId()),
                    tenantId, localUser.getId()));
            assertEquals(Set.of(localRole.getId()), userRoleService.findByMemberIdAndTenantId(member.getId(), tenantId)
                    .stream().map(UserRole::getRoleId).collect(java.util.stream.Collectors.toSet()));
        }
        try {
            applyScopeActor(actor, actorMember);
            String ownPid = String.valueOf(dynamicDataService.create(modelCode, Map.of(fieldCode, "OK")).get("pid"));
            applyScopeActor(other, otherMember);
            String otherPid = String.valueOf(dynamicDataService.create(modelCode, Map.of(fieldCode, "BAD")).get("pid"));
            assertNotEquals(ownPid, otherPid);
            assertEquals(2L, jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName
                    + " WHERE tenant_id = ?", Long.class, tenantId));
            applyScopeActor(actor, actorMember);
            Filter principalFilter = (request, response, chain) -> {
                applyScopeActor(actor, actorMember);
                var principal = new CustomUserDetails(
                        actor.getUserName(), "test-password", actor.getId(), actor.getPid(),
                        AuthorityUtils.NO_AUTHORITIES,
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities()));
                try {
                    chain.doFilter(request, response);
                } finally {
                    SecurityContextHolder.clearContext();
                    applyScopeActor(actor, actorMember);
                }
            };
            var mvc = MockMvcBuilders.webAppContextSetup(qualityWebContext)
                    .addFilter(principalFilter, "/*").build();
            String expectations = objectMapper.writeValueAsString(List.of(
                    Map.of("expectation_type", "expect_table_row_count_to_be_between",
                            "kwargs", Map.of("min_value", 1, "max_value", 1)),
                    Map.of("expectation_type", "expect_column_values_to_not_be_null",
                            "kwargs", Map.of("column", fieldCode)),
                    Map.of("expectation_type", "expect_column_values_to_be_in_set",
                            "kwargs", Map.of("column", fieldCode, "value_set", List.of("OK")))));
            String body = objectMapper.writeValueAsString(Map.of("suiteName", "quality-" + marker,
                    "datasetName", tableName, "expectationsJson", expectations));
            var created = mvc.perform(MockMvcRequestBuilders
                            .post("/api/dataquality/expectations").contentType("application/json").content(body))
                    .andReturn();
            assertNull(created.getResolvedException(), "Suite creation must complete without a handled exception");
            assertEquals(200, created.getResponse().getStatus());
            String response = created.getResponse().getContentAsString();
            assertTrue(objectMapper.readTree(response).required("ok").asBoolean());
            String suitePid = objectMapper.readTree(response).required("suitePid").asText();
            assertFalse(suitePid.isBlank());
            assertQualityRun(mvc, suitePid, 3, 0, List.of(1L, 0L, 0L));
            assertEquals(1, qualityRuns.listBySuite(tenantId, suitePid).size());

            assertTrue(roleService.removePermissions(localRole.getId(), List.of(readId)));
            assertQualityDeniedWithoutRun(mvc, suitePid, body, tenantId, 1);
            assertTrue(roleService.assignPermissions(localRole.getId(), grants));
            for (String mask : List.of("hide", "partial")) {
                DataPermissionPolicyCreateRequest request = new DataPermissionPolicyCreateRequest();
                request.setName("Quality protected input " + mask + marker);
                request.setModelCode(modelCode);
                request.setPolicyType("column");
                request.setFieldCode(fieldCode);
                request.setMaskType(mask);
                var policy = policyService.create(request);
                policyService.bindToRole(policy.getPid(), localRole.getPid());
                assertQualityDeniedWithoutRun(mvc, suitePid, body, tenantId, 1);
                policyService.disable(policy.getPid());
            }
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
            assertQualityRun(mvc, suitePid, 1, 2, List.of(2L, 0L, 1L));
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "none", "MAX");
            assertQualityRun(mvc, suitePid, 2, 1, List.of(0L, 0L, 0L));
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "self", "MAX");
            assertQualityRun(mvc, suitePid, 3, 0, List.of(1L, 0L, 0L));
            assertEquals(4, qualityRuns.listBySuite(tenantId, suitePid).size());
            mvc.perform(MockMvcRequestBuilders
                            .get("/api/dataquality/expectations/" + suitePid + "/runs"))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.length()").value(4));
            assertTrue(roleService.removePermissions(localRole.getId(), List.of(qualityId)));
            assertQualityDeniedWithoutRun(mvc, suitePid, body, tenantId, 4);
            assertEquals(1L, jdbcTemplate.queryForObject("SELECT count(*) FROM ab_dataquality_expectation_suite"
                    + " WHERE tenant_id = ? AND dataset_name = ?", Long.class, tenantId, tableName));
        } finally {
            applyScopeActor(localUser, localTenantMember);
        }
    }

    @Test
    void structuredQueriesProtectPhysicalAliasesAndObserveReadRevocation() throws Exception {
        String marker = Long.toUnsignedString(System.nanoTime());
        String modelCode = "query_scope_" + marker;
        String visible = "q_visible_" + marker;
        String secret = "q_secret_" + marker;
        String hidden = "q_hidden_" + marker;
        String table = "mt_" + modelCode;
        Long tenantId = localTenant.getId();
        var modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Structured query permission fixture");
        modelRequest.setModelCategory("entity");
        modelRequest.setTableName(table);
        var model = metaModelService.create(modelRequest);
        int sequence = 1;
        for (String code : List.of(visible, secret, hidden)) {
            var fieldRequest = new MetaFieldCreateRequest();
            fieldRequest.setCode(code);
            fieldRequest.setDataType("string");
            fieldRequest.setAutoPublish(true);
            if (code.equals(secret)) fieldRequest.setExtension(Map.of("columnName", "physical_secret_" + marker));
            if (code.equals(hidden)) fieldRequest.setExtension(Map.of("columnName", "physical_hidden_" + marker,
                    "fieldPermission", Map.of("view", List.of("absent_query_reader"))));
            var field = metaFieldService.create(fieldRequest);
            metaModelService.bindFieldToModel(model.getId(), field.getId(), sequence++,
                    false, true, false, null, null, null, null);
        }
        metaModelService.publish(model.getPid(), "Structured query fixture");
        String secretColumn = metaModelService.getColumnName(modelCode, secret);
        String hiddenColumn = metaModelService.getColumnName(modelCode, hidden);
        assertNotEquals(secret, secretColumn);
        assertNotEquals(hidden, hiddenColumn);
        Long readId = permissionService.findByCode("model." + modelCode + ".read").getId();
        Long createId = permissionService.findByCode("model." + modelCode + ".create").getId();
        var queryPermission = qualityPermissionMapper.findByCode("query_builder");
        Long queryId;
        if (queryPermission == null) {
            var action = new PermissionCreateRequest(); action.setCode("query_builder");
            action.setName("Structured query execution"); action.setResourceType("meta");
            action.setResourceCode("query_builder"); action.setAction("execute");
            queryId = permissionService.create(action).getId();
        } else { queryId = queryPermission.getId(); }
        assertTrue(roleService.assignPermissions(localRole.getId(), List.of(readId, createId, queryId)));
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "create", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "self", "MAX");
        User actor = userService.signUp("query-actor-" + marker + "@auraboot.com", "test-password-123");
        User other = userService.signUp("query-other-" + marker + "@auraboot.com", "test-password-123");
        TenantMember actorMember = tenantMemberService.addMember(actor.getId(), tenantId, "active");
        TenantMember otherMember = tenantMemberService.addMember(other.getId(), tenantId, "active");
        for (TenantMember member : List.of(actorMember, otherMember)) {
            assertTrue(userRoleService.assignRolesToMember(member.getId(), List.of(localRole.getId()),
                    tenantId, localUser.getId()));
            assertEquals(Set.of(localRole.getId()), userRoleService.findByMemberIdAndTenantId(member.getId(), tenantId)
                    .stream().map(UserRole::getRoleId).collect(java.util.stream.Collectors.toSet()));
        }
        try {
            applyScopeActor(actor, actorMember);
            String ownPid = String.valueOf(dynamicDataService.create(modelCode,
                    Map.of(visible, "OWN", secret, "12345678901")).get("pid"));
            applyScopeActor(other, otherMember);
            String otherPid = String.valueOf(dynamicDataService.create(modelCode,
                    Map.of(visible, "OTHER", secret, "98765432109")).get("pid"));
            assertNotEquals(ownPid, otherPid);
            applyScopeActor(actor, actorMember);
            Filter principalFilter = (request, response, chain) -> {
                applyScopeActor(actor, actorMember);
                var principal = new CustomUserDetails(actor.getUserName(), "test-password",
                        actor.getId(), actor.getPid(), AuthorityUtils.NO_AUTHORITIES, true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                try { chain.doFilter(request, response); }
                finally { SecurityContextHolder.clearContext(); applyScopeActor(actor, actorMember); }
            };
            var mvc = MockMvcBuilders.webAppContextSetup(qualityWebContext).addFilter(principalFilter, "/*").build();
            Map<String, Object> projection = Map.of("modelCode", modelCode,
                    "fields", List.of("pid", visible, secret));
            var result = queryFixtureResult(mvc, projection, 200);
            assertEquals(1, result.size());
            assertEquals(ownPid, result.get(0).get("pid").asText());
            assertEquals("OWN", result.get(0).get(visible).asText());
            assertEquals("12345678901", result.get(0).get(secretColumn).asText());
            var defaultResult = queryFixtureResult(mvc, Map.of("modelCode", modelCode), 200);
            assertEquals(1, defaultResult.size());
            assertFalse(defaultResult.get(0).has(hiddenColumn));
            assertFalse(defaultResult.get(0).has(hidden));

            // The HTTP DTO accepts registered field codes. Physical aliases are tested at the real planning seam.
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put(visible, visible);
            columns.put(secret, secretColumn);
            columns.put(hidden, hiddenColumn);
            columns.put("secret_alias", secretColumn);
            columns.put("hidden_alias", hiddenColumn);
            assertEquals(Map.of(visible, visible, secret, secretColumn, "secret_alias", secretColumn),
                    scoringReadProtection.visibleColumns(modelCode, columns));
            for (String field : List.of(hidden, "hidden_alias")) {
                for (var use : queryFixtureUses(field)) {
                    var dto = new com.auraboot.framework.meta.dto.QueryBuilderDTO();
                    dto.setModelCode(modelCode);
                    use.accept(dto);
                    assertThrows(org.springframework.security.access.AccessDeniedException.class,
                            () -> scoringReadProtection.prepare(dto, columns, table));
                }
            }
            for (var use : queryFixtureUses(hidden)) {
                var dto = new com.auraboot.framework.meta.dto.QueryBuilderDTO(); dto.setModelCode(modelCode);
                use.accept(dto); queryFixtureResult(mvc, dto, 403);
            }
            // Legal operations must also work: denial alone would allow an always-rejecting planner to pass.
            for (var use : queryFixtureUses(secret).subList(1, 5)) {
                var dto = new com.auraboot.framework.meta.dto.QueryBuilderDTO(); dto.setModelCode(modelCode);
                dto.setFields(List.of(secret)); use.accept(dto);
                var legal = queryFixtureResult(mvc, dto, 200);
                assertEquals(1, legal.size());
                if (dto.getAggregations() == null) assertEquals("12345678901", legal.get(0).get(secretColumn).asText());
                else assertEquals(1L, legal.get(0).get("field_count").asLong());
            }
            var policyRequest = new DataPermissionPolicyCreateRequest();
            policyRequest.setName("Structured query partial " + marker);
            policyRequest.setModelCode(modelCode); policyRequest.setPolicyType("column");
            policyRequest.setFieldCode(secret); policyRequest.setMaskType("partial");
            var policy = policyService.create(policyRequest);
            policyService.bindToRole(policy.getPid(), localRole.getPid());
            var masked = queryFixtureResult(mvc, projection, 200);
            assertEquals(1, masked.size());
            assertEquals(ownPid, masked.get(0).get("pid").asText());
            assertEquals("123****8901", masked.get(0).get(secretColumn).asText());
            for (String field : List.of(secret, "secret_alias")) {
                for (var use : queryFixtureUses(field).subList(1, 5)) {
                    var dto = new com.auraboot.framework.meta.dto.QueryBuilderDTO(); dto.setModelCode(modelCode);
                    use.accept(dto);
                    assertThrows(org.springframework.security.access.AccessDeniedException.class,
                            () -> scoringReadProtection.prepare(dto, columns, table));
                }
            }
            for (var use : queryFixtureUses(secret).subList(1, 5)) {
                var dto = new com.auraboot.framework.meta.dto.QueryBuilderDTO(); dto.setModelCode(modelCode);
                use.accept(dto); queryFixtureResult(mvc, dto, 403);
            }
            policyService.disable(policy.getPid());
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
            var all = queryFixtureResult(mvc, projection, 200);
            assertEquals(Set.of(ownPid, otherPid), java.util.stream.StreamSupport.stream(all.spliterator(), false)
                    .map(row -> row.get("pid").asText()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(roleService.removePermissions(localRole.getId(), List.of(readId)));
            queryFixtureResult(mvc, projection, 403);
                assertTrue(roleService.assignPermissions(localRole.getId(), List.of(readId, createId, queryId)));
            assertTrue(roleService.removePermissions(localRole.getId(), List.of(queryId)));
            assertTrue(scoringPermissions.canAction(actorMember.getId(), modelCode, "read"));
            queryFixtureResult(mvc, projection, 403);
            assertTrue(roleService.assignPermissions(localRole.getId(), List.of(readId, createId, queryId)));
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "none", "MAX");
            assertEquals(0, queryFixtureResult(mvc, projection, 200).size());
            List<Map<String, Object>> stored = jdbcTemplate.queryForList("SELECT pid, " + visible + ", "
                    + secretColumn + " FROM " + table + " WHERE tenant_id = ?", tenantId);
            assertEquals(2, stored.size());
            Map<String, String> persisted = new HashMap<>();
            for (var row : stored) persisted.put(String.valueOf(row.get("pid")), String.valueOf(row.get(secretColumn)));
            assertEquals(Map.of(ownPid, "12345678901", otherPid, "98765432109"), persisted);
        } finally { applyScopeActor(localUser, localTenantMember); }
    }

    private com.fasterxml.jackson.databind.JsonNode queryFixtureResult(MockMvc mvc, Object request, int status) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/api/query-builder/execute")
                .contentType("application/json").content(objectMapper.writeValueAsString(request)))
                .andExpect(MockMvcResultMatchers.status().is(status))
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(status == 200 ? 0 : 403))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private List<java.util.function.Consumer<com.auraboot.framework.meta.dto.QueryBuilderDTO>> queryFixtureUses(String field) {
        return List.of(dto -> dto.setFields(List.of(field)),
                dto -> { var filter = new com.auraboot.framework.meta.dto.QueryBuilderDTO.FilterCondition();
                    filter.setFieldName(field); filter.setOperator("EQ"); filter.setValue("12345678901");
                    dto.setFilters(List.of(filter)); },
                dto -> dto.setSortField(field), dto -> dto.setGroupBy(List.of(field)),
                dto -> { var metric = new com.auraboot.framework.meta.dto.QueryBuilderDTO.AggregationConfig();
                    metric.setFieldCode(field); metric.setFunction("COUNT"); metric.setAlias("field_count");
                    dto.setAggregations(List.of(metric)); });
    }

    @Test
    void aiScoringProtectsActualSourcesWritesAndProviderTimeRevocation() throws Exception {
        String marker = Long.toUnsignedString(System.nanoTime());
        String modelCode = "scoring_scope_" + marker;
        String contextField = "scoring_context_" + marker;
        String scoreField = "scoring_score_" + marker;
        String readOnlyScore = "scoring_readonly_" + marker;
        String tableName = "mt_" + modelCode;
        Long tenantId = localTenant.getId();
        var modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Scoring source and write fixture");
        modelRequest.setModelCategory("entity");
        modelRequest.setTableName(tableName);
        var model = metaModelService.create(modelRequest);
        int sequence = 1;
        for (String code : List.of(contextField, scoreField, readOnlyScore)) {
            var fieldRequest = new MetaFieldCreateRequest();
            fieldRequest.setCode(code);
            fieldRequest.setDataType(code.equals(contextField) ? "string" : "integer");
            fieldRequest.setAutoPublish(true);
            if (code.equals(readOnlyScore)) {
                fieldRequest.setExtension(Map.of("fieldPermission", Map.of(
                        "view", List.of(localRole.getCode()), "edit", List.of("absent_scoring_editor"))));
            }
            var field = metaFieldService.create(fieldRequest);
            metaModelService.bindFieldToModel(model.getId(), field.getId(), sequence++,
                    false, true, false, null, null, null, null);
        }
        metaModelService.publish(model.getPid(), "Scoring source integration fixture");
        Long readId = permissionService.findByCode("model." + modelCode + ".read").getId();
        Long createId = permissionService.findByCode("model." + modelCode + ".create").getId();
        Long updateId = permissionService.findByCode("model." + modelCode + ".update").getId();
        List<Long> grants = List.of(readId, createId, updateId);
        assertTrue(roleService.assignPermissions(localRole.getId(), grants));
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "create", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
        dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "update", "self", "MAX");
        User actor = userService.signUp("scoring-actor-" + marker + "@auraboot.com", "test-password-123");
        User other = userService.signUp("scoring-other-" + marker + "@auraboot.com", "test-password-123");
        TenantMember actorMember = tenantMemberService.addMember(actor.getId(), tenantId, "active");
        TenantMember otherMember = tenantMemberService.addMember(other.getId(), tenantId, "active");
        for (TenantMember member : List.of(actorMember, otherMember)) {
            assertTrue(userRoleService.assignRolesToMember(member.getId(), List.of(localRole.getId()),
                    tenantId, localUser.getId()));
            assertEquals(Set.of(localRole.getId()), userRoleService.findByMemberIdAndTenantId(member.getId(), tenantId)
                    .stream().map(UserRole::getRoleId).collect(java.util.stream.Collectors.toSet()));
        }
        // Only the external provider/config boundary is substituted. Internal dependencies are real beans.
        var factory = org.mockito.Mockito.mock(com.auraboot.framework.agent.provider.LlmProviderFactory.class);
        var provider = org.mockito.Mockito.mock(com.auraboot.framework.agent.provider.LlmProvider.class);
        var config = com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai").defaultModel("fixed-scoring-provider")
                .apiKey("fixture-key").baseUrl("https://provider.invalid").build();
        org.mockito.Mockito.when(factory.resolveConfig(tenantId, null)).thenReturn(config);
        org.mockito.Mockito.when(factory.getProvider("openai")).thenReturn(provider);
        var service = new com.auraboot.framework.agent.service.PlatformAiScoringServiceImpl(
                factory, metaModelService, dynamicDataService, scoringReadProtection,
                scoringPermissions, scoringFields, dataPermissionEngine, objectMapper);
        var request = new com.auraboot.framework.agent.dto.PlatformAiScoreRequest();
        request.setModelCode(modelCode);
        request.setContextFields(List.of(contextField));
        request.setScoreField(scoreField);
        request.setScoringDimensions(List.of());
        request.setBatchSize(1);
        List<String> prompts = new ArrayList<>();
        var providerJson = new java.util.concurrent.atomic.AtomicReference<String>();
        var revokeAtProvider = new java.util.concurrent.atomic.AtomicBoolean(false);
        org.mockito.Mockito.when(provider.chat(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    com.auraboot.framework.agent.dto.LlmChatRequest input = invocation.getArgument(0);
                    prompts.add(assertInstanceOf(String.class, input.getMessages().getFirst().getContent()));
                    if (revokeAtProvider.get()) {
                        assertTrue(roleService.removePermissions(localRole.getId(), List.of(updateId)));
                        assertFalse(scoringPermissions.canAction(actorMember.getId(), modelCode, "update"));
                    }
                    return com.auraboot.framework.agent.dto.LlmChatResponse.builder()
                            .content(List.of(com.auraboot.framework.agent.dto.LlmChatResponse.ContentBlock.builder()
                                    .type("text").text(providerJson.get()).build()))
                            .inputTokens(17).outputTokens(9).build();
                });
        try {
            applyScopeActor(actor, actorMember);
            String ownPid = String.valueOf(dynamicDataService.create(modelCode,
                    Map.of(contextField, "OWN_VISIBLE", scoreField, 11)).get("pid"));
            applyScopeActor(other, otherMember);
            String otherPid = String.valueOf(dynamicDataService.create(modelCode,
                    Map.of(contextField, "OTHER_PRIVATE", scoreField, 22)).get("pid"));
            assertNotEquals(ownPid, otherPid);
            applyScopeActor(actor, actorMember);
            assertFalse(scoringFields.getFieldPermissions(actorMember.getId(), modelCode)
                    .editableFields().contains(readOnlyScore));
            assertTrue(scoringFields.getFieldPermissions(actorMember.getId(), modelCode)
                    .viewableFields().contains(readOnlyScore));
            assertTrue(scoringPermissions.canAction(actorMember.getId(), modelCode, "read"));
            assertTrue(scoringPermissions.canAction(actorMember.getId(), modelCode, "update"));
            providerJson.set(objectMapper.writeValueAsString(List.of(Map.of("id", ownPid, "score", 85))));
            var result = service.score(request, tenantId);
            assertEquals(1, result.getScoredCount());
            assertEquals(0, result.getFailedCount());
            assertEquals(Map.of(ownPid, 85), result.getScores());
            assertEquals(17, result.getTotalInputTokens());
            assertEquals(9, result.getTotalOutputTokens());
            assertEquals(1, prompts.size());
            assertTrue(prompts.getFirst().contains(ownPid));
            assertTrue(prompts.getFirst().contains("OWN_VISIBLE"));
            assertFalse(prompts.getFirst().contains(otherPid));
            assertFalse(prompts.getFirst().contains("OTHER_PRIVATE"));
            assertScoringStored(tableName, scoreField, ownPid, otherPid, 85, 22);

            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "self", "MAX");
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "update", "all", "MAX");
            providerJson.set(objectMapper.writeValueAsString(List.of(Map.of("id", ownPid, "score", 87))));
            assertEquals(Map.of(ownPid, 87), service.score(request, tenantId).getScores());
            assertEquals(2, prompts.size());
            assertTrue(prompts.get(1).contains(ownPid));
            assertFalse(prompts.get(1).contains(otherPid));
            assertFalse(prompts.get(1).contains("OTHER_PRIVATE"));
            assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);

            // ALL permits the second row generally, but it is not a member of this request's batch.
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
            request.setRecordPids(List.of(ownPid));
            for (String invalidPid : List.of(otherPid, ownPid)) {
                providerJson.set(objectMapper.writeValueAsString(List.of(
                        Map.of("id", ownPid, "score", 99), Map.of("id", invalidPid, "score", 98))));
                var rejectedBatch = service.score(request, tenantId);
                assertEquals(0, rejectedBatch.getScoredCount());
                assertEquals(1, rejectedBatch.getFailedCount());
                assertEquals(Map.of(), rejectedBatch.getScores());
                assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);
            }
            int providerCalls = prompts.size();
            assertEquals(4, providerCalls);
            for (Long actionId : List.of(readId, updateId)) {
                assertTrue(roleService.removePermissions(localRole.getId(), List.of(actionId)));
                assertThrows(org.springframework.security.access.AccessDeniedException.class,
                        () -> service.score(request, tenantId));
                assertEquals(providerCalls, prompts.size());
                assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);
                assertTrue(roleService.assignPermissions(localRole.getId(), grants));
            }
            for (String mask : List.of("hide", "partial")) {
                var policyRequest = new DataPermissionPolicyCreateRequest();
                policyRequest.setName("Scoring protected input " + mask + marker);
                policyRequest.setModelCode(modelCode);
                policyRequest.setPolicyType("column");
                policyRequest.setFieldCode(contextField);
                policyRequest.setMaskType(mask);
                var policy = policyService.create(policyRequest);
                policyService.bindToRole(policy.getPid(), localRole.getPid());
                assertThrows(org.springframework.security.access.AccessDeniedException.class,
                        () -> service.score(request, tenantId));
                assertEquals(providerCalls, prompts.size());
                assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);
                policyService.disable(policy.getPid());
            }
            request.setScoreField(readOnlyScore);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> service.score(request, tenantId));
            assertEquals(providerCalls, prompts.size());
            assertEquals(0L, jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableName
                    + " WHERE tenant_id = ? AND " + readOnlyScore + " IS NOT NULL", Long.class, tenantId));
            request.setScoreField(scoreField);
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "none", "MAX");
            var noRows = service.score(request, tenantId);
            assertEquals(0, noRows.getScoredCount());
            assertEquals(0, noRows.getFailedCount());
            assertEquals(Map.of(), noRows.getScores());
            assertEquals(providerCalls, prompts.size());
            assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);
            dataScopeService.setScope(tenantId, localRole.getId(), modelCode, "read", "all", "MAX");
            providerJson.set(objectMapper.writeValueAsString(List.of(Map.of("id", ownPid, "score", 91))));
            revokeAtProvider.set(true);
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    () -> service.score(request, tenantId));
            assertEquals(providerCalls + 1, prompts.size());
            assertScoringStored(tableName, scoreField, ownPid, otherPid, 87, 22);
            assertFalse(scoringPermissions.canAction(actorMember.getId(), modelCode, "update"));
            assertEquals(Set.of(localRole.getId()), userRoleService.findByMemberIdAndTenantId(actorMember.getId(), tenantId)
                    .stream().map(UserRole::getRoleId).collect(java.util.stream.Collectors.toSet()));
        } finally {
            applyScopeActor(localUser, localTenantMember);
        }
    }

    private void assertScoringStored(String tableName, String scoreField, String ownPid, String otherPid,
                                     int ownScore, int otherScore) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT pid, " + scoreField
                + " FROM " + tableName + " WHERE tenant_id = ?", localTenant.getId());
        assertEquals(2, rows.size());
        Map<String, Integer> scores = new HashMap<>();
        for (Map<String, Object> row : rows) {
            scores.put(String.valueOf(row.get("pid")), ((Number) row.get(scoreField)).intValue());
        }
        assertEquals(Map.of(ownPid, ownScore, otherPid, otherScore), scores);
    }

    private void assertQualityRun(MockMvc mvc, String suitePid,
                                  int passed, int failed, List<Long> values) throws Exception {
        String response = mvc.perform(MockMvcRequestBuilders
                        .post("/api/dataquality/expectations/" + suitePid + "/run"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.ok").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.totalExpectations").value(3))
                .andExpect(MockMvcResultMatchers.jsonPath("$.passed").value(passed))
                .andExpect(MockMvcResultMatchers.jsonPath("$.failed").value(failed))
                .andReturn().getResponse().getContentAsString();
        String pid = objectMapper.readTree(response).required("runPid").asText();
        var matches = qualityRuns.listBySuite(localTenant.getId(), suitePid).stream()
                .filter(run -> pid.equals(run.getPid())).toList();
        assertEquals(1, matches.size());
        var run = matches.getFirst();
        assertEquals(3, run.getTotalExpectations());
        assertEquals(passed, run.getPassed());
        assertEquals(failed, run.getFailed());
        var results = objectMapper.readTree(run.getResultsJson());
        assertEquals(3, results.size());
        for (int i = 0; i < values.size(); i++) {
            assertEquals(values.get(i).longValue(), results.get(i).required("actual_value").asLong());
        }
    }

    private void assertQualityDeniedWithoutRun(MockMvc mvc,
                                               String suitePid, String body, Long tenantId,
                                               int previousRuns) throws Exception {
        mvc.perform(MockMvcRequestBuilders
                        .post("/api/dataquality/expectations").contentType("application/json").content(body))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders
                        .post("/api/dataquality/expectations/" + suitePid + "/run"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders
                        .get("/api/dataquality/expectations/" + suitePid + "/runs"))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
        assertEquals(previousRuns, qualityRuns.listBySuite(tenantId, suitePid).size());
    }

    private void applyScopeActor(User user, TenantMember member) {
        MetaContext.setContext(localTenant.getId(), user.getId(), user.getPid(), user.getUserName());
        MetaContext.setMemberId(member.getId());
    }

    private void assertScopeValue(String modelCode, String pid, String fieldCode, String expected) {
        Map<String, Object> record = dynamicDataService.getById(modelCode, pid);
        assertNotNull(record, "Refused mutation must preserve the readable record: " + pid);
        assertEquals(expected, record.get(fieldCode));
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
    @DisplayName("Engine: getNonWritableFields returns fields for FIELD_WRITE policies (gap #1)")
    void testGetNonWritableFields() {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("Readonly Credit Limit");
        req.setModelCode("write_model");
        req.setPolicyType("field_write");
        req.setFieldCode("credit_limit");
        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), localRole.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        java.util.Set<String> nonWritable =
                dataPermissionEngine.getNonWritableFields(tenantId, "write_model", userId);

        assertTrue(nonWritable.contains("credit_limit"));
    }

    @Test
    @DisplayName("Engine: getNonWritableFields empty when no FIELD_WRITE policy applies (gap #1)")
    void testGetNonWritableFieldsEmptyWhenNoPolicy() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        java.util.Set<String> nonWritable =
                dataPermissionEngine.getNonWritableFields(tenantId, "unpoliced_model_" + testSuffix, userId);

        assertTrue(nonWritable.isEmpty());
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

    // ==================== B2: CUSTOM condition_ast → SQL ====================

    @Test
    @DisplayName("B2: CUSTOM condition_ast compiles to SQL that filters real rows")
    void engine_customAst_buildsSqlThatFiltersRealRows() {
        String model = "ast_region_model";
        ConditionNode ast = eq(recordPath("region"), literal("EAST"));
        createBoundCustomAstPolicy("ast-region", model, ast);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, model, userId);

        assertNotNull(filter);
        assertTrue(filter.contains("region = 'EAST'"), "filter was: " + filter);

        String table = "b2_region" + testSuffix;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGINT, region TEXT)");
            jdbcTemplate.update("DELETE FROM " + table);
            jdbcTemplate.update("INSERT INTO " + table + " (id, region) VALUES (1, 'EAST'), (2, 'WEST'), (3, 'EAST')");

            Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            Integer matched = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE 1=1 " + filter, Integer.class);

            assertEquals(3, total);
            assertEquals(2, matched, "generated SQL should match only EAST rows");
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("B2: CUSTOM condition_ast resolves actor.id to the current user in SQL")
    void engine_customAst_resolvesActorIdToCurrentUser() {
        String model = "ast_owner_model";
        ConditionNode ast = eq(recordPath("owner_id"),
                new Operand.PathOperand(Scope.ACTOR, "id", DataType.USER));
        createBoundCustomAstPolicy("ast-owner", model, ast);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, model, userId);

        assertNotNull(filter);
        assertTrue(filter.contains("owner_id = " + userId), "filter was: " + filter);

        String table = "b2_owner" + testSuffix;
        try {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGINT, owner_id BIGINT)");
            jdbcTemplate.update("DELETE FROM " + table);
            jdbcTemplate.update("INSERT INTO " + table + " (id, owner_id) VALUES (1, ?), (2, ?)",
                    userId, userId + 1);

            Integer matched = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE 1=1 " + filter, Integer.class);
            assertEquals(1, matched, "only the row owned by the current user should match");
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("B2: CUSTOM condition_ast is evaluated in-memory by canAccessRecord")
    void engine_customAst_inMemoryCanAccessRecordEvaluatesAst() {
        String model = "ast_access_model";
        ConditionNode ast = eq(recordPath("region"), literal("EAST"));
        createBoundCustomAstPolicy("ast-access", model, ast);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        assertTrue(dataPermissionEngine.canAccessRecord(tenantId, model, userId, Map.of("region", "EAST")));
        assertFalse(dataPermissionEngine.canAccessRecord(tenantId, model, userId, Map.of("region", "WEST")));
        // missing field → UNKNOWN → deny
        assertFalse(dataPermissionEngine.canAccessRecord(tenantId, model, userId, Map.of("other", "x")));
    }

    @Test
    @DisplayName("B2: untranslatable CUSTOM condition_ast fails closed to 1=0")
    void engine_customAst_rejectedConditionFailsClosed() {
        String model = "ast_reject_model";
        // A function operand on the value side is not translatable to a row-filter SQL fragment.
        ConditionNode ast = ConditionNode.CompareNode.of(
                recordPath("created_at"), Operator.LT,
                new Operand.FunctionCallOperand("now", List.of(), DataType.DATETIME));
        createBoundCustomAstPolicy("ast-reject", model, ast);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String filter = dataPermissionEngine.buildRowFilter(tenantId, model, userId);

        assertNotNull(filter);
        assertTrue(filter.contains("1=0"), "rejected AST should fail closed; filter was: " + filter);
    }

    private DataPermissionPolicy createBoundCustomAstPolicy(String name, String modelCode, ConditionNode ast) {
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName(name);
        req.setModelCode(modelCode);
        req.setPolicyType("row");
        req.setScopeType("custom");
        req.setConditionAst(toWire(ast));
        req.setPriority(10);
        DataPermissionPolicy policy = policyService.create(req);
        policyService.bindToRole(policy.getPid(), localRole.getPid());
        return policy;
    }

    /** Serialize the AST through its polymorphic base type (emits {@code type} discriminators), then
     * read it back to a plain Object — exactly the JSON wire form the controller/UI would send. */
    private Object toWire(ConditionNode ast) {
        try {
            String json = objectMapper.writerFor(ConditionNode.class).writeValueAsString(ast);
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Operand recordPath(String field) {
        return new Operand.PathOperand(Scope.RECORD, "data." + field, DataType.STRING);
    }

    private static Operand literal(Object value) {
        return new Operand.LiteralOperand(value, null);
    }

    private static ConditionNode eq(Operand left, Operand right) {
        return ConditionNode.CompareNode.of(left, Operator.EQ, right);
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
