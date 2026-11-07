package com.auraboot.framework.permission;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.dto.DataScopeUpdateRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftSnapshot;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RoleAuthorizationDraftService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL transactions; faults occur after actual writes, never in a mocked database. */
@SpringBootTest(classes = TestApplication.class, properties = "spring.data.redis.host=")
@Transactional(propagation = Propagation.NEVER)
class RoleAuthorizationDraftTransactionIT extends BaseIntegrationTest {
    @Autowired private RoleAuthorizationDraftService drafts;
    @MockitoSpyBean private com.auraboot.framework.menu.service.RoleNavigationService navigation;
    @Autowired private com.auraboot.framework.plugin.pf4j.ExtensionRegistry originalExtensions;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private CapabilityRegistryService catalog;
    @Autowired private com.auraboot.framework.permission.service.PermissionMatrixService matrix;
    @Autowired private com.auraboot.framework.rbac.service.RoleService roleAuthoring;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.auraboot.framework.permission.service.PermissionService effectivePermissions;
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired private com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper policyMapper;
    @MockitoSpyBean private DataScopeService scopes;
    @MockitoSpyBean private com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper fieldBindings;
    @Autowired private com.auraboot.framework.meta.mapper.MetaModelMapper models;
    @Autowired private com.auraboot.framework.meta.mapper.MetaFieldMapper fields;
    @Autowired private com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper modelFields;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService metadata;
    @Autowired private com.auraboot.framework.meta.service.DynamicDataService dynamicData;
    @Autowired private com.auraboot.framework.meta.service.SchemaManagementService schema;
    private boolean physicalTable;
    private Long modelId, fieldId;
    private long roleId;
    private String rolePid;
    private String resource;
    private String readCode;
    private String updateCode;
    private String readCapability;
    private String editCapability;
    private String nativeCommandPid;
    private String nativeCommandCode;
    private String nativePermissionCode;
    private String nativeCapability;

    private com.auraboot.framework.integration.TenantAdministrationFixture administrator;
    @BeforeEach void fixture() {
        administrator = com.auraboot.framework.integration.TenantAdministrationFixture.grant(jdbc, getTestTenant().getId(), getTestTenantMember().getId());
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.ROLE_MANAGE, "API", "role", "update", "Role authoring fixture");
        roleId = IdWorker.getId();
        rolePid = UniqueIdGenerator.generate();
        resource = "draft_tx_" + rolePid.toLowerCase();
        readCode = "model." + resource + ".read";
        updateCode = "model." + resource + ".update";
        readCapability = resource + ".cap.read";
        editCapability = resource + ".cap.edit";
        jdbc.update("""
                INSERT INTO ab_role (id,pid,tenant_id,code,name,type,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,'custom','active',false,now(),now())
                """, roleId, rolePid, getTestTenant().getId(), resource, resource);
        for (String action : List.of("read", "update")) {
            jdbc.update("""
                    INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,
                                               source,status,deleted_flag,created_at,updated_at)
                    VALUES (?,?,?,?,'model',?,?,'test','active',false,now(),now())
                    """, UniqueIdGenerator.generate(), getTestTenant().getId(), "model." + resource + "." + action,
                    action, resource, action);
        }
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(readCapability).group("Draft transaction")
                .includes(List.of(readCode)).build());
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(editCapability).group("Draft transaction")
                .includes(List.of(readCode, updateCode)).build());
    }

    @AfterEach void cleanup() {
        reset(scopes, fieldBindings);
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "extensions", originalExtensions);
        if (administrator != null) administrator.close();
        if (nativeCommandPid != null) {
            jdbc.update("DELETE FROM ab_binding_rule WHERE command_id IN (SELECT id FROM ab_command_definition WHERE pid=?)", nativeCommandPid);
            jdbc.update("DELETE FROM ab_command_definition WHERE tenant_id=? AND pid=?", getTestTenant().getId(), nativeCommandPid);
        }
        if (physicalTable) assertThat(schema.dropTableByModel(resource).isSuccess()).isTrue();
        if (modelId != null) {
            jdbc.update("DELETE FROM ab_meta_model_field_binding WHERE tenant_id=? AND model_id=?", getTestTenant().getId(), modelId);
            jdbc.update("DELETE FROM ab_meta_model WHERE tenant_id=? AND id=?", getTestTenant().getId(), modelId);
            jdbc.update("DELETE FROM ab_meta_field WHERE tenant_id=? AND id=?", getTestTenant().getId(), fieldId);
            metadata.clearAllCache();
        }
        if (rolePid != null) {
            jdbc.update("DELETE FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=?", getTestTenant().getId(), rolePid);
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_permission_capability WHERE tenant_id=? AND code LIKE ?", getTestTenant().getId(), resource + ".%");
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND resource_code=?", getTestTenant().getId(), resource);
            jdbc.update("DELETE FROM ab_data_permission_policy WHERE tenant_id=? AND model_code=?", getTestTenant().getId(), resource);
        }
    }

    private void migrationSource() throws Exception {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE,
                "operation", "permission", "update", "Migration inspection fixture");
        jdbc.update("UPDATE ab_permission SET resource_type='operation' WHERE tenant_id=? AND code=?", getTestTenant().getId(), updateCode);
        var registry = org.mockito.Mockito.mock(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class);
        var handler = org.mockito.Mockito.mock(com.auraboot.framework.plugin.extension.CommandHandlerExtension.class,
                org.mockito.Mockito.withSettings().extraInterfaces(com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.class));
        var owner = (com.auraboot.framework.plugin.extension.BusinessResourceAuthorization) handler;
        String command = "test:" + resource;
        org.mockito.Mockito.when(owner.businessBoundary()).thenReturn(
                new com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.Boundary(command, resource, "update", Set.of(updateCode)));
        org.mockito.Mockito.when(registry.getAllCommandHandlers()).thenReturn(List.of(handler));
        org.mockito.Mockito.when(registry.getBusinessResourceOwner(command)).thenReturn(java.util.Optional.of(handler));
        org.springframework.test.util.ReflectionTestUtils.setField(drafts, "extensions", registry);
        var start = drafts.snapshot(rolePid);
        drafts.apply(rolePid, new RoleAuthorizationDraftRequest(start.revision(), Set.of(editCapability), Set.of(), false, null, false, List.of()));
        String ast = json.writeValueAsString(com.auraboot.framework.decision.ast.ConditionNode.CompareNode.of(
                new com.auraboot.framework.decision.ast.Operand.PathOperand(com.auraboot.framework.decision.ast.Scope.RECORD,
                    "data.status", com.auraboot.framework.decision.ast.DataType.STRING), com.auraboot.framework.decision.ast.Operator.EQ,
                new com.auraboot.framework.decision.ast.Operand.LiteralOperand("approved", com.auraboot.framework.decision.ast.DataType.STRING)));
        String fields = json.writeValueAsString(Map.of("modelCode", resource, "readFields", List.of(), "writeFields", List.of()));
        jdbc.update("""
                UPDATE ab_role_permission SET priority=37,conditions='{"maxApprovalAmount":12}'::jsonb,
                  condition_ast=?::jsonb,data_scope_ast=?::jsonb,field_scope=?::jsonb,source_ref='independent:migration-fixture',
                  effective_date=CURRENT_DATE-1,expiry_date=CURRENT_DATE+30
                WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)
                """, ast, ast, fields, getTestTenant().getId(), roleId, getTestTenant().getId(), updateCode);
    }

    private String migrationBindingBytes() {
        return jdbc.queryForObject("""
                SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY r.id),'[]'::jsonb)::text
                FROM ab_role_permission r WHERE tenant_id=? AND role_id=?
                """, String.class, getTestTenant().getId(), roleId);
    }

    private void nativeSource() throws Exception {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE,
                "operation", "permission", "update", "Native migration inspection fixture");
        nativePermissionCode = "business." + resource + ".manage";
        nativeCapability = resource + ".cap.native";
        nativeCommandPid = UniqueIdGenerator.generate(); nativeCommandCode = "native:" + resource;
        jdbc.update("""
                INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,
                                           source,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,'operation',?,'manage','test','active',false,now(),now())
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), nativePermissionCode, "Native business fixture", resource);
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(nativeCapability).group("Native draft transaction")
                .includes(List.of(nativePermissionCode)).build());
        String config = json.writeValueAsString(Map.of("type", "update", "inputFields", List.of("amount"),
                "permissions", List.of(nativePermissionCode), "handlerParams",
                Map.of("targetRecordAuthorization", "business", "targetRecordAction", "update")));
        jdbc.update("""
                INSERT INTO ab_command_definition (id,pid,tenant_id,code,model_code,execution_config,
                    version,is_current,row_version,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,?::jsonb,1,true,0,'published',false,now(),now())
                """, IdWorker.getId(), nativeCommandPid, getTestTenant().getId(), nativeCommandCode, resource, config);
        var before = drafts.snapshot(rolePid);
        drafts.apply(rolePid, new RoleAuthorizationDraftRequest(before.revision(), Set.of(nativeCapability), Set.of(),
                false, null, false, List.of()));
        String fields = json.writeValueAsString(Map.of("modelCode", resource, "readFields", List.of("amount"), "writeFields", List.of("amount")));
        jdbc.update("""
                UPDATE ab_role_permission SET priority=37,conditions='{"maxApprovalAmount":12}'::jsonb,
                    field_scope=?::jsonb,source_ref='independent:native-migration-fixture',
                    effective_date=CURRENT_DATE-1,expiry_date=CURRENT_DATE+30
                WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)
                """, fields, getTestTenant().getId(), roleId, getTestTenant().getId(), nativePermissionCode);
    }

    @Test void nativeMigrationFindsThePublishedDefinitionAndPreservesGrantTuplesDuringScopeDecision() throws Exception {
        nativeSource(); String before = migrationBindingBytes();
        var inspected = drafts.migrationPreview(rolePid);
        var nativeBoundary = inspected.boundaries().stream().filter(root -> nativeCommandCode.equals(root.commandCode())).findFirst().orElseThrow();
        assertThat(nativeBoundary.rootModel()).isEqualTo(resource); assertThat(nativeBoundary.targetAction()).isEqualTo("update");
        assertThat(nativeBoundary.operations().getFirst().scopeStatus()).isEqualTo("REQUIRES_EXPLICIT_SCOPE_DECISION");
        assertThat(inspected.unmappedPermissionCodes()).doesNotContain(nativePermissionCode);
        assertThat(inspected.bindings()).anySatisfy(binding -> {
            assertThat(binding.permissionCode()).isEqualTo(nativePermissionCode);
            assertThat(binding.priority()).isEqualTo(37); assertThat(binding.sourceRef()).isEqualTo("independent:native-migration-fixture");
            assertThat(binding.conditions().get("maxApprovalAmount").asInt()).isEqualTo(12);
            assertThat(binding.fieldScope().get("modelCode").asText()).isEqualTo(resource);
            assertThat(binding.effectiveDate()).isNotNull(); assertThat(binding.expiryDate()).isNotNull();
        });
        drafts.apply(rolePid, new RoleAuthorizationDraftRequest(inspected.revision(), Set.of(nativeCapability), Set.of(),
                false, null, false, List.of(new DataScopeUpdateRequest(resource, "manage", "self", "MAX"))));
        assertThat(defaultScope()).isNull(); assertThat(migrationBindingBytes()).isEqualTo(before);
        assertThat(inspected.equivalentMigrationProven()).isFalse();
        var after = drafts.migrationPreview(rolePid).boundaries().stream()
                .filter(root -> nativeCommandCode.equals(root.commandCode())).findFirst().orElseThrow().operations().getFirst();
        assertThat(after.currentScopeType()).isEqualTo("self"); assertThat(after.scopeOrigin()).isEqualTo("EXPLICIT_ACTION");
    }

    private void ownerEntry() throws Exception {
        nativeCommandPid = UniqueIdGenerator.generate(); nativeCommandCode = "test:" + resource;
        String config = json.writeValueAsString(Map.of("type", "custom", "handler", nativeCommandCode,
                "permissions", List.of(updateCode), "handlerParams", Map.of("targetRecordAuthorization", "business",
                        "targetRecordAction", "update", "dslPersistence", false)));
        jdbc.update("""
                INSERT INTO ab_command_definition (id,pid,tenant_id,code,model_code,execution_config,
                    version,is_current,row_version,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,?::jsonb,1,true,0,'published',false,now(),now())
                """, IdWorker.getId(), nativeCommandPid, getTestTenant().getId(), nativeCommandCode, resource, config);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"input", "execution", "withdrawn"})
    void changedOwnerEntryRejectsOldReviewWithoutPartialDatabaseWrites(String change) throws Exception {
        migrationSource(); ownerEntry();
        var reviewed = drafts.migrationPreview(rolePid); String before = migrationBindingBytes();
        switch (change) {
            case "input" -> jdbc.update("UPDATE ab_command_definition SET input_schema='{\"type\":\"object\"}'::jsonb WHERE tenant_id=? AND pid=?",
                    getTestTenant().getId(), nativeCommandPid);
            case "execution" -> jdbc.update("UPDATE ab_command_definition SET execution_config=jsonb_set(execution_config,'{handlerParams,dslPersistence}','true'::jsonb) WHERE tenant_id=? AND pid=?",
                    getTestTenant().getId(), nativeCommandPid);
            case "withdrawn" -> jdbc.update("UPDATE ab_command_definition SET status='draft' WHERE tenant_id=? AND pid=?",
                    getTestTenant().getId(), nativeCommandPid);
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> drafts.apply(rolePid, new RoleAuthorizationDraftRequest(reviewed.revision(), Set.of(editCapability), Set.of(),
                true, "all", true, List.of(new DataScopeUpdateRequest(resource, "update", "all", "MAX")))))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(migrationBindingBytes()).isEqualTo(before);
        assertThat(defaultScope()).isNull(); assertThat(persistedScopes()).isEmpty();
    }

    @Test void newlyDefinedOwnerEntryInvalidatesAReviewOfItsMissingDefinition() throws Exception {
        migrationSource(); var before = drafts.snapshot(rolePid); String bindings = migrationBindingBytes();
        ownerEntry();
        assertThat(drafts.snapshot(rolePid).revision()).isNotEqualTo(before.revision());
        assertThat(migrationBindingBytes()).isEqualTo(bindings); assertThat(defaultScope()).isNull();
    }

    @Test void nativeInputDefinitionChangeRejectsTheOldDraftWithoutPartialDatabaseWrites() throws Exception {
        nativeSource(); var inspected = drafts.migrationPreview(rolePid); String before = migrationBindingBytes();
        jdbc.update("UPDATE ab_command_definition SET execution_config=jsonb_set(execution_config,'{inputFields}','[\"amount\",\"note\"]'::jsonb) WHERE tenant_id=? AND pid=?",
                getTestTenant().getId(), nativeCommandPid);
        assertThatThrownBy(() -> drafts.apply(rolePid, new RoleAuthorizationDraftRequest(inspected.revision(), Set.of(nativeCapability), Set.of(),
                true, "all", true, List.of(new DataScopeUpdateRequest(resource, "manage", "all", "MAX")))))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(migrationBindingBytes()).isEqualTo(before); assertThat(defaultScope()).isNull(); assertThat(persistedScopes()).isEmpty();
    }

    @Test void withdrawnNativePublicationIsNotAnExecutableMigrationBoundary() throws Exception {
        nativeSource(); var before = drafts.snapshot(rolePid); String grants = migrationBindingBytes();
        jdbc.update("UPDATE ab_command_definition SET status='draft' WHERE tenant_id=? AND pid=?", getTestTenant().getId(), nativeCommandPid);
        var inspected = drafts.migrationPreview(rolePid);
        assertThat(inspected.boundaries()).noneMatch(root -> nativeCommandCode.equals(root.commandCode()));
        assertThat(inspected.unmappedPermissionCodes()).contains(nativePermissionCode);
        assertThat(inspected.revision()).isNotEqualTo(before.revision());
        assertThat(migrationBindingBytes()).isEqualTo(grants); assertThat(defaultScope()).isNull();
    }

    @Test void explicitMigrationScopeUsesTheDraftWithoutRewritingAnySourceBinding() throws Exception {
        migrationSource(); String before = migrationBindingBytes();
        var inspected = drafts.migrationPreview(rolePid);
        assertThat(inspected.boundaries().getFirst().operations().getFirst().scopeStatus()).isEqualTo("REQUIRES_EXPLICIT_SCOPE_DECISION");
        assertThat(inspected.bindings()).anySatisfy(binding -> {
            assertThat(binding.sourceRef()).isEqualTo("independent:migration-fixture");
            assertThat(binding.priority()).isEqualTo(37); assertThat(binding.conditions().get("maxApprovalAmount").asInt()).isEqualTo(12);
            assertThat(binding.conditionAst()).isNotNull(); assertThat(binding.dataScopeAst()).isNotNull(); assertThat(binding.fieldScope()).isNotNull();
        });
        assertThat(migrationBindingBytes()).isEqualTo(before); assertThat(persistedScopes()).isEmpty();
        drafts.apply(rolePid, new RoleAuthorizationDraftRequest(inspected.revision(), Set.of(editCapability), Set.of(), false, null, false,
                List.of(new DataScopeUpdateRequest(resource, "update", "self", "MAX"))));
        assertThat(defaultScope()).isNull(); assertThat(migrationBindingBytes()).isEqualTo(before);
        var after = drafts.migrationPreview(rolePid);
        assertThat(after.boundaries().getFirst().operations().getFirst().currentScopeType()).isEqualTo("self");
        assertThat(after.equivalentMigrationProven()).isFalse();
    }

    @Test void migrationReviewPreservesPublishPointerAndRejectsAChangedOwnerBeforeAnyScopeWrite() throws Exception {
        migrationSource();
        assertThat(jdbc.update("""
                UPDATE ab_role_permission SET snapshot_id=991,source_ref=NULL
                WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)
                """, getTestTenant().getId(), roleId, getTestTenant().getId(), updateCode)).isEqualTo(1);
        String before = migrationBindingBytes();
        var reviewed = drafts.migrationPreview(rolePid);
        assertThat(reviewed.bindings()).anySatisfy(binding -> {
            assertThat(binding.permissionCode()).isEqualTo(updateCode);
            assertThat(binding.snapshotId()).isEqualTo(991L);
            assertThat(binding.sourceRef()).isNull();
            assertThat(binding.sourceOwnership()).isEqualTo("PUBLISHED_SNAPSHOT");
            assertThat(binding.priority()).isEqualTo(37);
            assertThat(binding.conditionAst()).isNotNull(); assertThat(binding.dataScopeAst()).isNotNull();
            assertThat(binding.fieldScope()).isNotNull();
        });
        assertThat(reviewed.equivalentMigrationProven()).isFalse();
        assertThat(migrationBindingBytes()).isEqualTo(before);
        assertThat(persistedScopes()).isEmpty();
        jdbc.update("UPDATE ab_role_permission SET snapshot_id=992 WHERE tenant_id=? AND role_id=? AND snapshot_id=991",
                getTestTenant().getId(), roleId);
        String changed = migrationBindingBytes();
        assertThatThrownBy(() -> drafts.apply(rolePid, new RoleAuthorizationDraftRequest(reviewed.revision(), Set.of(editCapability), Set.of(),
                false, null, false, List.of(new DataScopeUpdateRequest(resource, "update", "all", "MAX")))))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        failure -> assertThat(failure.getResponseCode()).isEqualTo(com.auraboot.framework.common.constant.ResponseCode.CasVersionConflict));
        assertThat(migrationBindingBytes()).isEqualTo(changed);
        assertThat(persistedScopes()).isEmpty(); assertThat(defaultScope()).isNull();
    }

    @Test void dutyRevocationMustPreserveTheSeparateDenyBinding() throws Exception {
        migrationSource();
        jdbc.update("UPDATE ab_role_permission SET source_ref=NULL WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
        String denyPid = jdbc.queryForObject("""
                SELECT g.pid FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id
                WHERE g.tenant_id=? AND g.role_id=? AND p.code=? AND g.deleted_flag=FALSE
                """, String.class, getTestTenant().getId(), roleId, updateCode);
        jdbc.update("UPDATE ab_role_permission SET grant_type='deny',priority=99,conditions=?::jsonb WHERE pid=?", json.writeValueAsString(Map.of("guard", "must-remain")), denyPid);
        String denyBefore = jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g WHERE pid=?", String.class, denyPid);
        var before = drafts.snapshot(rolePid);
        drafts.apply(rolePid, new RoleAuthorizationDraftRequest(before.revision(), Set.of(readCapability), Set.of(), false, null, false, List.of()));
        assertThat(jdbc.queryForObject("SELECT to_jsonb(g)::text FROM ab_role_permission g WHERE pid=?", String.class, denyPid)).isEqualTo(denyBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission g JOIN ab_permission p ON p.id=g.permission_id WHERE g.role_id=? AND p.code=? AND g.grant_type='grant' AND g.deleted_flag=FALSE", Long.class, roleId, updateCode)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"snapshot", "external", "managed_tombstone"})
    void dutyChangeCannotReplaceManagedOrUnknownBindings(String source) throws Exception {
        migrationSource();
        jdbc.update("UPDATE ab_role_permission g SET snapshot_id=?,source_ref='package:fixture',deleted_flag=? FROM ab_permission p WHERE g.permission_id=p.id AND g.tenant_id=? AND g.role_id=? AND p.code=?",
                "external".equals(source) ? null : 991L, "managed_tombstone".equals(source), getTestTenant().getId(), roleId, updateCode);
        String original = migrationBindingBytes();
        var before = drafts.snapshot(rolePid);
        var change = new RoleAuthorizationDraftRequest(before.revision(),
                Set.of("managed_tombstone".equals(source) ? editCapability : readCapability), Set.of(), true, "all", false, List.of());
        assertThatThrownBy(() -> drafts.preview(rolePid, change)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThatThrownBy(() -> drafts.apply(rolePid, change)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(migrationBindingBytes()).isEqualTo(original);
        assertThat(defaultScope()).isNull(); assertThat(persistedScopes()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"inactive_grant", "future_grant", "inactive_role"})
    void dutyConfigurationRetainsInactiveAndFutureBindingsWithoutActivatingThem(String mode) throws Exception {
        migrationSource();
        if ("inactive_role".equals(mode)) jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", roleId);
        else if ("future_grant".equals(mode)) jdbc.update("UPDATE ab_role_permission g SET effective_date=CURRENT_DATE+3 FROM ab_permission p WHERE g.permission_id=p.id AND g.tenant_id=? AND g.role_id=? AND p.code=?",
                getTestTenant().getId(), roleId, updateCode);
        else jdbc.update("UPDATE ab_role_permission g SET status='inactive' FROM ab_permission p WHERE g.permission_id=p.id AND g.tenant_id=? AND g.role_id=? AND p.code=?",
                getTestTenant().getId(), roleId, updateCode);
        String original = migrationBindingBytes(); var before = drafts.snapshot(rolePid);
        assertThat(before.groups().stream().flatMap(group -> group.getCapabilities().stream())
                .filter(capability -> editCapability.equals(capability.getCode())).findFirst().orElseThrow().isGranted()).isTrue();
        assertThat(effectivePermissions.findRolePermissions(roleId)).noneMatch(permission -> updateCode.equals(permission.getCode()));
        var change = new RoleAuthorizationDraftRequest(before.revision(), Set.of(editCapability), Set.of(), false, null, false, List.of());
        drafts.apply(rolePid, change);
        assertThat(migrationBindingBytes()).isEqualTo(original);
        assertThat(effectivePermissions.findRolePermissions(roleId)).noneMatch(permission -> updateCode.equals(permission.getCode()));
        assertThat(persistedScopes()).isEmpty(); assertThat(defaultScope()).isNull();
    }

    @Test void changedSourceConditionsInvalidateTheMigrationRevisionBeforeScopeWrites() throws Exception {
        migrationSource(); var inspected = drafts.migrationPreview(rolePid);
        jdbc.update("UPDATE ab_role_permission SET priority=priority+1 WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
        String changed = migrationBindingBytes();
        assertThatThrownBy(() -> drafts.apply(rolePid, new RoleAuthorizationDraftRequest(inspected.revision(), Set.of(editCapability), Set.of(),
                false, null, false, List.of(new DataScopeUpdateRequest(resource, "update", "all", "MAX")))))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                    error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(migrationBindingBytes()).isEqualTo(changed); assertThat(persistedScopes()).isEmpty(); assertThat(defaultScope()).isNull();
    }

    @Test void migrationInspectionCannotJoinAWeakerCallerTransaction() throws Exception {
        migrationSource(); String before = migrationBindingBytes();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> transaction.execute(status -> drafts.migrationPreview(rolePid)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(migrationBindingBytes()).isEqualTo(before); assertThat(persistedScopes()).isEmpty();
    }

    @Test void migrationInspectionRejectsRevokedPolicyManagementWithoutUsingItsWarmPermissionCache() throws Exception {
        migrationSource(); drafts.migrationPreview(rolePid);
        var ids = jdbc.queryForList("""
                SELECT rp.id FROM ab_role_permission rp JOIN ab_permission p ON p.id=rp.permission_id
                WHERE rp.tenant_id=? AND rp.role_id=? AND p.code=? AND rp.status='active' AND rp.deleted_flag=FALSE
                """, Long.class, getTestTenant().getId(), getTestRole().getId(),
                com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE);
        assertThat(ids).isNotEmpty(); String before = migrationBindingBytes();
        try {
            for (Long id : ids) jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE id=? AND tenant_id=?", id, getTestTenant().getId());
            assertThatThrownBy(() -> drafts.migrationPreview(rolePid)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(migrationBindingBytes()).isEqualTo(before); assertThat(persistedScopes()).isEmpty();
        } finally {
            for (Long id : ids) jdbc.update("UPDATE ab_role_permission SET status='active' WHERE id=? AND tenant_id=?", id, getTestTenant().getId());
            applicationContext.getBean(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class).clearAll();
        }
    }

    private List<Long> revokeOnlyFixturePermission(String code) {
        var ids = jdbc.queryForList("""
                SELECT rp.id FROM ab_role_permission rp JOIN ab_permission p ON p.id=rp.permission_id
                WHERE rp.tenant_id=? AND rp.role_id=? AND p.code=? AND rp.status='active' AND rp.deleted_flag=FALSE
                """, Long.class, getTestTenant().getId(), getTestRole().getId(), code);
        assertThat(ids).isNotEmpty();
        for (Long id : ids) jdbc.update("UPDATE ab_role_permission SET status='inactive' WHERE id=? AND tenant_id=?", id, getTestTenant().getId());
        return ids;
    }

    private void restoreFixturePermission(List<Long> ids) {
        for (Long id : ids) jdbc.update("UPDATE ab_role_permission SET status='active' WHERE id=? AND tenant_id=?", id, getTestTenant().getId());
        applicationContext.getBean(com.auraboot.framework.permission.service.impl.PermissionSnapshotCache.class).clearAll();
    }

    @Test void aWarmRoleManagementPermissionCannotAuthorizeDraftOrLegacyScopeWritesAfterRevocation() throws Exception {
        migrationSource(); var baseline = drafts.snapshot(rolePid); String before = migrationBindingBytes();
        assertThat(applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class)
                .hasPermission(getTestUser().getId(), com.auraboot.framework.permission.constants.MetaPermission.ROLE_MANAGE)).isTrue();
        var revoked = revokeOnlyFixturePermission(com.auraboot.framework.permission.constants.MetaPermission.ROLE_MANAGE);
        try {
            assertThat(applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class)
                    .hasPermission(getTestUser().getId(), com.auraboot.framework.permission.constants.MetaPermission.ROLE_MANAGE)).isTrue();
            assertThatThrownBy(() -> drafts.apply(rolePid, new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(),
                    false, null, false, List.of(new DataScopeUpdateRequest(resource, "update", "self", "MAX")))))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> matrix.setRoleScope(getTestTenant().getId(), roleId,
                    new DataScopeUpdateRequest(resource, "update", "self", "MAX")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(migrationBindingBytes()).isEqualTo(before); assertThat(persistedScopes()).isEmpty();
        } finally { restoreFixturePermission(revoked); }
    }

    @Test void aWarmPolicyManagementPermissionCannotRemoveFieldBindingsAfterRevocation() {
        String policy = fieldPolicy();
        var baseline = drafts.apply(rolePid, fieldsRequest(drafts.snapshot(rolePid), Set.of(policy)));
        var beforeScopes = persistedScopes(); String beforeGrants = migrationBindingBytes();
        assertThat(applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class)
                .hasPermission(getTestUser().getId(), com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE)).isTrue();
        var revoked = revokeOnlyFixturePermission(com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE);
        try {
            assertThat(applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class)
                    .hasPermission(getTestUser().getId(), com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE)).isTrue();
            assertThatThrownBy(() -> drafts.apply(rolePid, fieldsRequest(baseline, Set.of())))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=? AND policy_pid=?",
                    Integer.class, getTestTenant().getId(), rolePid, policy)).isEqualTo(1);
            assertThat(migrationBindingBytes()).isEqualTo(beforeGrants); assertThat(persistedScopes()).isEqualTo(beforeScopes);
        } finally { restoreFixturePermission(revoked); }
    }

    @Test void directServiceCallsDenyARealDelegatedRoleManagerWithoutChangingTheDraft() {
        long user = IdWorker.getId(), member = IdWorker.getId(), assignment = IdWorker.getId();
        String userPid = UniqueIdGenerator.generate();
        var baseline = drafts.snapshot(rolePid);
        jdbc.update("INSERT INTO ab_user (id,pid,is_enabled,deleted_flag) VALUES (?,?,TRUE,FALSE)", user, userPid);
        jdbc.update("INSERT INTO ab_tenant_member (id,pid,tenant_id,user_id,status,deleted_flag) VALUES (?,?,?,?,'active',FALSE)",
                member, UniqueIdGenerator.generate(), getTestTenant().getId(), user);
        jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)",
                assignment, UniqueIdGenerator.generate(), getTestTenant().getId(), member, getTestRole().getId());
        try {
            MetaContext.setContext(getTestTenant().getId(), user, userPid, "delegated-author-test");
            MetaContext.setMemberId(member);
            var effective = applicationContext.getBean(com.auraboot.framework.permission.service.UserPermissionService.class);
            assertThat(effective.hasPermission(user, com.auraboot.framework.permission.constants.MetaPermission.ROLE_MANAGE)).isTrue();
            assertThatThrownBy(() -> roleAuthoring.setDefaultDataScopeType(roleId,"all"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> matrix.setRoleScope(getTestTenant().getId(),roleId,new DataScopeUpdateRequest(resource,"read","all","MAX")))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> applicationContext.getBean(com.auraboot.framework.permission.capability.CapabilityViewService.class)
                    .applyCapabilitySelection(roleId, Set.of(editCapability)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            applyTestMetaContext();
            jdbc.update("DELETE FROM ab_user_role WHERE id=?", assignment);
            jdbc.update("DELETE FROM ab_tenant_member WHERE id=?", member);
            jdbc.update("DELETE FROM ab_user WHERE id=?", user);
        }
        assertThat(persistedCodes()).isEmpty();
        assertThat(persistedScopes()).isEmpty();
        assertThat(defaultScope()).isNull();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void defaultScopeManagementUpdatesCurrentAndFutureGrantsInOneApplicationOperation() {
        drafts.apply(rolePid,editRequest(drafts.snapshot(rolePid)));
        matrix.setDefaultRoleScope(getTestTenant().getId(),roleId,"all");
        assertThat(defaultScope()).isEqualTo("all");
        assertThat(persistedScopes()).containsExactly(Map.of("action_code","read","scope_type","all"),Map.of("action_code","update","scope_type","all"));
        matrix.setDefaultRoleScope(getTestTenant().getId(),roleId,null);
        assertThat(defaultScope()).isNull();
        assertThat(persistedScopes()).allMatch(row->"all".equals(row.get("scope_type")));
    }

    @Test void foreignRoleIdentityCannotHaveItsDefaultChangedByALocalAdministrator() {
        long foreign=IdWorker.getId(),foreignTenant=IdWorker.getId();String pid=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES(?,?,?)",foreignTenant,UniqueIdGenerator.generate(),"Foreign role fixture");
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,code,name,type,status,deleted_flag) VALUES(?,?,?,?,?,'custom','active',FALSE)",foreign,pid,foreignTenant,resource+"_foreign",resource);
        try {
            assertThatThrownBy(()->roleAuthoring.setDefaultDataScopeType(foreign,"all"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT default_data_scope_type FROM ab_role WHERE id=?",String.class,foreign)).isNull();
            assertThatThrownBy(()->scopes.setScope(getTestTenant().getId(),foreign,resource,"read","all","MAX"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_data_scope WHERE role_id=?",Integer.class,foreign)).isZero();
        } finally {
            jdbc.update("DELETE FROM ab_role_data_scope WHERE role_id=?",foreign);
            jdbc.update("DELETE FROM ab_role WHERE id=?",foreign);
            jdbc.update("DELETE FROM ab_tenant WHERE id=?",foreignTenant);
        }
    }

    @Test void actualActionScopeFailureRollsBackTheAlreadyWrittenDefaultAndAllScopes() {
        drafts.apply(rolePid,editRequest(drafts.snapshot(rolePid)));
        var before=persistedScopes();long epoch=com.auraboot.framework.meta.cache.DataAccessCacheEpoch.current();
        String function="reject_scope_"+roleId;
        jdbc.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'scope mutation failure'; END $$");
        jdbc.execute("CREATE TRIGGER "+function+" BEFORE INSERT OR UPDATE ON ab_role_data_scope FOR EACH ROW WHEN (NEW.role_id="+roleId+") EXECUTE FUNCTION "+function+"()");
        try {
            assertThatThrownBy(()->matrix.setDefaultRoleScope(getTestTenant().getId(),roleId,"all"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(defaultScope()).isEqualTo("self");assertThat(persistedScopes()).isEqualTo(before);
            assertThat(com.auraboot.framework.meta.cache.DataAccessCacheEpoch.current()).isEqualTo(epoch);
        } finally {jdbc.execute("DROP TRIGGER "+function+" ON ab_role_data_scope");jdbc.execute("DROP FUNCTION "+function+"()");}
    }

    @Test void scopeManagementRejectsAnExplicitForeignTenantEvenWithValidLocalRole() {
        assertThatThrownBy(()->matrix.setDefaultRoleScope(getTestTenant().getId()+1,roleId,"all"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->matrix.setRoleScope(getTestTenant().getId()+1,roleId,new DataScopeUpdateRequest(resource,"read","all","MAX")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->scopes.removeScope(getTestTenant().getId()+1,roleId,resource,"read"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(defaultScope()).isNull();assertThat(persistedScopes()).isEmpty();
    }

    @Test void deletedRolesAndUnmappedActionsCannotAcquireScopeConfiguration() {
        assertThatThrownBy(()->matrix.setRoleScope(getTestTenant().getId(),roleId,new DataScopeUpdateRequest(resource,"read","all","MAX")))
                .isInstanceOf(RootUnCheckedException.class);
        jdbc.update("UPDATE ab_role SET deleted_flag=TRUE WHERE tenant_id=? AND id=?",getTestTenant().getId(),roleId);
        assertThatThrownBy(()->roleAuthoring.setDefaultDataScopeType(roleId,"all"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(defaultScope()).isNull();assertThat(persistedScopes()).isEmpty();
    }

    @Test void savesDutiesDefaultAndPerActionOverrideAsOneState() {
        var baseline = drafts.snapshot(rolePid);
        var saved = drafts.apply(rolePid, editRequest(baseline));
        assertThat(persistedCodes()).containsExactly(readCode, updateCode);
        assertThat(defaultScope()).isEqualTo("self");
        assertThat(persistedScopes()).containsExactly(
                Map.of("action_code", "read", "scope_type", "self"),
                Map.of("action_code", "update", "scope_type", "none"));
        assertThat(saved.revision()).isNotEqualTo(baseline.revision());
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(saved.revision());
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
    }

    @Test void failureAfterRealGrantDefaultAndScopeWritesRollsBackEverything() {
        var baseline = drafts.snapshot(rolePid);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(persistedCodes()).containsExactly(readCode, updateCode);
            assertThat(defaultScope()).isEqualTo("self");
            assertThat(persistedScopes()).anyMatch(row -> "none".equals(row.get("scope_type")));
            throw new IllegalStateException("Fault after actual authorization scope write");
        }).when(scopes).setScope(eq(getTestTenant().getId()), eq(roleId), eq(resource), eq("update"), eq("none"), eq("MAX"));
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("after actual");
        assertThat(persistedCodes()).isEmpty();
        assertThat(defaultScope()).isNull();
        assertThat(persistedScopes()).isEmpty();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void concurrentDraftsHaveOneWinnerAndOneStaleRevision() throws Exception {
        var baseline = drafts.snapshot(rolePid);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = List.of(readCapability, editCapability).stream().map(capability -> pool.submit(() -> {
                MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), "draft-test");
                MetaContext.setMemberId(getTestTenantMember().getId());
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent draft did not start");
                    drafts.apply(rolePid, new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(capability),
                            Set.of(), false, null, false, List.of()));
                    return "saved";
                } catch (RootUnCheckedException conflict) {
                    // CATCH: expected stale-version outcome; other failures still fail the future.
                    if (conflict.getResponseCode() != ResponseCode.CasVersionConflict) throw conflict;
                    return "conflict";
                } finally { MetaContext.clear(); }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("saved", "conflict");
        }
        assertThat(persistedCodes()).contains(readCode).hasSizeBetween(1, 2);
    }

    @Test void changingBoundFieldPolicyInvalidatesAnEarlierDraft() {
        String policyPid = UniqueIdGenerator.generate();
        jdbc.update("""
                INSERT INTO ab_data_permission_policy (tenant_id,pid,name,model_code,policy_type,field_code,
                                                      mask_type,priority,enabled,created_at,updated_at)
                VALUES (?,?,?,?,'column','secret','hide',10,true,now(),now())
                """, getTestTenant().getId(), policyPid, resource, resource);
        jdbc.update("INSERT INTO ab_data_permission_role_binding (tenant_id,policy_pid,role_pid) VALUES (?,?,?)",
                getTestTenant().getId(), policyPid, rolePid);
        var baseline = drafts.snapshot(rolePid);
        jdbc.update("UPDATE ab_data_permission_policy SET mask_type='hash',updated_at=now() WHERE tenant_id=? AND pid=?",
                getTestTenant().getId(), policyPid);
        assertThatThrownBy(() -> drafts.apply(rolePid, editRequest(baseline)))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(persistedCodes()).isEmpty();
    }

    @Test void fieldBindingsCommitWithDutiesAndScopesAndCanBeExplicitlyRemoved() {
        String policy = fieldPolicy();
        var saved = drafts.apply(rolePid, fieldsRequest(drafts.snapshot(rolePid), Set.of(policy)));
        assertThat(saved.fieldPolicies()).extracting(view -> view.policyPid()).containsExactly(policy);
        assertThat(persistedCodes()).containsExactly(readCode, updateCode);
        assertThat(defaultScope()).isEqualTo("self");
        assertThat(fieldBindings.findByRolePid(getTestTenant().getId(), rolePid)).hasSize(1);
        var removed = drafts.apply(rolePid, fieldsRequest(saved, Set.of()));
        assertThat(removed.fieldPolicies()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_data_permission_policy WHERE tenant_id=? AND pid=?",
                Long.class, getTestTenant().getId(), policy)).isEqualTo(1L);
    }

    @Test void fieldGridCompilesHiddenAndReadOnlyWithoutChangingExistingGrants() {
        fieldPolicy();
        var baseline=drafts.apply(rolePid,editRequest(drafts.snapshot(rolePid)));
        var original=persistedCodes();
        var request=gridRequest(baseline,"hidden");
        assertThat(drafts.preview(rolePid,request).fieldAccessChanges()).singleElement().satisfies(c->assertThat(c.access()).isEqualTo("hidden"));
        assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).isEmpty();
        var hidden=drafts.apply(rolePid,request);
        assertThat(navigation.auditLog(rolePid)).anySatisfy(a->assertThat(a.changes()).anySatisfy(c->{assertThat(c.kind()).isEqualTo("field");assertThat(c.after()).isEqualTo("hidden");}));
        assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).extracting(p->p.getPolicyType()).containsExactlyInAnyOrder("column","field_write");
        assertThat(hidden.fieldPolicies()).isEmpty();
        assertThat(persistedCodes()).isEqualTo(original);
        var readonly=drafts.apply(rolePid,gridRequest(hidden,"read_only"));
        assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).extracting(p->p.getPolicyType()).containsExactly("field_write");
        drafts.apply(rolePid,gridRequest(readonly,"inherit"));
        assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).isEmpty();
        assertThat(persistedCodes()).isEqualTo(original);
    }

    @Test void faultAfterActualManagedFieldWriteRollsBackTheWholeRoleDraft() {
        fieldPolicy();
        var baseline=drafts.snapshot(rolePid);
        var nativeBindings=sqlSession.getMapper(com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper.class);
        doAnswer(call->{
            nativeBindings.insertBinding(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).hasSize(1);
            throw new IllegalStateException("Fault after role-managed field policy insert");
        }).when(fieldBindings).insertBinding(eq(getTestTenant().getId()),org.mockito.ArgumentMatchers.anyString(),eq(rolePid));
        assertThatThrownBy(()->drafts.apply(rolePid,gridRequest(baseline,"hidden"))).isInstanceOf(IllegalStateException.class);
        assertThat(policyMapper.findManagedByRole(getTestTenant().getId(),rolePid)).isEmpty();
        assertThat(persistedCodes()).isEmpty();
        assertThat(defaultScope()).isNull();
    }

    private RoleAuthorizationDraftRequest gridRequest(RoleAuthorizationDraftSnapshot baseline,String access) {
        return new RoleAuthorizationDraftRequest(baseline.revision(),Set.of(editCapability),Set.of(),true,"self",true,List.of(),null,null,
                List.of(new com.auraboot.framework.permission.dto.RoleFieldAccess.Change(resource,resource+"_secret",access)));
    }

    @Test void failureAfterActualFieldBindingRollsBackDutiesDefaultsScopesAndFieldChanges() {
        String policy = fieldPolicy();
        var baseline = drafts.snapshot(rolePid);
        var nativeBindings = sqlSession.getMapper(com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper.class);
        doAnswer(invocation -> {
            nativeBindings.insertBinding(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            assertThat(persistedCodes()).containsExactly(readCode, updateCode);
            assertThat(fieldBindings.findByRolePid(getTestTenant().getId(), rolePid)).hasSize(1);
            throw new IllegalStateException("Fault after actual field binding write");
        }).when(fieldBindings).insertBinding(eq(getTestTenant().getId()), eq(policy), eq(rolePid));
        assertThatThrownBy(() -> drafts.apply(rolePid, fieldsRequest(baseline, Set.of(policy))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("actual field binding");
        assertThat(persistedCodes()).isEmpty();
        assertThat(defaultScope()).isNull();
        assertThat(persistedScopes()).isEmpty();
        assertThat(fieldBindings.findByRolePid(getTestTenant().getId(), rolePid)).isEmpty();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void unboundCandidateChangedAfterReviewConflictsBeforeAnyWrite() {
        String policy = fieldPolicy();
        var baseline = drafts.snapshot(rolePid);
        var request = fieldsRequest(baseline, Set.of(policy));
        assertThat(drafts.preview(rolePid, request).fieldPolicies().added()).hasSize(1);
        jdbc.update("UPDATE ab_data_permission_policy SET mask_type='hash',updated_at=now() WHERE tenant_id=? AND pid=?",
                getTestTenant().getId(), policy);
        assertThatThrownBy(() -> drafts.apply(rolePid, request)).isInstanceOfSatisfying(RootUnCheckedException.class,
                error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(persistedCodes()).isEmpty();
        assertThat(fieldBindings.findByRolePid(getTestTenant().getId(), rolePid)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"role-inactive","role-deleted","assignment-inactive","assignment-deleted","expired","future"})
    void fieldPoliciesUseOnlyCurrentActiveRoleAssignments(String mode) {
        String policy = fieldPolicy();
        jdbc.update("INSERT INTO ab_data_permission_role_binding (tenant_id,policy_pid,role_pid) VALUES (?,?,?)",
                getTestTenant().getId(), policy, rolePid);
        jdbc.update("""
                INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag)
                VALUES (?,?,?,?,?,'active',false)
                """, IdWorker.getId(), UniqueIdGenerator.generate(), getTestTenant().getId(), getTestTenantMember().getId(), roleId);
        assertThat(policyMapper.findEffectivePolicies(getTestTenant().getId(), resource, getTestTenantMember().getId())).hasSize(1);
        switch (mode) {
            case "role-inactive" -> jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?",roleId);
            case "role-deleted" -> jdbc.update("UPDATE ab_role SET deleted_flag=true WHERE id=?",roleId);
            case "assignment-inactive" -> jdbc.update("UPDATE ab_user_role SET status='inactive' WHERE role_id=?",roleId);
            case "assignment-deleted" -> jdbc.update("UPDATE ab_user_role SET deleted_flag=true WHERE role_id=?",roleId);
            case "expired" -> jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE role_id=?",roleId);
            case "future" -> jdbc.update("UPDATE ab_user_role SET effective_date=CURRENT_DATE+1 WHERE role_id=?",roleId);
        }
        assertThat(policyMapper.findEffectivePolicies(getTestTenant().getId(), resource, getTestTenantMember().getId())).isEmpty();
    }

    @Test void menuAssignmentDoesNotCreateBusinessGrantsAndConflictsWithAnOlderJointDraft() {
        String menuPid=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_menu(id,pid,tenant_id,code,name,type,path,visible,status,deleted_flag) VALUES(?,?,?,?,?,1,'/home',TRUE,'active',FALSE)",IdWorker.getId(),menuPid,getTestTenant().getId(),resource+"_menu","Navigation fixture");
        try {
            var before=drafts.snapshot(rolePid);
            long grantsBefore=jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?",Long.class,roleId);
            var menuOnly=new RoleAuthorizationDraftRequest(before.revision(),Set.of(),Set.of(),false,null,false,List.of(),null,Set.of(menuPid));
            assertThat(drafts.preview(rolePid,menuOnly).navigation().selectedMenuPids()).containsExactly(menuPid);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_state WHERE role_id=?",Integer.class,roleId)).isZero();
            var saved=drafts.apply(rolePid,menuOnly);
            assertThat(saved.navigation().mode()).isEqualTo("explicit");
            assertThat(saved.navigation().selectedMenuPids()).containsExactly(menuPid);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?",Long.class,roleId)).isEqualTo(grantsBefore);
            assertThatThrownBy(()->drafts.apply(rolePid,menuOnly)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            var revision=saved.revision();
            navigation.save(rolePid,new com.auraboot.framework.menu.dto.RoleNavigation.Write(saved.navigation().revision(),Set.of()));
            assertThatThrownBy(()->drafts.apply(rolePid,new RoleAuthorizationDraftRequest(revision,Set.of(editCapability),Set.of(),false,null,false,List.of())))
                    .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        } finally {jdbc.update("DELETE FROM ab_role_navigation_menu WHERE menu_pid=?",menuPid);jdbc.update("DELETE FROM ab_menu WHERE pid=?",menuPid);}
    }

    @Test void failureAfterActualMenuAndBusinessWritesRollsBackTheWholeJointDraft() {
        String menuPid=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_menu(id,pid,tenant_id,code,name,type,path,visible,status,deleted_flag) VALUES(?,?,?,?,?,1,'/home',TRUE,'active',FALSE)",IdWorker.getId(),menuPid,getTestTenant().getId(),resource+"_rollback_menu","Navigation rollback fixture");
        try {
            var before=drafts.snapshot(rolePid);
            long grantsBefore=jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?",Long.class,roleId);
            doAnswer(call->{call.callRealMethod();assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_menu WHERE role_id=?",Integer.class,roleId)).isEqualTo(1);throw new IllegalStateException("Fault after actual navigation write");})
                    .when(navigation).apply(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(Set.of(menuPid)));
            assertThatThrownBy(()->drafts.apply(rolePid,new RoleAuthorizationDraftRequest(before.revision(),Set.of(editCapability),Set.of(),true,"self",false,List.of(),null,Set.of(menuPid))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Fault after actual navigation write");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_state WHERE role_id=?",Integer.class,roleId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_menu WHERE role_id=?",Integer.class,roleId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?",Long.class,roleId)).isEqualTo(grantsBefore);
        } finally {reset(navigation);jdbc.update("DELETE FROM ab_role_navigation_menu WHERE menu_pid=?",menuPid);jdbc.update("DELETE FROM ab_menu WHERE pid=?",menuPid);}
    }

    private RoleAuthorizationDraftRequest grantFieldsRequest(RoleAuthorizationDraftSnapshot baseline,
            com.auraboot.framework.permission.dto.RoleGrantFields.Change change) {
        return new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), false,
                null, false, List.of(), null, null, null, List.of(change));
    }

    private RoleAuthorizationDraftSnapshot grantFieldsBaseline() {
        fieldPolicy();
        var initial = drafts.snapshot(rolePid);
        return drafts.apply(rolePid, new RoleAuthorizationDraftRequest(initial.revision(), Set.of(editCapability), Set.of(), false, null, false, List.of()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void businessGrantFieldsAreAuthoredWithTheSameBindingAndScope(boolean existing) throws Exception {
        fieldPolicy();
        String code = "business." + resource + ".manage";
        String duty = resource + ".cap.business_fields";
        jdbc.update("""
                INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,
                                           source,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,'operation',?,'manage','test','active',false,now(),now())
                """, UniqueIdGenerator.generate(), getTestTenant().getId(), code, "Manage business project", resource);
        catalog.saveDefinition(CapabilityDefinitionDTO.builder().code(duty).group("Business fields")
                .includes(List.of(code)).build());
        var initial = drafts.snapshot(rolePid);
        if (existing) {
            drafts.apply(rolePid, new RoleAuthorizationDraftRequest(initial.revision(), Set.of(duty), Set.of(),
                    true, "self", true, List.of()));
        }
        var baseline = drafts.snapshot(rolePid);
        var selection = new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(duty), Set.of(),
                true, "self", true, List.of());
        var candidate = drafts.preview(rolePid, selection).grantFieldCandidates().stream()
                .filter(binding -> code.equals(binding.permissionCode())).findFirst().orElseThrow();
        assertThat(candidate.editable()).isTrue();
        assertThat(candidate.bindingPid() != null).isEqualTo(existing);
        String before = migrationBindingBytes();
        var change = existing
                ? new com.auraboot.framework.permission.dto.RoleGrantFields.Change(candidate.bindingPid(), List.of(resource+"_secret"), List.of())
                : newFieldChange(candidate, List.of(resource+"_secret"), List.of());
        var request = new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(duty), Set.of(),
                true, "self", true, List.of(), null, null, null, List.of(change));
        assertThat(drafts.preview(rolePid, request).grantFieldChanges()).hasSize(1);
        assertThat(migrationBindingBytes()).isEqualTo(before);
        var saved = drafts.apply(rolePid, request);
        var binding = saved.grantFields().stream().filter(row -> code.equals(row.permissionCode())).findFirst().orElseThrow();
        assertThat(binding.legacy()).isFalse();
        assertThat(binding.readFields()).containsExactly(resource+"_secret");
        assertThat(binding.writeFields()).isEmpty();
        assertThat(persistedCodes()).containsExactly(code);
        assertThat(defaultScope()).isEqualTo("self");
        assertThat(roleAuthoring.assignRoleToMember(getTestTenantMember().getId(), roleId, getTestTenant().getId())).isTrue();
        administrator.close(); administrator = null;
        var record = new java.util.LinkedHashMap<String,Object>();
        record.put("pid", UniqueIdGenerator.generate()); record.put("tenant_id", getTestTenant().getId());
        record.put("created_by", getTestUser().getId()); record.put(resource+"_secret", "business-value");
        var decision = applicationContext.getBean(com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization.class)
                .resolve(getTestTenantMember().getId(), List.of(code), resource, record);
        assertThat(decision.result().granted()).isTrue();
        assertThat(decision.canRead(resource+"_secret")).isTrue();
        assertThat(decision.canWrite(resource+"_secret")).isFalse();
        assertThat(decision.canRead("another_field")).isFalse();
    }

    @Test void positiveGrantFieldsPreviewAndSavePreserveAllOtherBindingFacts() {
        var baseline = grantFieldsBaseline();
        var binding = baseline.grantFields().stream().filter(b -> b.permissionCode().equals(readCode)).findFirst().orElseThrow();
        assertThat(binding.legacy()).isTrue(); assertThat(binding.editable()).isTrue();
        String facts = jdbc.queryForObject("SELECT (to_jsonb(g)-'field_scope'-'updated_at'-'updated_by')::text FROM ab_role_permission g WHERE pid=? AND tenant_id=?", String.class, binding.bindingPid(), getTestTenant().getId());
        var request = grantFieldsRequest(baseline, new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(resource+"_secret"), List.of()));
        var preview = drafts.preview(rolePid, request);
        assertThat(preview.grantFieldChanges()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT field_scope IS NULL FROM ab_role_permission WHERE pid=? AND tenant_id=?", Boolean.class, binding.bindingPid(), getTestTenant().getId())).isTrue();
        var saved = drafts.apply(rolePid, request);
        var view = saved.grantFields().stream().filter(b -> b.bindingPid().equals(binding.bindingPid())).findFirst().orElseThrow();
        assertThat(view.legacy()).isFalse(); assertThat(view.readFields()).containsExactly(resource+"_secret"); assertThat(view.writeFields()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(g)-'field_scope'-'updated_at'-'updated_by')::text FROM ab_role_permission g WHERE pid=? AND tenant_id=?", String.class, binding.bindingPid(), getTestTenant().getId())).isEqualTo(facts);
        assertThat(saved.grantFields().stream().filter(b -> b.permissionCode().equals(updateCode)).findFirst().orElseThrow().legacy()).isTrue();
        assertThatThrownBy(() -> drafts.apply(rolePid, request)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }

    @Test void positiveGrantFieldsRejectUnknownDuplicateAndForeignBindingInputs() {
        var baseline = grantFieldsBaseline();
        var binding = baseline.grantFields().getFirst();
        for (var change : List.of(
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of("pid"), List.of()),
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(resource+"_secret", resource+"_secret"), List.of()),
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change(UniqueIdGenerator.generate(), List.of(), List.of()))) {
            assertThatThrownBy(() -> drafts.preview(rolePid, grantFieldsRequest(baseline, change))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void positiveGrantFieldsCannotOverwriteManagedSourceOrRevokedBinding() {
        var baseline = grantFieldsBaseline();
        var binding = baseline.grantFields().getFirst();
        var change = new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(), List.of());
        var revoke = new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(), Set.of(), false, null, false, List.of(), null, null, null, List.of(change));
        assertThatThrownBy(() -> drafts.preview(rolePid, revoke)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE ab_role_permission SET source_ref='package:fixture' WHERE tenant_id=? AND pid=?",getTestTenant().getId(),binding.bindingPid());
        var managed = drafts.snapshot(rolePid);
        assertThat(managed.grantFields().stream().filter(b -> b.bindingPid().equals(binding.bindingPid())).findFirst().orElseThrow().editable()).isFalse();
        assertThatThrownBy(() -> drafts.apply(rolePid, grantFieldsRequest(managed, change))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void positiveGrantFieldsRollBackWhenTheUnifiedAuditFails() {
        var baseline = grantFieldsBaseline();
        var binding = baseline.grantFields().getFirst();
        var change = new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(), List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("Injected final authoring audit failure")).when(navigation)
                .auditAuthorization(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(() -> drafts.apply(rolePid, grantFieldsRequest(baseline, change))).isInstanceOf(IllegalStateException.class);
        } finally { reset(navigation); }
        assertThat(jdbc.queryForObject("SELECT field_scope IS NULL FROM ab_role_permission WHERE tenant_id=? AND pid=?",Boolean.class,getTestTenant().getId(),binding.bindingPid())).isTrue();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
    }

    @Test void positiveGrantFieldsDriveOrdinaryReadsAndWritesWithoutSqlFieldEdits() {
        var baseline = grantFieldsBaseline();
        assertThat(schema.createTableByModel(resource).isSuccess()).isTrue(); physicalTable = true;
        assertThat(roleAuthoring.assignRoleToMember(getTestTenantMember().getId(), roleId, getTestTenant().getId())).isTrue();
        var created = MetaContext.runWithCommandPermitPlan("ALL", 1L, resource, null,
                () -> dynamicData.create(resource, new java.util.LinkedHashMap<>(Map.of(resource+"_secret", "private-value"))));
        String record = created.get("pid").toString();
        var changes = baseline.grantFields().stream().map(binding ->
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.<String>of(), List.<String>of())).toList();
        var request = new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), false,
                null, false, List.of(), null, null, null, changes);
        drafts.apply(rolePid, request);
        administrator.close(); administrator = null;
        assertThat(dynamicData.getById(resource, record)).containsEntry("pid", record).doesNotContainKey(resource+"_secret");
        assertThatThrownBy(() -> dynamicData.update(resource, record, Map.of(resource+"_secret", "must-not-persist")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        String table = metadata.getModelDefinition(resource).orElseThrow().getTableName();
        assertThat(jdbc.queryForObject("SELECT "+resource+"_secret FROM "+table+" WHERE pid=?",String.class,record)).isEqualTo("private-value");
    }

    @Test void positiveGrantFieldsKeepDetachedCopyProvenanceEditable() {
        var baseline = grantFieldsBaseline();
        var binding = baseline.grantFields().getFirst();
        String source = "copy:" + UniqueIdGenerator.generate() + ":" + UniqueIdGenerator.generate();
        jdbc.update("UPDATE ab_role_permission SET source_ref=? WHERE tenant_id=? AND pid=?",source,getTestTenant().getId(),binding.bindingPid());
        var copied = drafts.snapshot(rolePid);
        assertThat(copied.grantFields().stream().filter(b -> b.bindingPid().equals(binding.bindingPid())).findFirst().orElseThrow().editable()).isTrue();
        drafts.apply(rolePid, grantFieldsRequest(copied, new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(), List.of())));
        assertThat(jdbc.queryForObject("SELECT source_ref FROM ab_role_permission WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),binding.bindingPid())).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT field_scope->'readFields' FROM ab_role_permission WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),binding.bindingPid())).isEqualTo("[]");
        jdbc.update("UPDATE ab_role_permission SET source_ref='copy:not-a-host-reference' WHERE tenant_id=? AND pid=?",getTestTenant().getId(),binding.bindingPid());
        var malformed = drafts.snapshot(rolePid);
        assertThatThrownBy(() -> drafts.preview(rolePid, grantFieldsRequest(malformed,
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(), List.of(resource+"_secret"), List.of()))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private RoleAuthorizationDraftRequest newFieldsRequest(RoleAuthorizationDraftSnapshot baseline,
            List<com.auraboot.framework.permission.dto.RoleGrantFields.Change> changes, Set<String> menus) {
        return new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), true,
                "self", true, List.of(), null, menus, null, changes);
    }

    private List<com.auraboot.framework.permission.dto.RoleGrantFields.Binding> newFieldCandidates(RoleAuthorizationDraftSnapshot baseline) {
        return drafts.preview(rolePid, newFieldsRequest(baseline, null, null)).grantFieldCandidates();
    }

    private com.auraboot.framework.permission.dto.RoleGrantFields.Change newFieldChange(
            com.auraboot.framework.permission.dto.RoleGrantFields.Binding candidate, List<String> reads, List<String> writes) {
        return new com.auraboot.framework.permission.dto.RoleGrantFields.Change(null, candidate.permissionCode(), candidate.metadataRevision(), reads, writes);
    }

    @Test void newGrantFieldsAndSelectedDutiesCommitTogetherAndDriveRealAccess() {
        fieldPolicy();
        assertThat(schema.createTableByModel(resource).isSuccess()).isTrue(); physicalTable = true;
        assertThat(roleAuthoring.assignRoleToMember(getTestTenantMember().getId(),roleId,getTestTenant().getId())).isTrue();
        String record = MetaContext.runWithCommandPermitPlan("ALL",1L,resource,null,
                () -> dynamicData.create(resource,new java.util.LinkedHashMap<>(Map.of(resource+"_secret","new-grant-value")))).get("pid").toString();
        var baseline = drafts.snapshot(rolePid);
        var candidates = newFieldCandidates(baseline);
        assertThat(candidates).hasSize(2).allSatisfy(candidate -> {
            assertThat(candidate.bindingPid()).isNull(); assertThat(candidate.metadataRevision()).matches("[a-f0-9]{64}");
        });
        assertThat(persistedCodes()).isEmpty();
        var changes = candidates.stream().map(candidate -> newFieldChange(candidate,
                candidate.permissionCode().equals(readCode) ? List.of(resource+"_secret") : List.of(),List.of())).toList();
        var request = newFieldsRequest(baseline,changes,null);
        assertThat(drafts.preview(rolePid,request).grantFieldChanges()).hasSize(2);
        assertThat(persistedCodes()).isEmpty();
        var saved = drafts.apply(rolePid,request);
        assertThat(saved.grantFields()).hasSize(2).allSatisfy(binding -> {
            assertThat(binding.bindingPid()).isNotBlank(); assertThat(binding.legacy()).isFalse();
        });
        assertThat(persistedCodes()).containsExactly(readCode,updateCode);
        assertThat(defaultScope()).isEqualTo("self");
        administrator.close(); administrator=null;
        assertThat(dynamicData.getById(resource,record)).containsEntry(resource+"_secret","new-grant-value");
        assertThatThrownBy(() -> dynamicData.update(resource,record,Map.of(resource+"_secret","must-not-persist")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThat(jdbc.queryForObject("SELECT "+resource+"_secret FROM "+metadata.getModelDefinition(resource).orElseThrow().getTableName()+" WHERE pid=?",String.class,record)).isEqualTo("new-grant-value");
    }

    @Test void newGrantFieldsFailureAfterFieldsAndMenuWritesRollsBackFirstSave() {
        fieldPolicy();
        String menuPid=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_menu(id,pid,tenant_id,code,name,type,path,visible,status,deleted_flag) VALUES(?,?,?,?,?,1,'/home',TRUE,'active',FALSE)",IdWorker.getId(),menuPid,getTestTenant().getId(),resource+"_new_fields_menu","First field configuration");
        try {
            var baseline=drafts.snapshot(rolePid);
            var changes=newFieldCandidates(baseline).stream().map(candidate -> newFieldChange(candidate,List.of(),List.of())).toList();
            doAnswer(call -> {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND field_scope IS NOT NULL",Long.class,getTestTenant().getId(),roleId)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_menu WHERE role_id=?",Long.class,roleId)).isEqualTo(1L);
                throw new IllegalStateException("Fault after actual first-save field and menu writes");
            }).when(navigation).auditAuthorization(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            assertThatThrownBy(() -> drafts.apply(rolePid,newFieldsRequest(baseline,changes,Set.of(menuPid))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Fault after actual first-save");
            assertThat(persistedCodes()).isEmpty(); assertThat(defaultScope()).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_data_scope WHERE role_id=?",Long.class,roleId)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_navigation_state WHERE role_id=?",Long.class,roleId)).isZero();
            reset(navigation); assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
        } finally {reset(navigation);jdbc.update("DELETE FROM ab_role_navigation_menu WHERE menu_pid=?",menuPid);jdbc.update("DELETE FROM ab_menu WHERE pid=?",menuPid);}
    }

    @Test void newGrantFieldsReferencesCannotResetExistingBindingsOrSelectUnchosenPermissions() {
        fieldPolicy();
        var baseline=drafts.snapshot(rolePid);
        var candidates=newFieldCandidates(baseline);
        var update=candidates.stream().filter(c -> c.permissionCode().equals(updateCode)).findFirst().orElseThrow();
        var unselected=new RoleAuthorizationDraftRequest(baseline.revision(),Set.of(readCapability),Set.of(),false,null,false,List.of(),null,null,null,List.of(newFieldChange(update,List.of(),List.of())));
        assertThatThrownBy(() -> drafts.preview(rolePid,unselected)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var missingRevision=new com.auraboot.framework.permission.dto.RoleGrantFields.Change(null,readCode,null,List.of(),List.of());
        assertThatThrownBy(() -> drafts.apply(rolePid,newFieldsRequest(baseline,List.of(missingRevision),null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(persistedCodes()).isEmpty();
        var saved=drafts.apply(rolePid,newFieldsRequest(baseline,null,null));
        var before=jdbc.queryForList("SELECT to_jsonb(g)::text AS facts FROM ab_role_permission g WHERE role_id=? ORDER BY pid",roleId);
        assertThatThrownBy(() -> drafts.apply(rolePid,newFieldsRequest(saved,List.of(newFieldChange(update,List.of(),List.of())),null)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var binding=saved.grantFields().getFirst();
        var mixed=new com.auraboot.framework.permission.dto.RoleGrantFields.Change(binding.bindingPid(),binding.permissionCode(),binding.metadataRevision(),List.of(),List.of());
        assertThatThrownBy(() -> drafts.preview(rolePid,newFieldsRequest(saved,List.of(mixed),null))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForList("SELECT to_jsonb(g)::text AS facts FROM ab_role_permission g WHERE role_id=? ORDER BY pid",roleId)).isEqualTo(before);
    }

    @Test void newGrantFieldsDefinitionChangeRejectsTheReviewedFieldsBeforeAnyGrantExists() {
        fieldPolicy();
        var baseline=drafts.snapshot(rolePid);
        var candidate=newFieldCandidates(baseline).stream().filter(c -> c.permissionCode().equals(readCode)).findFirst().orElseThrow();
        var request=newFieldsRequest(baseline,List.of(newFieldChange(candidate,List.of(resource+"_secret"),List.of())),null);
        drafts.preview(rolePid,request);
        var field=fields.selectById(fieldId);
        var extension=new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(new java.util.HashMap<>(Map.of("displayName","Changed field definition")));field.setExtension(extension);fields.updateById(field);metadata.clearAllCache();
        assertThat(drafts.snapshot(rolePid).revision()).isEqualTo(baseline.revision());
        assertThatThrownBy(() -> drafts.apply(rolePid,request)).isInstanceOfSatisfying(com.auraboot.framework.exception.BusinessException.class,
                error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        assertThat(persistedCodes()).isEmpty(); assertThat(defaultScope()).isNull();
    }

    private RoleAuthorizationDraftRequest fieldsRequest(RoleAuthorizationDraftSnapshot baseline, Set<String> selected) {
        return new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), true,
                "self", true, List.of(new DataScopeUpdateRequest(resource, "update", "none", "MAX")), selected);
    }

    private String fieldPolicy() {
        grantCommittedPermissionToTestRole(com.auraboot.framework.permission.constants.MetaPermission.PERMISSION_MANAGE,
                "operation", "permission", "update", "Manage field policy fixtures");
        var model = new com.auraboot.framework.meta.entity.Model();
        model.setTenantId(getTestTenant().getId()); model.setPid(UniqueIdGenerator.generate()); model.setCode(resource);
        model.setVersion(1); model.setIsCurrent(true); model.setStatus("published"); model.setDeletedFlag(false);
        var extension = new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(new java.util.HashMap<>(Map.of("modelType","entity","displayName","Draft transaction project")));
        model.setExtension(extension);
        models.insert(model); modelId = model.getId();
        var field = new com.auraboot.framework.meta.entity.Field();
        field.setTenantId(getTestTenant().getId()); field.setPid(UniqueIdGenerator.generate()); field.setCode(resource+"_secret");
        field.setDataType("string"); field.setVersion(1); field.setIsCurrent(true); field.setStatus("published"); field.setDeletedFlag(false);
        field.setFeature(new com.auraboot.framework.meta.entity.payload.FieldFeatureBean());
        var fieldExtension=new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        fieldExtension.setExtension(new java.util.HashMap<>(Map.of("displayName","Engineering remark")));field.setExtension(fieldExtension);
        fields.insert(field); fieldId = field.getId();
        var binding = new com.auraboot.framework.meta.entity.ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId()); binding.setModelId(modelId); binding.setFieldId(fieldId); binding.setFieldOrder(0);
        modelFields.insert(binding); metadata.clearAllCache();
        String pid = UniqueIdGenerator.generate();
        jdbc.update("""
                INSERT INTO ab_data_permission_policy (tenant_id,pid,name,model_code,policy_type,field_code,
                                                      mask_type,priority,enabled,created_at,updated_at)
                VALUES (?,?,?,?,'column',?,'hide',10,true,now(),now())
                """, getTestTenant().getId(), pid, resource, resource, resource+"_secret");
        return pid;
    }

    private RoleAuthorizationDraftRequest editRequest(RoleAuthorizationDraftSnapshot baseline) {
        return new RoleAuthorizationDraftRequest(baseline.revision(), Set.of(editCapability), Set.of(), true,
                "self", true, List.of(new DataScopeUpdateRequest(resource, "update", "none", "MAX")));
    }

    private List<String> persistedCodes() {
        return jdbc.queryForList("""
                SELECT p.code FROM ab_role_permission rp JOIN ab_permission p ON p.id=rp.permission_id
                WHERE rp.tenant_id=? AND rp.role_id=? AND rp.deleted_flag=false ORDER BY p.code
                """, String.class, getTestTenant().getId(), roleId);
    }

    private String defaultScope() {
        return jdbc.queryForObject("SELECT default_data_scope_type FROM ab_role WHERE tenant_id=? AND id=?",
                String.class, getTestTenant().getId(), roleId);
    }

    private List<Map<String, Object>> persistedScopes() {
        return jdbc.queryForList("SELECT action_code,scope_type FROM ab_role_data_scope WHERE tenant_id=? AND role_id=? ORDER BY action_code",
                getTestTenant().getId(), roleId);
    }
}
