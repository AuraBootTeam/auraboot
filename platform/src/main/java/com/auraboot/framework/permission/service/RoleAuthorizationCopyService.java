package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.mapper.RoleDataScopeMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Copy complete constraints; a permission ID alone is never a copy of an authorization. */
@Service
@RequiredArgsConstructor
public class RoleAuthorizationCopyService {
    private final RoleAuthorizationWriteLock writes;
    private final RoleAuthorizationAuthoringAccess authoring;
    private final RoleMapper roles;
    private final RolePermissionMapper grants;
    private final PermissionMapper permissions;
    private final RoleDataScopeMapper scopes;
    private final DataPermissionRoleBindingMapper bindings;
    private final DataPermissionPolicyMapper policies;
    private final UserPermissionService users;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    @Transactional
    public boolean copy(Long sourceId, Long targetId) {
        writes.mutate();
        authoring.requireWrite();
        Role source=owned(sourceId), target=owned(targetId);
        if(sourceId.equals(targetId))return true;
        List<RolePermission> sourceGrants=grants.findByRoleId(sourceId,source.getTenantId());
        List<RolePermission> targetGrants=grants.findByRoleId(targetId,target.getTenantId());
        if(sourceGrants.isEmpty() && !targetGrants.isEmpty())return true;
        if(!Objects.equals(source.getRoleScope(),target.getRoleScope())
                || !Objects.equals(source.getScopeType(),target.getScopeType())
                || !Objects.equals(content(source.getScopeContent()),content(target.getScopeContent()))
                || !Objects.equals(source.getDefaultDataScopeType(),target.getDefaultDataScopeType()))throw conflict();

        Map<Long,RolePermission> existing=byPermission(targetGrants);
        byPermission(sourceGrants);
        List<RolePermission> pending=new ArrayList<>();
        for(var grant:sourceGrants) {
            if(!Objects.equals(grant.getTenantId(),source.getTenantId()) || !Objects.equals(grant.getRoleId(),sourceId))throw denied();
            var prior=existing.get(grant.getPermissionId());
            if(prior==null)pending.add(grant);
            else if(!sameGrant(prior,grant))throw conflict();
        }
        for(var grant:targetGrants)
            if(!Objects.equals(grant.getTenantId(),target.getTenantId()) || !Objects.equals(grant.getRoleId(),targetId))throw denied();
        List<Long> permissionIds=java.util.stream.Stream.concat(sourceGrants.stream(),targetGrants.stream())
                .map(RolePermission::getPermissionId).distinct().toList();
        var definitions=permissionIds.isEmpty()?List.<com.auraboot.framework.permission.entity.Permission>of():permissions.findByIds(permissionIds);
        if(!definitions.stream().map(com.auraboot.framework.permission.entity.Permission::getId).collect(java.util.stream.Collectors.toSet())
                .equals(new HashSet<>(permissionIds)))throw denied();
        Map<Long,Key> keys=new HashMap<>();
        for(var permission:definitions) {
            if(permission.getTenantId()!=null && !Objects.equals(permission.getTenantId(),source.getTenantId()))throw denied();
            if(permission.getResourceCode()!=null && permission.getAction()!=null)
                keys.put(permission.getId(),new Key(permission.getResourceCode(),permission.getAction()));
        }
        var sourceScopes=scopeMap(source);
        var targetScopes=scopeMap(target);
        Set<Key> sourceKeys=new HashSet<>(), targetKeys=new HashSet<>();
        sourceGrants.forEach(grant->{if(keys.containsKey(grant.getPermissionId()))sourceKeys.add(keys.get(grant.getPermissionId()));});
        targetGrants.forEach(grant->{if(keys.containsKey(grant.getPermissionId()))targetKeys.add(keys.get(grant.getPermissionId()));});
        for(Key key:sourceKeys) {
            if(targetScopes.containsKey(key) || targetKeys.contains(key))
                if(!sameScope(sourceScopes.get(key),targetScopes.get(key),source.getDefaultDataScopeType()))throw conflict();
        }
        List<RoleDataScope> pendingScopes=new ArrayList<>();
        for(var entry:sourceScopes.entrySet()) {
            if(!targetGrants.isEmpty() && !sourceKeys.contains(entry.getKey()))continue;
            var prior=targetScopes.get(entry.getKey());
            if(prior!=null) {
                if(!sameScope(entry.getValue(),prior,source.getDefaultDataScopeType()))throw conflict();
            } else {
                if(targetKeys.contains(entry.getKey()) && !sameScope(entry.getValue(),null,source.getDefaultDataScopeType()))throw conflict();
                pendingScopes.add(entry.getValue());
            }
        }
        Set<String> sourcePolicies=policyIds(source), targetPolicies=policyIds(target);
        if((!targetGrants.isEmpty() || !targetPolicies.isEmpty()) && !sourcePolicies.equals(targetPolicies))throw conflict();
        Set<String> pendingPolicies=new HashSet<>(sourcePolicies);pendingPolicies.removeAll(targetPolicies);
        if(!pendingPolicies.isEmpty() && !users.hasPermission(MetaContext.getCurrentUserId(),MetaPermission.PERMISSION_MANAGE))throw denied();
        for(String pid:sourcePolicies) {
            var policy=policies.findByPid(source.getTenantId(),pid);
            if(policy==null || !Objects.equals(policy.getTenantId(),source.getTenantId()))throw denied();
        }
        // All comparisons precede writes. Explicit SQL copies every JSONB constraint verbatim.
        for(var grant:pending) {
            int inserted=jdbc.update("""
                    INSERT INTO ab_role_permission
                        (pid,tenant_id,role_id,permission_id,grant_type,priority,effective_date,expiry_date,
                         conditions,condition_ast,data_scope_ast,field_scope,source_ref,snapshot_id,
                         status,deleted_flag,created_at,updated_at,created_by,updated_by)
                    SELECT ?,tenant_id,?,permission_id,grant_type,priority,effective_date,expiry_date,
                           conditions,condition_ast,data_scope_ast,field_scope,?,NULL,
                           status,FALSE,now(),now(),?,?
                    FROM ab_role_permission WHERE id=? AND tenant_id=? AND role_id=? AND deleted_flag=FALSE
                    """,UniqueIdGenerator.generate(),targetId,"copy:"+source.getPid()+":"+grant.getPid(),
                    MetaContext.getCurrentUserId(),MetaContext.getCurrentUserId(),grant.getId(),source.getTenantId(),sourceId);
            if(inserted!=1)throw conflict();
        }
        for(var scope:pendingScopes) {
            if(jdbc.update("""
                    INSERT INTO ab_role_data_scope
                        (pid,tenant_id,role_id,resource_code,action_code,scope_type,merge_strategy,scope_config,created_at,updated_at)
                    SELECT ?,tenant_id,?,resource_code,action_code,scope_type,merge_strategy,scope_config,now(),now()
                    FROM ab_role_data_scope WHERE id=? AND tenant_id=? AND role_id=?
                    """,UniqueIdGenerator.generate(),targetId,scope.getId(),source.getTenantId(),sourceId)!=1)throw conflict();
        }
        for(String pid:pendingPolicies)
            if(bindings.insertBinding(source.getTenantId(),pid,target.getPid())!=1)throw conflict();
        events.publishEvent(new RolePermissionChangedEvent(this,source.getTenantId(),targetId,null,"UPDATE"));
        return true;
    }

    private Role owned(Long id) {
        Role role=id==null?null:roles.selectById(id);
        if(role==null || Boolean.TRUE.equals(role.getDeletedFlag()) || !Objects.equals(role.getTenantId(),MetaContext.getCurrentTenantId())
                || com.auraboot.framework.rbac.constant.RoleConstants.isPlatformOnly(role.getCode())
                || "platform".equalsIgnoreCase(role.getRoleScope()))throw denied();
        return role;
    }
    private Map<Long,RolePermission> byPermission(List<RolePermission> rows) {
        Map<Long,RolePermission> result=new LinkedHashMap<>();
        for(var row:rows)if(row.getPermissionId()==null || result.putIfAbsent(row.getPermissionId(),row)!=null)throw conflict();
        return result;
    }
    private Map<Key,RoleDataScope> scopeMap(Role role) {
        Map<Key,RoleDataScope> result=new LinkedHashMap<>();
        for(var scope:scopes.findByTenantAndRole(role.getTenantId(),role.getId())) {
            if(!Objects.equals(scope.getTenantId(),role.getTenantId()) || !Objects.equals(scope.getRoleId(),role.getId()))throw denied();
            if(result.putIfAbsent(new Key(scope.getResourceCode(),scope.getActionCode()),scope)!=null)throw conflict();
        }
        return result;
    }
    private Set<String> policyIds(Role role) {
        Set<String> result=new HashSet<>();
        for(var binding:bindings.findByRolePid(role.getTenantId(),role.getPid())) {
            if(!Objects.equals(binding.getTenantId(),role.getTenantId()) || !Objects.equals(binding.getRolePid(),role.getPid()) || binding.getPolicyPid()==null)throw denied();
            result.add(binding.getPolicyPid());
        }
        return result;
    }
    private boolean sameGrant(RolePermission a,RolePermission b) {
        return Objects.equals(a.getGrantType(),b.getGrantType()) && Objects.equals(a.getPriority(),b.getPriority())
                && Objects.equals(a.getEffectiveDate(),b.getEffectiveDate()) && Objects.equals(a.getExpiryDate(),b.getExpiryDate())
                && Objects.equals(a.getStatus(),b.getStatus()) && Objects.equals(tree(a.getConditions()),tree(b.getConditions()))
                && Objects.equals(tree(a.getConditionAst()),tree(b.getConditionAst())) && Objects.equals(tree(a.getDataScopeAst()),tree(b.getDataScopeAst()))
                && Objects.equals(tree(a.getFieldScope()),tree(b.getFieldScope()));
    }
    private boolean sameScope(RoleDataScope a,RoleDataScope b,String fallback) {
        return Objects.equals(a==null?fallback:a.getScopeType(),b==null?fallback:b.getScopeType())
                && Objects.equals(a==null?"MAX":a.getMergeStrategy(),b==null?"MAX":b.getMergeStrategy())
                && Objects.equals(tree(a==null?null:a.getScopeConfig()),tree(b==null?null:b.getScopeConfig()));
    }
    private Object tree(Object value) {return value==null?null:json.valueToTree(value);}
    private Object content(String value) {
        if(value==null)return null;
        try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw conflict();}
    }
    private record Key(String resource,String action) { }
    private static AccessDeniedException denied(){return new AccessDeniedException("Role authorization copy is unavailable in this tenant");}
    private static BusinessException conflict(){return new BusinessException(ResponseCode.CasVersionConflict,"$i18n:role.authorization_copy_conflict");}
}
