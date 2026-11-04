package com.auraboot.framework.permission;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real grant/membership/scope/policy SQL and MVCC; not a product command or model-write journey. */
@SpringBootTest(classes = TestApplication.class, properties = "spring.data.redis.host=")
@Transactional(propagation = Propagation.NEVER)
class BusinessCommandGrantPostgresIT extends BaseIntegrationTest {
    @Autowired private BusinessCommandRecordAuthorization authorization;
    @Autowired private RolePermissionMapper grants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private DataScopeService scopes;
    private String resource, model, code;
    private long permissionId, roleA, roleB, bindingA, bindingB;

    @BeforeEach void ownedGrants() throws Exception {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        resource = "bg_" + suffix; model = "bg_model_" + suffix; code = resource + ".confirm";
        permissionId = jdbc.queryForObject("""
                INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,source,status,deleted_flag)
                VALUES (?,?,?,?,'operation',?,'confirm','test','active',FALSE) RETURNING id
                """, Long.class, UniqueIdGenerator.generate(), tenant(), code, "Confirm owned receipt", resource);
        roleA = role("creator", "self"); roleB = role("conditional", "all");
        bindingA = bind(roleA, null); bindingB = bind(roleB, warehouseAst("Shanghai"));
        // A shared test identity must not acquire this fixture through an unrelated wildcard.
        assertThat(grants.findBusinessGrants(tenant(), getTestUser().getId(), member(), code))
                .extracting(RolePermissionMapper.BusinessGrantRow::getId).containsExactlyInAnyOrder(bindingA, bindingB);
    }

    @AfterEach void cleanup() {
        reset(scopes);
        for (long role : new long[]{roleA, roleB}) {
            if (role == 0) continue;
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?", tenant(), role);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?", tenant(), role);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND role_id=?", tenant(), role);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?", tenant(), role);
        }
        if (permissionId != 0) jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND id=?", tenant(), permissionId);
    }

    @Test void completeGrantsDoNotCrossMultiplyRoleScopeAndCondition() {
        assertThat(allowed(false, "Beijing")).isFalse();
        assertThat(allowed(true, "Beijing")).isTrue();
        assertThat(allowed(false, "Shanghai")).isTrue();
    }

    @Test void freshDecisionsObserveGrantAssignmentAndRoleRevocationWithoutCacheEviction() {
        jdbc.update("UPDATE ab_role SET default_data_scope_type='none' WHERE id=?", roleA);
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_role_permission SET expiry_date=CURRENT_DATE-1 WHERE id=?", bindingB);
        assertThat(allowed(false, "Shanghai")).isFalse();
        jdbc.update("UPDATE ab_role_permission SET expiry_date=NULL WHERE id=?", bindingB);
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE role_id=? AND tenant_id=?", roleB, tenant());
        assertThat(allowed(false, "Shanghai")).isFalse();
        jdbc.update("UPDATE ab_user_role SET expiry_date=NULL WHERE role_id=? AND tenant_id=?", roleB, tenant());
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", roleB);
        assertThat(allowed(false, "Shanghai")).isFalse();
        jdbc.update("UPDATE ab_role SET status='active' WHERE id=?", roleB);
        assertThat(allowed(false, "Shanghai")).isTrue();
    }

    @Test void roleSpecificOverrideAndDataScopeAstCannotBeIgnored() throws Exception {
        jdbc.update("UPDATE ab_role SET default_data_scope_type='none' WHERE id=?", roleA);
        jdbc.update("UPDATE ab_role_permission SET condition_ast=NULL,data_scope_ast=?::jsonb WHERE id=?", warehouseAst("Shanghai"), bindingB);
        assertThat(allowed(false, "Shanghai")).isTrue();
        assertThat(allowed(false, "Beijing")).isFalse();
        scopes.setScope(tenant(), roleB, resource, "confirm", "none", "MAX");
        assertThat(allowed(false, "Shanghai")).isFalse();
        scopes.setScope(tenant(), roleB, resource, "confirm", "all", "MAX");
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_role_data_scope SET scope_config='{}'::jsonb WHERE tenant_id=? AND role_id=?", tenant(), roleB);
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_role_data_scope SET scope_config='{\"unsupported\":true}'::jsonb WHERE tenant_id=? AND role_id=?", tenant(), roleB);
        assertThat(allowed(false, "Shanghai")).isFalse();
        jdbc.update("UPDATE ab_role_data_scope SET scope_config=NULL WHERE tenant_id=? AND role_id=?", tenant(), roleB);
        jdbc.update("UPDATE ab_role_permission SET data_scope_ast=?::jsonb WHERE id=?", "{\"type\":\"unsupported\"}", bindingB);
        assertThat(allowed(false, "Shanghai")).isFalse();
    }

    @Test void missingScopeAndForeignTenantDoNotBecomeAnUnrestrictedGrant() {
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_role SET default_data_scope_type=NULL WHERE id IN (?,?)", roleA, roleB);
        assertThat(allowed(false, "Shanghai")).isFalse();
        var foreign = new HashMap<>(record(true, "Shanghai")); foreign.put("tenant_id", tenant() + 1);
        assertThat(authorization.evaluate(member(), List.of(code), model, foreign).granted()).isFalse();
    }

    @Test void authoringCommitBetweenScopeAndGuardCannotSpliceTwoDeniedVersionsIntoAllow() {
        jdbc.update("UPDATE ab_role SET default_data_scope_type='none' WHERE id=?", roleA);
        AtomicBoolean changed = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (changed.compareAndSet(false, true)) {
                TransactionTemplate writer = new TransactionTemplate(transactions);
                writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                writer.executeWithoutResult(status -> {
                    jdbc.update("UPDATE ab_role SET default_data_scope_type='none' WHERE id=?", roleB);
                    jdbc.update("UPDATE ab_role_permission SET condition_ast=NULL WHERE id=?", bindingB);
                });
            }
            return result;
        }).when(scopes).resolveScopeForRole(eq(member()), eq(roleB), eq(resource), eq("confirm"), eq(model));
        // Before: ALL + Shanghai guard denies Beijing. After: NONE + unconditional also denies.
        // READ COMMITTED would combine old ALL with new unconditional and incorrectly permit it.
        assertThat(allowed(false, "Beijing")).isFalse();
        assertThat(changed).isTrue();
        assertThat(jdbc.queryForObject("SELECT default_data_scope_type FROM ab_role WHERE id=?", String.class, roleB)).isEqualTo("none");
        assertThat(allowed(false, "Beijing")).isFalse();
    }

    @Test void corruptOrMenuOnlyPermissionDefinitionCannotReuseAnEarlierAllow() {
        assertThat(allowed(false, "Shanghai")).isTrue();
        jdbc.update("UPDATE ab_permission SET resource_type='page' WHERE id=?", permissionId);
        assertThat(allowed(false, "Shanghai")).isFalse();
        jdbc.update("UPDATE ab_permission SET resource_type='operation' WHERE id=?", permissionId);
        assertThat(allowed(false, "Shanghai")).isTrue();
    }

    @Test void guardActorRolesExcludeExpiredFutureAndInactiveAssignments() throws Exception {
        jdbc.update("UPDATE ab_role SET default_data_scope_type='none' WHERE id=?", roleA);
        String actorAst = json.writeValueAsString(ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.ACTOR, "roles", DataType.COLLECTION), Operator.CONTAINS_ELEMENT,
                new Operand.LiteralOperand(resource + "_creator", DataType.STRING)));
        jdbc.update("UPDATE ab_role_permission SET condition_ast=?::jsonb WHERE id=?", actorAst, bindingB);
        assertThat(allowed(false, "Beijing")).isTrue();
        jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE role_id=? AND tenant_id=?", roleA, tenant());
        assertThat(allowed(false, "Beijing")).isFalse();
        jdbc.update("UPDATE ab_user_role SET expiry_date=NULL,effective_date=CURRENT_DATE+1 WHERE role_id=? AND tenant_id=?", roleA, tenant());
        assertThat(allowed(false, "Beijing")).isFalse();
        jdbc.update("UPDATE ab_user_role SET effective_date=NULL WHERE role_id=? AND tenant_id=?", roleA, tenant());
        assertThat(allowed(false, "Beijing")).isTrue();
        jdbc.update("UPDATE ab_role SET status='inactive' WHERE id=?", roleA);
        assertThat(allowed(false, "Beijing")).isFalse();
        jdbc.update("UPDATE ab_role SET status='active' WHERE id=?", roleA);
        assertThat(allowed(false, "Beijing")).isTrue();
    }

    private long role(String name, String scope) {
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO ab_role (id,pid,tenant_id,code,name,type,status,deleted_flag,default_data_scope_type)
                VALUES (?,?,?,?,?,'custom','active',FALSE,?)
                """, id, UniqueIdGenerator.generate(), tenant(), resource + "_" + name, "Owned " + name, scope);
        jdbc.update("""
                INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag)
                VALUES (?,?,?,?,?,'active',FALSE)
                """, IdWorker.getId(), UniqueIdGenerator.generate(), tenant(), member(), id);
        return id;
    }
    private long bind(long role, String ast) {
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO ab_role_permission (id,pid,tenant_id,role_id,permission_id,grant_type,condition_ast,status,deleted_flag)
                VALUES (?,?,?,?,?,'grant',?::jsonb,'active',FALSE)
                """, id, UniqueIdGenerator.generate(), tenant(), role, permissionId, ast);
        return id;
    }
    private boolean allowed(boolean own, String warehouse) {
        return authorization.evaluate(member(), List.of(code), model, record(own, warehouse)).granted();
    }
    private Map<String, Object> record(boolean own, String warehouse) {
        return Map.of("pid", "owned-receipt", "tenant_id", tenant(), "created_by", own ? getTestUser().getId() : -1L,
                "warehouse", warehouse);
    }
    private String warehouseAst(String warehouse) throws Exception {
        return json.writeValueAsString(ConditionNode.CompareNode.of(
                new Operand.PathOperand(Scope.RECORD, "data.warehouse", DataType.STRING), Operator.EQ,
                new Operand.LiteralOperand(warehouse, DataType.STRING)));
    }
    private Long tenant() { return getTestTenant().getId(); }
    private Long member() { return getTestTenantMember().getId(); }
}
