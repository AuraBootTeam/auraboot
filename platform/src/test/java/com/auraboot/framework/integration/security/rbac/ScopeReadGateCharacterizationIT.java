package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor;
import com.auraboot.framework.exception.ConflictException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the legacy engine's SELF behaviour and proves that the command permit plan now preserves the
 * same result at the real DynamicDataService read/list gates without re-deciding authorization.
 *
 * <p>The gate under characterization is the engine's {@link DataPermissionEngine#canAccessRecord} —
 * the {@code @1034} read-check that runs before every single-record write. Today it decides via the
 * engine's policies; under command scope the production gate now honours the plan grade instead.
 * A member bound to a SELF row policy may reach a record they own and not one another user owns; a
 * member with no policy reaches both. The plan-backed assertions below keep that characterization
 * and the new enforcement path in the same real-stack regression anchor.</p>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Read-gate SELF scope — legacy anchor and authoritative command-plan enforcement")
class ScopeReadGateCharacterizationIT extends BaseIntegrationTest {

    @Autowired private DataPermissionEngine dataPermissionEngine;
    @Autowired private DataPermissionPolicyService dataPermissionPolicyService;
    @Autowired private DynamicDataService dynamicDataService;
    @Autowired private CommandFieldMapExecutor commandFieldMapExecutor;
    @Autowired private SchemaManagementService schemaManagementService;
    @Autowired private MetaModelMapper metaModelMapper;
    @Autowired private MetaFieldMapper metaFieldMapper;
    @Autowired private MetaModelFieldBindingMapper fieldBindingMapper;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String model = "spc_" + suffix;
    private final String table = "mt_" + model;
    private final String sharedModel = "shr_" + suffix;
    private final String sharedTable = "mt_" + sharedModel;
    private final List<Long> fieldIds = new ArrayList<>();

    private Long tenantId;
    private Long selfUserId;      // member bound to a SELF policy
    private Long selfMemberId;
    private Long openUserId;      // member with no policy at all
    private Long openMemberId;
    private String policyPid;
    private String rolePid;
    private String ownRecordPid;
    private String otherRecordPid;
    private String sharedRecordPid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void setUp() {
        tenantId = TestIdGenerator.uniqueTenantId();
        selfUserId = TestIdGenerator.uniqueUserId();
        openUserId = selfUserId + 4_000_001L;

        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "scope_char_" + tenantId);
        selfMemberId = insertMember(selfUserId);
        openMemberId = insertMember(openUserId);
        createDynamicModel();
        createSharedModel();
        ownRecordPid = "own_" + suffix;
        otherRecordPid = "other_" + suffix;
        sharedRecordPid = "shared_" + suffix;
        jdbc.update("INSERT INTO " + table
                        + " (pid, tenant_id, name, row_version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, 1, ?, ?), (?, ?, ?, 1, ?, ?)",
                ownRecordPid, tenantId, "own row", selfUserId, selfUserId,
                otherRecordPid, tenantId, "other row", openUserId, openUserId);
        jdbc.update("INSERT INTO " + sharedTable
                        + " (pid, tenant_id, name, row_version, created_by, updated_by) "
                        + "VALUES (?, ?, ?, 1, ?, ?)",
                sharedRecordPid, tenantId, "admin-created shared config", openUserId, openUserId);

        // A role the SELF member holds; the open member holds nothing.
        long roleId = System.nanoTime() & 0x7fffffffffffffffL;
        rolePid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_role (id, pid, tenant_id, name, code, type, scope_type, status, "
                        + "deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CUSTOM', 'TENANT', 'ACTIVE', FALSE, now(), now())",
                roleId, rolePid, tenantId, "scope char role", "char_role_" + suffix);
        jdbc.update("INSERT INTO ab_user_role (id, pid, tenant_id, member_id, role_id, assign_type, "
                        + "status, deleted_flag, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'DIRECT', 'ACTIVE', FALSE, now())",
                System.nanoTime() & 0x7fffffffffffffffL, UniqueIdGenerator.generate(), tenantId,
                selfMemberId, roleId);

        // A SELF row policy on the model, bound to that role.
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("scope char self " + suffix);
        req.setModelCode(model);
        req.setPolicyType("row");
        req.setScopeType("self");
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "scope-char-setup");
        try {
            DataPermissionPolicy policy = dataPermissionPolicyService.create(req);
            policyPid = policy.getPid();
            dataPermissionPolicyService.bindToRole(policyPid, rolePid);
        } finally {
            MetaContext.clear();
        }
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tearDown() {
        try {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
            jdbc.execute("DROP TABLE IF EXISTS " + sharedTable);
            jdbc.update("DELETE FROM ab_data_permission_role_binding WHERE policy_pid = ?", policyPid);
            jdbc.update("DELETE FROM ab_data_permission_policy WHERE pid = ?", policyPid);
            jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE model_id IN "
                            + "(SELECT id FROM ab_meta_model WHERE code IN (?, ?) AND tenant_id = ?)",
                    model, sharedModel, tenantId);
            if (fieldIds.size() == 3) {
                jdbc.update("DELETE FROM ab_meta_field WHERE tenant_id = ? AND id IN (?, ?, ?)",
                        tenantId, fieldIds.get(0), fieldIds.get(1), fieldIds.get(2));
            }
            jdbc.update("DELETE FROM ab_meta_model WHERE code IN (?, ?) AND tenant_id = ?",
                    model, sharedModel, tenantId);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE pid = ?", rolePid);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
        } catch (Exception ignored) {
        }
        MetaContext.clear();
    }

    @BeforeEach
    void resetTargetRows() {
        jdbc.update("UPDATE " + table + " SET name = 'own row', row_version = 1 WHERE pid = ?", ownRecordPid);
        jdbc.update("UPDATE " + table + " SET name = 'other row', row_version = 1 WHERE pid = ?", otherRecordPid);
    }

    @Test
    @DisplayName("a SELF-scoped member may reach a record they own, but not one another user owns")
    void selfScopedMemberReachesOwnNotOthers() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, selfUserId, ownedBy(selfUserId)))
                    .as("a SELF-scoped member reaches their own record")
                    .isTrue();
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, selfUserId, ownedBy(openUserId)))
                    .as("a SELF-scoped member is refused another user's record — this is what the plan must preserve")
                    .isFalse();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("a member with no policy reaches any record — the SELF policy is what discriminates")
    void memberWithNoPolicyReachesAnyRecord() {
        MetaContext.setContext(tenantId, openUserId, "u-" + openUserId, "open-member");
        MetaContext.setMemberId(openMemberId);
        try {
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, openUserId, ownedBy(openUserId))).isTrue();
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, openUserId, ownedBy(selfUserId)))
                    .as("no policy = unrestricted read, so the SELF denial above is the policy, not some other gate")
                    .isTrue();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("the real DynamicDataService read gate executes SELF/ALL from the command plan")
    void dynamicReadGateExecutesPlanGrade() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            MetaContext.runWithCommandPermitScope("SELF", () -> {
                assertThat(dynamicDataService.getById(model, ownRecordPid))
                        .containsEntry("pid", ownRecordPid);
                assertThatThrownBy(() -> dynamicDataService.getById(model, otherRecordPid))
                        .isInstanceOf(AccessDeniedException.class)
                        .hasMessageContaining("command permit scope");
            });

            MetaContext.runWithCommandPermitScope("ALL", () ->
                    assertThat(dynamicDataService.getById(model, otherRecordPid))
                            .as("ALL comes from the plan; the seeded engine policy is still SELF")
                            .containsEntry("pid", otherRecordPid));
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("the real list data-scope executes SELF/ALL from the command plan")
    void dynamicListExecutesPlanGrade() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .conditions(List.of())
                .build();
        try {
            MetaContext.runWithCommandPermitScope("SELF", () ->
                    assertThat(dynamicDataService.list(model, request).getRecords())
                            .extracting(row -> String.valueOf(row.get("pid")))
                            .contains(ownRecordPid)
                            .doesNotContain(otherRecordPid));

            MetaContext.runWithCommandPermitScope("ALL", () ->
                    assertThat(dynamicDataService.list(model, request).getRecords())
                            .as("ALL must not re-consult the seeded SELF engine policy")
                            .extracting(row -> String.valueOf(row.get("pid")))
                            .contains(ownRecordPid, otherRecordPid));
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("a root-model plan never leaks its SELF or ALL grade into another model")
    void commandPlanScopeIsBoundToItsRootModel() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10)
                .conditions(List.of())
                .build();
        try {
            MetaContext.runWithCommandPermitPlan("SELF", null, model, ownRecordPid, () -> {
                assertThat(dynamicDataService.list(model, request).getRecords())
                        .as("the root model still executes the command's SELF grade")
                        .extracting(row -> String.valueOf(row.get("pid")))
                        .contains(ownRecordPid)
                        .doesNotContain(otherRecordPid);
                assertThat(dynamicDataService.list(sharedModel, request).getRecords())
                        .as("the independent model uses its own no-policy=ALL scope")
                        .extracting(row -> String.valueOf(row.get("pid")))
                        .contains(sharedRecordPid);
            });

            MetaContext.runWithCommandPermitPlan("ALL", null, sharedModel, sharedRecordPid, () ->
                    assertThat(dynamicDataService.list(model, request).getRecords())
                            .as("the independent model's SELF policy must not inherit the root ALL grade")
                            .extracting(row -> String.valueOf(row.get("pid")))
                            .contains(ownRecordPid)
                            .doesNotContain(otherRecordPid));
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("a target version change after authorization makes the real update refuse")
    void serverCapturedVersionRejectsTargetDrift() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            jdbc.update("UPDATE " + table + " SET row_version = 2 WHERE pid = ?", ownRecordPid);

            assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan(
                    "SELF", 1L, model, ownRecordPid,
                    () -> dynamicDataService.update(model, ownRecordPid, Map.of("name", "stale write"))))
                    .isInstanceOf(MetaServiceException.class)
                    .hasMessageContaining("version conflict");

            assertThat(jdbc.queryForObject(
                    "SELECT name FROM " + table + " WHERE pid = ?", String.class, ownRecordPid))
                    .isEqualTo("own row");
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("successive real target writes advance the in-flight server version")
    void successfulTargetWritesAdvancePlanVersion() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            MetaContext.runWithCommandPermitPlan(
                    "SELF", 1L, model, ownRecordPid,
                    () -> {
                        dynamicDataService.update(model, ownRecordPid, Map.of("name", "first write"));
                        dynamicDataService.update(model, ownRecordPid, Map.of("name", "second write"));
                    });

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT name, row_version FROM " + table + " WHERE pid = ?", ownRecordPid);
            assertThat(row)
                    .containsEntry("name", "second write")
                    .containsEntry("row_version", 3);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("a target version change after authorization makes the real delete refuse")
    void serverCapturedVersionRejectsDeleteDrift() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            jdbc.update("UPDATE " + table + " SET row_version = 2 WHERE pid = ?", ownRecordPid);

            assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan(
                    "SELF", 1L, model, ownRecordPid,
                    () -> dynamicDataService.delete(model, ownRecordPid)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("target changed");

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE pid = ?", Integer.class, ownRecordPid))
                    .isEqualTo(1);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("the real FIELD_MAP write also asserts the server-captured target version")
    void fieldMapWriteRejectsTargetDrift() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            jdbc.update("UPDATE " + table + " SET row_version = 2 WHERE pid = ?", ownRecordPid);

            BindingRule rule = new BindingRule();
            rule.setRuleType("field_map");
            rule.setTargetModel(model);
            rule.setSourceField("name");
            rule.setTargetField("name");
            CommandExecuteRequest request = new CommandExecuteRequest();
            request.setOperationType("update");
            request.setTargetRecordId(ownRecordPid);

            assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan(
                    "SELF", 1L, model, ownRecordPid,
                    () -> commandFieldMapExecutor.executeFieldMapPhase(
                            List.of(rule), Map.of("name", "stale field map"), tenantId, request)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("target changed");

            assertThat(jdbc.queryForObject(
                    "SELECT name FROM " + table + " WHERE pid = ?", String.class, ownRecordPid))
                    .isEqualTo("own row");
        } finally {
            MetaContext.clear();
        }
    }

    private Map<String, Object> ownedBy(Long userId) {
        Map<String, Object> record = new HashMap<>();
        record.put("created_by", userId);
        record.put("pid", "rec_" + userId);
        return record;
    }

    private Long insertMember(Long userId) {
        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);
        return memberId;
    }

    private void createDynamicModel() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "scope-char-model");
        try {
            Model entity = insertModel(model, "Scope read gate characterization");

            bindField(entity.getId(), "pid", "string", true, 0);
            bindField(entity.getId(), "name", "string", false, 1);
            bindField(entity.getId(), "row_version", "integer", false, 2);

            SchemaOperationResult result = schemaManagementService.createTableByModel(model);
            assertThat(result.isSuccess())
                    .as("real dynamic table must be created: %s", result.getMessage())
                    .isTrue();
        } finally {
            MetaContext.clear();
        }
    }

    private void createSharedModel() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "scope-char-shared-model");
        try {
            Model entity = insertModel(sharedModel, "Shared configuration characterization");
            for (int index = 0; index < fieldIds.size(); index++) {
                ModelFieldBinding binding = new ModelFieldBinding();
                binding.setTenantId(tenantId);
                binding.setModelId(entity.getId());
                binding.setFieldId(fieldIds.get(index));
                binding.setFieldOrder(index);
                fieldBindingMapper.insert(binding);
            }

            SchemaOperationResult result = schemaManagementService.createTableByModel(sharedModel);
            assertThat(result.isSuccess())
                    .as("real shared dynamic table must be created: %s", result.getMessage())
                    .isTrue();
        } finally {
            MetaContext.clear();
        }
    }

    private Model insertModel(String modelCode, String displayName) {
        Model entity = new Model();
        entity.setPid(UniqueIdGenerator.generate());
        entity.setTenantId(tenantId);
        entity.setCode(modelCode);
        entity.setVersion(1);
        entity.setIsCurrent(true);
        entity.setStatus(Status.PUBLISHED.getCode());
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeletedFlag(false);
        ExtensionBean extension = new ExtensionBean();
        extension.setExtension(Map.of(
                "displayName", displayName,
                "modelType", "entity"));
        entity.setExtension(extension);
        metaModelMapper.insert(entity);
        return entity;
    }

    private void bindField(Long modelId, String code, String dataType, boolean primaryKey, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(tenantId);
        field.setCode(code);
        field.setDataType(dataType);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setUnique(primaryKey);
        field.setFeature(feature);
        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionValues = new HashMap<>();
        extensionValues.put("displayName", code);
        if (primaryKey) {
            extensionValues.put("primaryKey", true);
        }
        extension.setExtension(extensionValues);
        field.setExtension(extension);
        metaFieldMapper.insert(field);
        fieldIds.add(field.getId());

        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(tenantId);
        binding.setModelId(modelId);
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }
}
