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
    @Autowired private org.mybatis.spring.SqlSessionTemplate sqlSession;
    @Autowired private com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper policyMapper;
    @MockitoSpyBean private DataScopeService scopes;
    @MockitoSpyBean private com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper fieldBindings;
    @Autowired private com.auraboot.framework.meta.mapper.MetaModelMapper models;
    @Autowired private com.auraboot.framework.meta.mapper.MetaFieldMapper fields;
    @Autowired private com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper modelFields;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService metadata;
    private Long modelId, fieldId;
    private long roleId;
    private String rolePid;
    private String resource;
    private String readCode;
    private String updateCode;
    private String readCapability;
    private String editCapability;

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
