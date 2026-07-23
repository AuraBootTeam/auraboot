package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamicDataAccessorQueryInIntegrationTest {

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
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserRoleService userRoleService;
    @Autowired
    private DataPermissionPolicyService policyService;

    private DynamicDataAccessorImpl accessor;
    private String modelCode;
    private String tableName;
    private Model model;
    private User testUser;
    private Tenant testTenant;
    private TenantMember testMember;
    private Role testRole;
    private boolean modelInitialized;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        accessor = new DynamicDataAccessorImpl(dynamicDataService);
        if (!modelInitialized) {
            modelCode = "da_query_in_" + Math.abs(System.nanoTime());
            tableName = "mt_" + modelCode.toLowerCase();
            purgeTestArtifacts();
            createModel();
            createFields();
            createPhysicalTable();
            modelInitialized = true;
        }
        ensureRoleBinding();
    }

    @AfterAll
    void cleanup() {
        try {
            purgeTestArtifacts();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("queryIn delegates through DynamicDataService and preserves IN, tenant and soft-delete semantics")
    void queryInPreservesDynamicDataServiceSemantics() {
        String activePid = seedOne("A-100", "active");
        String deletedPid = seedOne("B-200", "active");
        String inactivePid = seedOne("C-300", "inactive");
        MetaContext.runWithoutDataPermission(() -> { dynamicDataService.delete(modelCode, deletedPid); });
        insertOtherTenantRow("X-999");

        List<Map<String, Object>> rows = MetaContext.runWithoutDataPermission(
                () -> accessor.queryIn(modelCode, "code",
                        List.of("A-100", "B-200", "C-300", "X-999", "A-100")));

        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("code")))
                .containsExactlyInAnyOrder("A-100", "C-300");
        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("pid")))
                .containsExactlyInAnyOrder(activePid, inactivePid);
        assertThat(rows)
                .extracting(row -> row.get("tenant_id"))
                .containsOnly(testTenant.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_flag FROM " + tableName + " WHERE pid = ?",
                Boolean.class,
                deletedPid)).isTrue();
    }

    @Test
    @DisplayName("queryIn preserves restrictive row-level SELF data permission")
    void queryInPreservesRestrictiveSelfDataPermission() {
        bindSelfPolicyToCurrentRole();
        String ownPid = seedOne("OWN-100", "active");
        String otherPid = insertRowCreatedBy("OTHER-200", "active", testUser.getId() + 999_999L);

        List<Map<String, Object>> rows = accessor.queryIn(modelCode, "code", List.of("OWN-100", "OTHER-200"));

        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("code")))
                .containsExactly("OWN-100");
        assertThat(rows)
                .extracting(row -> String.valueOf(row.get("pid")))
                .containsExactly(ownPid)
                .doesNotContain(otherPid);
    }

    private void setupTenantContext() {
        if (testUser == null) {
            String email = "da-query-in-test@auraboot.com";
            testUser = userService.findByEmail(email);
            if (testUser == null) {
                testUser = userService.signUp(email, "test-password-123");
            }
        }
        if (testTenant == null) {
            String name = "da-query-in-test-tenant";
            testTenant = tenantService.findByName(name);
            if (testTenant == null) {
                Tenant tenant = new Tenant();
                tenant.setPid(UniqueIdGenerator.generate());
                tenant.setName(name);
                tenant.setDisplayName("DataAccessor queryIn Test Tenant");
                tenant.setStatus("active");
                tenant.setContactEmail("admin@da-query-in-test.com");
                tenant.setDescription("Test tenant for DataAccessor queryIn integration tests");
                tenant.setDeletedFlag(false);
                tenant.setCreatedAt(Instant.now());
                tenant.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(tenant);
            }
        }
        testMember = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (testMember == null) {
            testMember = tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        MetaContext.setMemberId(testMember.getId());
    }

    private void purgeTestArtifacts() {
        if (tableName != null) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        }
        if (testTenant != null) {
            Long tenantId = testTenant.getId();
            jdbcTemplate.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                    + "(SELECT id FROM ab_meta_model WHERE code LIKE 'da_query_in_%' AND tenant_id = ?)", tenantId);
            jdbcTemplate.update("DELETE FROM ab_meta_field WHERE code IN ('pid', 'code', 'status') AND tenant_id = ?",
                    tenantId);
            jdbcTemplate.update("DELETE FROM ab_meta_model WHERE code LIKE 'da_query_in_%' AND tenant_id = ?",
                    tenantId);
            jdbcTemplate.update("""
                    DELETE FROM ab_data_permission_role_binding
                    WHERE tenant_id = ? AND policy_pid IN (
                        SELECT pid FROM ab_data_permission_policy
                        WHERE tenant_id = ? AND model_code LIKE 'da_query_in_%'
                    )
                    """, tenantId, tenantId);
            jdbcTemplate.update("DELETE FROM ab_data_permission_policy WHERE tenant_id = ? AND model_code LIKE 'da_query_in_%'",
                    tenantId);
            jdbcTemplate.update("""
                    DELETE FROM ab_user_role
                    WHERE tenant_id = ? AND role_id IN (
                        SELECT id FROM ab_role
                        WHERE tenant_id = ? AND code LIKE 'da_query_in_role_%'
                    )
                    """, tenantId, tenantId);
            jdbcTemplate.update("DELETE FROM ab_role WHERE tenant_id = ? AND code LIKE 'da_query_in_role_%'", tenantId);
        }
    }

    private void ensureRoleBinding() {
        if (testRole == null) {
            String suffix = Long.toString(Math.abs(System.nanoTime()));
            Role role = new Role();
            role.setPid(UniqueIdGenerator.generate());
            role.setTenantId(testTenant.getId());
            role.setName("DataAccessor queryIn role " + suffix);
            role.setCode("da_query_in_role_" + suffix);
            role.setDescription("Role for DataAccessor queryIn row permission integration tests");
            role.setType("custom");
            role.setScopeType("tenant");
            role.setStatus("active");
            role.setIsDefault(false);
            role.setIsSystem(false);
            role.setDeletedFlag(false);
            role.setPriority(100);
            role.setCreatedAt(Instant.now());
            role.setUpdatedAt(Instant.now());
            testRole = roleService.createRole(role);
        }
        if (userRoleService.findByMemberIdAndRoleIdAndTenantId(
                testMember.getId(), testRole.getId(), testTenant.getId()) == null) {
            userRoleService.assignRolesToMember(testMember.getId(), List.of(testRole.getId()), testTenant.getId(), null);
        }
    }

    private DataPermissionPolicy bindSelfPolicyToCurrentRole() {
        DataPermissionPolicyCreateRequest request = new DataPermissionPolicyCreateRequest();
        request.setName("DataAccessor queryIn self only");
        request.setModelCode(modelCode);
        request.setPolicyType("row");
        request.setScopeType("self");
        request.setPriority(10);
        DataPermissionPolicy policy = policyService.create(request);
        policyService.bindToRole(policy.getPid(), testRole.getPid());
        return policy;
    }

    private void createModel() {
        model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(testTenant.getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("displayName", "DataAccessor queryIn Model");
        attributes.put("modelType", "entity");
        attributes.put("softDelete", true);
        extension.setExtension(attributes);
        model.setExtension(extension);

        metaModelMapper.insert(model);
    }

    private void createFields() {
        createAndBindField("pid", DataType.STRING, true, false, -1);
        createAndBindField("code", DataType.STRING, false, true, 0);
        createAndBindField("status", DataType.STRING, false, false, 1);
    }

    private void createAndBindField(String code, DataType dataType, boolean primaryKey, boolean required, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(testTenant.getId());
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

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("displayName", code.toUpperCase());
        if (primaryKey) {
            attributes.put("primaryKey", true);
        }
        extension.setExtension(attributes);
        field.setExtension(extension);

        metaFieldMapper.insert(field);
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(testTenant.getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        binding.setRequired(required);
        fieldBindingMapper.insert(binding);
    }

    private void createPhysicalTable() {
        SchemaOperationResult result = schemaManagementService.createTableByModel(modelCode);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to create dynamic table: " + result.getErrorMessage());
        }
    }

    private String seedOne(String code, String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("status", status);
        Map<String, Object> created = dynamicDataService.create(modelCode, data);
        return String.valueOf(created.get("pid"));
    }

    private void insertOtherTenantRow(String code) {
        jdbcTemplate.update("INSERT INTO " + tableName
                        + " (pid, tenant_id, code, status, deleted_flag) VALUES (?, ?, ?, ?, FALSE)",
                UniqueIdGenerator.generate(),
                testTenant.getId() + 999_999L,
                code,
                "active");
    }

    private String insertRowCreatedBy(String code, String status, Long createdBy) {
        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update("INSERT INTO " + tableName
                        + " (pid, tenant_id, code, status, deleted_flag, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, FALSE, ?, ?, now(), now())",
                pid,
                testTenant.getId(),
                code,
                status,
                createdBy,
                createdBy);
        return pid;
    }
}
