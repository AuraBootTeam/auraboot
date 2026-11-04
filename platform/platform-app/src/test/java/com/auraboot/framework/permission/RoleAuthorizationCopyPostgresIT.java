package com.auraboot.framework.permission;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TenantAdministrationFixture;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual copy transactions, persisted constraints, runtime decisions and late-write rollback. */
@SpringBootTest(classes=TestApplication.class,properties="spring.data.redis.host=")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RoleAuthorizationCopyPostgresIT extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired RolePermissionService permissions;
    @Autowired RoleService roles;
    @Autowired RolePermissionMapper grants;
    @Autowired BusinessCommandRecordAuthorization authorization;
    @MockitoSpyBean DataPermissionRoleBindingMapper policyBindings;
    private TenantAdministrationFixture administrator;
    private final List<Long> ownedRoles=new ArrayList<>();
    private long source,target,confirm,remove;
    private String sourcePid,targetPid,resource,policy;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    private String ast;

    @BeforeEach void fixture() throws Exception {
        ast=json.writeValueAsString(com.auraboot.framework.decision.ast.ConditionNode.CompareNode.of(
                new com.auraboot.framework.decision.ast.Operand.PathOperand(com.auraboot.framework.decision.ast.Scope.RECORD,"data.warehouse",com.auraboot.framework.decision.ast.DataType.STRING),
                com.auraboot.framework.decision.ast.Operator.EQ,
                new com.auraboot.framework.decision.ast.Operand.LiteralOperand("Shanghai",com.auraboot.framework.decision.ast.DataType.STRING)));
        administrator=TenantAdministrationFixture.grant(jdbc,tenant(),getTestTenantMember().getId());
        grantCommittedPermissionToTestRole(MetaPermission.ROLE_MANAGE,"API","role","update","Role copy fixture");
        grantCommittedPermissionToTestRole(MetaPermission.PERMISSION_MANAGE,"API","permission","update","Policy copy fixture");
        resource="copy_"+UniqueIdGenerator.generate().toLowerCase(Locale.ROOT);
        source=role("source");target=role("target");sourcePid=pid(source);targetPid=pid(target);
        confirm=permission("confirm");remove=permission("remove");
        jdbc.update("""
                INSERT INTO ab_role_permission
                  (pid,tenant_id,role_id,permission_id,grant_type,priority,effective_date,expiry_date,
                   conditions,condition_ast,data_scope_ast,field_scope,source_ref,snapshot_id,status,deleted_flag)
                VALUES (?,?,?,?,'grant',7,CURRENT_DATE-2,CURRENT_DATE+5,
                   '{"legacy":"preserved"}'::jsonb,?::jsonb,?::jsonb,?::jsonb,'package:source',991,'active',FALSE)
                """,UniqueIdGenerator.generate(),tenant(),source,confirm,ast,ast,fieldScope());
        jdbc.update("""
                INSERT INTO ab_role_permission
                  (pid,tenant_id,role_id,permission_id,grant_type,priority,effective_date,expiry_date,status,deleted_flag)
                VALUES (?,?,?,?,'deny',3,CURRENT_DATE+1,CURRENT_DATE+3,'inactive',FALSE)
                """,UniqueIdGenerator.generate(),tenant(),source,remove);
        scope(source,"all");
        policy=UniqueIdGenerator.generate();
        jdbc.update("""
                INSERT INTO ab_data_permission_policy (pid,tenant_id,name,model_code,policy_type,field_code,mask_type,enabled)
                VALUES (?,?,?,?,'column','price','hide',TRUE)
                """,policy,tenant(),resource,resource);
        jdbc.update("INSERT INTO ab_data_permission_role_binding (tenant_id,policy_pid,role_pid) VALUES (?,?,?)",tenant(),policy,sourcePid);
    }
    @AfterEach void cleanup() {
        reset(policyBindings);
        if(administrator!=null)administrator.close();
        for(Long role:ownedRoles) {
            jdbc.update("DELETE FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=(SELECT pid FROM ab_role WHERE tenant_id=? AND id=?)",tenant(),tenant(),role);
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?",tenant(),role);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?",tenant(),role);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND role_id=?",tenant(),role);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?",tenant(),role);
        }
        if(policy!=null)jdbc.update("DELETE FROM ab_data_permission_policy WHERE tenant_id=? AND pid=?",tenant(),policy);
        if(resource!=null)jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND resource_code=?",tenant(),resource);
        ownedRoles.clear();
    }

    @Test void fullBindingsScopesAndPoliciesSurviveCopyAndRepeatedCopy() {
        jdbc.update("UPDATE ab_role_data_scope SET scope_config=?::jsonb WHERE tenant_id=? AND role_id=?",
                "{\"nested\":{\"ids\":[\"W1\",\"W2\"],\"strict\":true}}",tenant(),source);
        jdbc.update("UPDATE ab_data_permission_policy SET enabled=FALSE WHERE tenant_id=? AND pid=?",tenant(),policy);
        var original=contracts(source);
        assertThat(permissions.copyRolePermissions(source,target)).isTrue();
        assertThat(contracts(target)).isEqualTo(original);
        assertThat(scopeContract(target)).isEqualTo(scopeContract(source));
        assertThat(jdbc.queryForList("SELECT policy_pid FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=?",String.class,tenant(),targetPid)).containsExactly(policy);
        assertThat(jdbc.queryForList("SELECT source_ref FROM ab_role_permission WHERE tenant_id=? AND role_id=?",String.class,tenant(),target))
                .allMatch(ref->ref.startsWith("copy:"+sourcePid+":"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND snapshot_id IS NOT NULL",Integer.class,tenant(),target)).isZero();
        permissions.copyRolePermissions(source,target);
        assertThat(contracts(target)).isEqualTo(original);assertThat(contracts(source)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT enabled FROM ab_data_permission_policy WHERE tenant_id=? AND pid=?",Boolean.class,tenant(),policy)).isFalse();
    }
    @Test void copiedRoleKeepsScopeAndInactiveStatusWithoutCopyingMembers() {
        jdbc.update("UPDATE ab_role SET status='inactive' WHERE tenant_id=? AND id=?",tenant(),source);
        var copied=roles.copyRole(source,"Copied role",resource+"_cloned");ownedRoles.add(copied.getId());
        assertThat(copied.getDefaultDataScopeType()).isEqualTo("all");assertThat(copied.getRoleScope()).isEqualTo("tenant");
        assertThat(copied.getScopeType()).isEqualTo("TENANT");assertThat(copied.getStatus()).isEqualTo("inactive");
        assertThat(copied.getIsDefault()).isFalse();assertThat(copied.getIsSystem()).isFalse();
        assertThat(contracts(copied.getId())).isEqualTo(contracts(source));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_user_role WHERE tenant_id=? AND role_id=?",Integer.class,tenant(),copied.getId())).isZero();
    }
    @Test void roleDefaultConflictCannotBroadenAnImplicitScope() {
        jdbc.update("UPDATE ab_role SET default_data_scope_type='self' WHERE tenant_id=? AND id=?",tenant(),target);
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertEmptyTarget();
    }
    @Test void overlappingGrantWithDifferentFieldsIsAConflictRatherThanAnOverwrite() {
        seedTargetGrant();
        jdbc.update("UPDATE ab_role_permission SET field_scope=NULL WHERE tenant_id=? AND role_id=?",tenant(),target);
        var before=contracts(target);
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(contracts(target)).isEqualTo(before);
    }
    @Test void differentExistingRecordScopeIsNotSilentlyReplaced() {
        scope(target,"none");
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(contracts(target)).isEmpty();assertThat(scopeContract(target).getFirst()).containsEntry("scope_type","none");
    }
    @Test void rolePolicyMismatchCannotChangeAnExistingTargetsOtherGrants() {
        seedTargetGrant();scope(target,"all");var before=contracts(target);
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(contracts(target)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=?",Integer.class,tenant(),targetPid)).isZero();
    }
    @Test void latePolicyFailureRollsBackActualGrantAndScopeWrites() {
        doAnswer(call->{
            assertThat(contracts(target)).hasSize(2);assertThat(scopeContract(target)).hasSize(1);
            throw new IllegalStateException("Injected policy copy failure after actual writes");
        }).when(policyBindings).insertBinding(eq(tenant()),eq(policy),eq(targetPid));
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(IllegalStateException.class);
        assertEmptyTarget();assertThat(contracts(source)).hasSize(2);
    }
    @Test void lateCopyFailureAlsoRollsBackTheNewRoleRow() {
        String code=resource+"_failed";
        doAnswer(call->{
            String pid=call.getArgument(2);Long role=jdbc.queryForObject("SELECT id FROM ab_role WHERE tenant_id=? AND pid=?",Long.class,tenant(),pid);
            assertThat(contracts(role)).hasSize(2);throw new IllegalStateException("Injected new role copy failure");
        }).when(policyBindings).insertBinding(eq(tenant()),eq(policy),argThat(pid->!sourcePid.equals(pid)));
        assertThatThrownBy(()->roles.copyRole(source,"Failed copy",code)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role WHERE tenant_id=? AND code=?",Integer.class,tenant(),code)).isZero();
        assertThat(contracts(source)).hasSize(2);
    }
    @Test void platformIdentityCannotBeCopiedOrUsedAsACopyTarget() {
        jdbc.update("UPDATE ab_role SET role_scope='platform' WHERE tenant_id=? AND id=?",tenant(),source);
        assertThatThrownBy(()->roles.copyRole(source,"Invalid platform copy",resource+"_invalid")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("UPDATE ab_role SET role_scope='tenant' WHERE tenant_id=? AND id=?",tenant(),source);
        jdbc.update("UPDATE ab_role SET code='platform_admin' WHERE tenant_id=? AND id=?",tenant(),target);
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(AccessDeniedException.class);
        assertEmptyTarget();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role WHERE tenant_id=? AND code=?",Integer.class,tenant(),resource+"_invalid")).isZero();
    }
    @Test void currentAdministratorRevocationRejectsCopyBeforeWriting() {
        administrator.close();
        assertThatThrownBy(()->permissions.copyRolePermissions(source,target)).isInstanceOf(AccessDeniedException.class);
        assertEmptyTarget();
    }
    @Test void runtimeUsesTheCopiedConditionScopeAndFiniteFieldSet() {
        permissions.copyRolePermissions(source,target);administrator.close();
        jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)",
                IdWorker.getId(),UniqueIdGenerator.generate(),tenant(),getTestTenantMember().getId(),target);
        var rows=grants.findBusinessGrants(tenant(),getTestUser().getId(),getTestTenantMember().getId(),resource+".confirm");
        assertThat(rows).extracting(RolePermissionMapper.BusinessGrantRow::getRoleId).containsExactly(target);
        var decision=authorization.resolve(getTestTenantMember().getId(),List.of(resource+".confirm"),resource,record("Shanghai"));
        assertThat(decision.result().granted()).isTrue();assertThat(decision.canRead("price")).isTrue();
        assertThat(decision.canRead("secret")).isFalse();assertThat(decision.canWrite("quantity")).isTrue();assertThat(decision.canWrite("price")).isFalse();
        assertThat(authorization.resolve(getTestTenantMember().getId(),List.of(resource+".confirm"),resource,record("Beijing")).result().granted()).isFalse();
        jdbc.update("UPDATE ab_role_data_scope SET scope_type='none' WHERE tenant_id=? AND role_id=?",tenant(),target);
        assertThat(authorization.resolve(getTestTenantMember().getId(),List.of(resource+".confirm"),resource,record("Shanghai")).result().granted()).isFalse();
        jdbc.update("UPDATE ab_role_data_scope SET scope_type='all' WHERE tenant_id=? AND role_id=?",tenant(),target);
        jdbc.update("UPDATE ab_role_permission SET expiry_date=CURRENT_DATE-1 WHERE tenant_id=? AND role_id=? AND permission_id=?",tenant(),target,confirm);
        assertThat(authorization.resolve(getTestTenantMember().getId(),List.of(resource+".confirm"),resource,record("Shanghai")).result().granted()).isFalse();
    }
    private Map<String,Object> record(String warehouse){return Map.of("pid","copy-root","tenant_id",tenant(),"warehouse",warehouse);}
    private String fieldScope(){return "{\"modelCode\":\""+resource+"\",\"readFields\":[\"price\"],\"writeFields\":[\"quantity\"]}";}
    private List<Map<String,Object>> contracts(long role){return jdbc.queryForList("""
            SELECT permission_id,grant_type,priority,effective_date,expiry_date,conditions::text,condition_ast::text,
                   data_scope_ast::text,field_scope::text,status,deleted_flag FROM ab_role_permission
            WHERE tenant_id=? AND role_id=? ORDER BY permission_id
            """,tenant(),role);}
    private List<Map<String,Object>> scopeContract(long role){return jdbc.queryForList("SELECT resource_code,action_code,scope_type,merge_strategy,scope_config::text FROM ab_role_data_scope WHERE tenant_id=? AND role_id=? ORDER BY resource_code,action_code",tenant(),role);}
    private void assertEmptyTarget(){assertThat(contracts(target)).isEmpty();assertThat(scopeContract(target)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_data_permission_role_binding WHERE tenant_id=? AND role_pid=?",Integer.class,tenant(),targetPid)).isZero();}
    private void seedTargetGrant(){jdbc.update("""
            INSERT INTO ab_role_permission (pid,tenant_id,role_id,permission_id,grant_type,priority,effective_date,expiry_date,conditions,condition_ast,data_scope_ast,field_scope,status,deleted_flag)
            SELECT ?,tenant_id,?,permission_id,grant_type,priority,effective_date,expiry_date,conditions,condition_ast,data_scope_ast,field_scope,status,deleted_flag
            FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=?
            """,UniqueIdGenerator.generate(),target,tenant(),source,confirm);}
    private void scope(long role,String type){jdbc.update("""
            INSERT INTO ab_role_data_scope (pid,tenant_id,role_id,resource_code,action_code,scope_type,merge_strategy,scope_config,created_at,updated_at)
            VALUES (?,?,?,?,?,?,'MIN','{}'::jsonb,now(),now())
            """,UniqueIdGenerator.generate(),tenant(),role,resource,"confirm",type);}
    private long permission(String action){return jdbc.queryForObject("""
            INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,action,source,status,deleted_flag)
            VALUES (?,?,?,?,'operation',?,?,'test','active',FALSE) RETURNING id
            """,Long.class,UniqueIdGenerator.generate(),tenant(),resource+"."+action,resource,resource,action);}
    private long role(String suffix){long id=IdWorker.getId();ownedRoles.add(id);jdbc.update("""
            INSERT INTO ab_role (id,pid,tenant_id,code,name,type,role_scope,scope_type,scope_content,default_data_scope_type,status,is_default,is_system,deleted_flag,created_at,updated_at)
            VALUES (?,?,?,?,?,'custom','tenant','TENANT','{}'::jsonb,'all','active',FALSE,FALSE,FALSE,now(),now())
            """,id,UniqueIdGenerator.generate(),tenant(),resource+"_"+suffix,suffix);return id;}
    private String pid(long role){return jdbc.queryForObject("SELECT pid FROM ab_role WHERE tenant_id=? AND id=?",String.class,tenant(),role);}
    private Long tenant(){return getTestTenant().getId();}
}
