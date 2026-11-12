package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.enums.DataScopeType;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.permission.mapper.RoleDataScopeMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves effective data scope for a member by merging across all assigned roles.
 *
 * <p>Merge logic:
 * <ul>
 *   <li>Each scope type has a priority: NONE(1) &lt; SELF(2) &lt; DEPT(3) &lt; TEAM(4) &lt; DEPT_AND_SUB(5) &lt; ALL(6)</li>
 *   <li>Default merge strategy is MAX (most permissive wins)</li>
 *   <li>If any role sets merge_strategy='MIN', the least permissive scope is used</li>
 * </ul>
 *
 * <p>Note: OrganizationService is @Lazy-injected to break circular dependency:
 * DynamicDataService → DataPermissionEngine → DataScopeEvaluator → DataScopeService → OrganizationService → DynamicDataService
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("java/log-injection")
public class DataScopeServiceImpl implements DataScopeService {
    private final com.auraboot.framework.permission.service.RoleAuthorizationWriteLock authorizationWriteLock;


    private final RoleDataScopeMapper roleDataScopeMapper;
    private final com.auraboot.framework.rbac.mapper.RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final DataScopeCacheEvictor dataScopeCacheEvictor;

    @Autowired @Lazy
    private OrganizationService organizationService;

    @Autowired @Lazy
    private MetaModelService metaModelService;

    @Autowired @Lazy
    private com.auraboot.framework.organization.service.TeamMemberService teamMemberService;

    @Autowired @Lazy
    private PermissionSnapshotCache snapshots;

    @Autowired @Lazy
    private com.auraboot.framework.permission.service.DomainCommandAuthorization domains;

    @Override
    @Cacheable(value = "dataScopeCondition",
            cacheManager = "permissionCacheManager",
            key = "T(com.auraboot.framework.meta.cache.DataAccessCacheEpoch).current() + ':' + #memberId + ':' + #resourceCode + ':' + #actionCode")
    public DataScopeCondition resolveScope(Long memberId, String resourceCode, String actionCode) {
        return resolveScopeInternal(memberId, resourceCode, actionCode, false);
    }

    @Override
    public DataScopeCondition resolveScopeForRole(Long memberId, Long roleId, String permissionResource,
                                                  String action, String recordModel) {
        return resolveGrantScope(memberId,roleId,permissionResource,action,recordModel,false);
    }

    @Override
    public DataScopeCondition resolveObjectBoundScopeForRole(Long memberId, Long roleId, String permissionResource,
                                                             String action, String recordModel) {
        return resolveGrantScope(memberId, roleId, permissionResource, action, recordModel, true, true);
    }

    @Override
    public DataScopeCondition resolveOrdinaryReadScopeForRole(Long memberId, Long roleId, String recordModel) {
        return resolveGrantScope(memberId,roleId,recordModel,"read",recordModel,true);
    }

    @Override
    public DataScopeCondition resolveOrdinaryUpdateScopeForRole(Long memberId, Long roleId, String recordModel) {
        return resolveGrantScope(memberId,roleId,recordModel,"update",recordModel,true);
    }

    @Override
    public DataScopeCondition resolveOrdinaryCreateScopeForRole(Long memberId, Long roleId, String recordModel) {
        return resolveGrantScope(memberId,roleId,recordModel,"create",recordModel,true);
    }

    private DataScopeCondition resolveGrantScope(Long memberId, Long roleId, String permissionResource,
                                                String action, String recordModel, boolean ordinaryRead) {
        return resolveGrantScope(memberId, roleId, permissionResource, action, recordModel, ordinaryRead, false);
    }

    private DataScopeCondition resolveGrantScope(Long memberId, Long roleId, String permissionResource,
                                                String action, String recordModel, boolean ordinaryRead,
                                                boolean inheritObjectScope) {
        if (!MetaContext.exists() || memberId == null || roleId == null
                || !java.util.Objects.equals(memberId, MetaContext.getCurrentMemberId())
                || MetaContext.getCurrentTenantId() == null || MetaContext.getCurrentUserId() == null
                || permissionResource == null || permissionResource.isBlank()
                || action == null || action.isBlank() || recordModel == null || recordModel.isBlank()) {
            return DataScopeCondition.none();
        }
        Long tenant = MetaContext.getCurrentTenantId();
        var role = roleMapper.findEffectiveGrantOwner(tenant, MetaContext.getCurrentUserId(), memberId, roleId);
        if (role == null) return DataScopeCondition.none();
        var roleScopes = roleDataScopeMapper.findByTenantAndRole(tenant, roleId);
        var entries = roleScopes.stream()
                .filter(entry -> permissionResource.equals(entry.getResourceCode()) && action.equals(entry.getActionCode()))
                .toList();
        // An explicit action restriction always wins, including NONE or malformed metadata.
        // The fallback is local to the exact grant owner; another role cannot lend its scope.
        if (entries.isEmpty() && inheritObjectScope) {
            entries = roleScopes.stream().filter(entry -> recordModel.equals(entry.getResourceCode())
                    && "read".equals(entry.getActionCode())).toList();
        }
        // Never silently discard a custom restriction that this scope resolver cannot interpret.
        if (entries.size() > 1 || entries.stream().anyMatch(entry -> entry.getScopeConfig() != null
                && !(entry.getScopeConfig() instanceof Map<?, ?> config && config.isEmpty()))) {
            return DataScopeCondition.none();
        }
        String type = entries.isEmpty() ? role.getDefaultDataScopeType() : entries.getFirst().getScopeType();
        if (type == null) return ordinaryRead && entries.isEmpty()
                ? DataScopeCondition.notConfigured() : DataScopeCondition.none();
        try {
            return resolveCondition(memberId, DataScopeType.fromCode(type), recordModel, true);
        } catch (IllegalArgumentException invalid) {
            // CATCH: corrupt authoring metadata cannot manufacture an unrestricted business grant.
            return DataScopeCondition.none();
        }
    }

    @Override
    public DataScopeCondition resolveHistoricalScope(Long memberId, String resourceCode, String actionCode) {
        if (memberId == null) return DataScopeCondition.none();
        return resolveScopeInternal(memberId, resourceCode, actionCode, true);
    }

    @Override
    public DataScopeCondition resolveAudienceScope(Long memberId, String resourceCode, String actionCode) {
        if (memberId == null) return DataScopeCondition.none();
        List<Long> roles = userRoleMapper.findEffectiveRoleIdsByMemberId(MetaContext.getCurrentTenantId(),memberId);
        if (roles == null || roles.isEmpty()) return DataScopeCondition.notConfigured();
        List<RoleDataScope> entries = roleDataScopeMapper.findByRoleIdsAndResource(roles, resourceCode, actionCode);
        if (entries == null || entries.isEmpty()) return DataScopeCondition.notConfigured();
        DataScopeType type = mergeScopes(entries);
        return type == DataScopeType.TEAM
                ? buildTeamCondition(memberId, resourceCode, true, false)
                : resolveCondition(memberId, type, resourceCode, true);
    }

    private DataScopeCondition resolveScopeInternal(Long memberId, String resourceCode, String actionCode,
                                                    boolean strict) {
        // 1. Get member's role IDs
        List<Long> roleIds = userRoleMapper.findRoleIdsByMemberId(memberId);
        if (roleIds == null || roleIds.isEmpty()) {
            // No roles assigned — data scope not configured for this member
            return DataScopeCondition.notConfigured();
        }

        // 2. Query matching data scope entries
        List<RoleDataScope> scopes = roleDataScopeMapper.findByRoleIdsAndResource(roleIds, resourceCode, actionCode);
        if (scopes == null || scopes.isEmpty()) {
            // Physical-row checks already use the owning business grant's role default.
            // Query the same audience instead of selecting rows those checks must reject.
            DataScopeCondition domainDefault = strict ? null : domainDefaultAudience(memberId, resourceCode, actionCode);
            return domainDefault != null ? domainDefault : DataScopeCondition.notConfigured();
        }

        // 3. Merge across roles
        DataScopeType merged = mergeScopes(scopes);

        // 4. Resolve concrete condition (pass resourceCode for model-specific field config)
        return resolveCondition(memberId, merged, resourceCode, strict);
    }

    /** Preserve explicit scopes and legacy absence; defaults belong only to effective read-grant owners. */
    private DataScopeCondition domainDefaultAudience(Long member, String model, String action) {
        if (!"read".equals(action) || snapshots == null || domains == null || !MetaContext.exists()
                || MetaContext.getCurrentMemberId() == null || !member.equals(MetaContext.getCurrentMemberId())) return null;
        var defaults = new java.util.ArrayList<RoleDataScope>();
        var visited = new java.util.HashSet<Long>();
        boolean domain = false;
        for (String code : snapshots.resourcePermissionCodes(MetaContext.getCurrentTenantId(), model, action)) {
            var definition = snapshots.resolvePermissionDefinition(MetaContext.getCurrentTenantId(), code);
            // Command-local technical effects must not populate a shared query-scope configuration.
            if (!com.auraboot.framework.permission.service.DomainPermissionPolicy.models(definition).containsKey(model)) continue;
            domain = true;
            var bindings = domains.grants(code);
            if (bindings == null || bindings.stream().anyMatch(binding -> !"grant".equals(binding.getGrantType()))) continue;
            for (var binding : bindings) {
                if (binding.getRoleId() == null || !visited.add(binding.getRoleId())) continue;
                var condition = resolveObjectBoundScopeForRole(member, binding.getRoleId(), model, action, model);
                // An absent legacy scope remains absent; never persist or relabel it as ALL.
                if (condition != null && "not_configured".equals(condition.scopeType())) return null;
                var entry = new RoleDataScope();
                entry.setScopeType(condition == null ? "none" : condition.scopeType());
                entry.setMergeStrategy("MAX");
                defaults.add(entry);
            }
        }
        if (!domain) return null;
        return defaults.isEmpty() ? DataScopeCondition.none() : resolveCondition(member, mergeScopes(defaults), model, true);
    }

    @Override
    public boolean isCreatorInDepartments(Long userId, List<String> departmentPids) {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null || userId == null || departmentPids == null || departmentPids.isEmpty()) return false;
        TenantMember member = tenantMemberMapper.findByTenantIdAndUserId(tenantId, userId);
        if (member == null || member.getPid() == null) return false;
        Map<String, Object> employee = organizationService.getEmployeeByMemberPid(member.getPid());
        return employee != null && employee.get("org_emp_dept_id") != null
                && departmentPids.contains(String.valueOf(employee.get("org_emp_dept_id")));
    }

    @Override
    public boolean isOwnerInDepartments(String ownerUserPid, List<String> departmentPids) {
        if (ownerUserPid == null || ownerUserPid.isBlank()
                || departmentPids == null || departmentPids.isEmpty()) {
            return false;
        }
        Map<String, Object> employee = organizationService.getEmployeeByUserPid(ownerUserPid);
        if (employee == null || employee.get("org_emp_dept_id") == null) {
            return false;
        }
        return departmentPids.contains(String.valueOf(employee.get("org_emp_dept_id")));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void setScope(Long tenantId, Long roleId, String resourceCode, String actionCode,
                         String scopeType, String mergeStrategy) {
        authorizationWriteLock.mutate();
        try {
            DataScopeType.fromCode(scopeType);
        } catch (IllegalArgumentException e) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Invalid data scope type", e);
        }
        if (mergeStrategy != null && !"MAX".equals(mergeStrategy) && !"MIN".equals(mergeStrategy)) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Invalid data scope merge strategy");
        }
        requireWriteTarget(tenantId,roleId,resourceCode,actionCode);
        LambdaQueryWrapper<RoleDataScope> query = new LambdaQueryWrapper<RoleDataScope>()
                .eq(RoleDataScope::getTenantId, tenantId)
                .eq(RoleDataScope::getRoleId, roleId)
                .eq(RoleDataScope::getResourceCode, resourceCode)
                .eq(RoleDataScope::getActionCode, actionCode);

        RoleDataScope existing = roleDataScopeMapper.selectOne(query);
        if (existing != null) {
            existing.setScopeType(scopeType);
            existing.setMergeStrategy(mergeStrategy != null ? mergeStrategy : "MAX");
            existing.setUpdatedAt(Instant.now());
            roleDataScopeMapper.updateById(existing);
        } else {
            RoleDataScope scope = new RoleDataScope();
            scope.setPid(UniqueIdGenerator.generate());
            scope.setTenantId(tenantId);
            scope.setRoleId(roleId);
            scope.setResourceCode(resourceCode);
            scope.setActionCode(actionCode);
            scope.setScopeType(scopeType);
            scope.setMergeStrategy(mergeStrategy != null ? mergeStrategy : "MAX");
            scope.setCreatedAt(Instant.now());
            scope.setUpdatedAt(Instant.now());
            roleDataScopeMapper.insert(scope);
        }
        // The user-permission snapshot and member-keyed dataScopeCondition caches read row
        // filters through this table; an import or admin change that skips eviction keeps
        // stale row filters alive until restart (the 2026-11-05 bootnap finding). Evict the
        // member-keyed region only after the surrounding transaction commits — a pre-commit
        // evict lets a concurrent request re-cache the pre-change filters for a full TTL —
        // and publish through the standard role-changed channel so snapshot consumers evict
        // after commit as well.
        dataScopeCacheEvictor.evictAfterCommit();
        eventPublisher.publishEvent(new RolePermissionChangedEvent(
                this, tenantId, roleId, null, "UPDATE"));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void removeScope(Long tenantId, Long roleId, String resourceCode, String actionCode) {
        authorizationWriteLock.mutate();
        requireWriteTarget(tenantId,roleId,resourceCode,actionCode);
        LambdaQueryWrapper<RoleDataScope> query = new LambdaQueryWrapper<RoleDataScope>()
                .eq(RoleDataScope::getTenantId, tenantId)
                .eq(RoleDataScope::getRoleId, roleId)
                .eq(RoleDataScope::getResourceCode, resourceCode)
                .eq(RoleDataScope::getActionCode, actionCode);
        roleDataScopeMapper.delete(query);
        dataScopeCacheEvictor.evictAfterCommit();
        eventPublisher.publishEvent(new RolePermissionChangedEvent(
                this, tenantId, roleId, null, "DELETE"));
    }

    @Override
    public List<RoleDataScope> getScopesByRole(Long tenantId, Long roleId) {
        return roleDataScopeMapper.findByTenantAndRole(tenantId, roleId);
    }

    /** Structural boundary shared by administrative and code-owned metadata derivation. */
    private void requireWriteTarget(Long tenant,Long roleId,String resource,String action) {
        if(!MetaContext.exists() || tenant==null || !java.util.Objects.equals(tenant,MetaContext.getCurrentTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Scope writer requires its current tenant");
        var role=roleId==null?null:roleMapper.selectById(roleId);
        boolean systemTemplate=role!=null && role.getTenantId()==null
                && com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID.equals(tenant);
        if(role==null || Boolean.TRUE.equals(role.getDeletedFlag())
                || !systemTemplate && !java.util.Objects.equals(tenant,role.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Scope role is unavailable in this tenant");
        if(resource==null || resource.isBlank() || action==null || action.isBlank())
            throw new RootUnCheckedException(ResponseCode.BadParam,"Scope writer requires a resource and action");
    }

    // ========================================================================
    // Private helpers
    // ========================================================================

    /**
     * Merge multiple scope entries into a single effective scope type.
     */
    private DataScopeType mergeScopes(List<RoleDataScope> scopes) {
        boolean hasMin = scopes.stream()
                .anyMatch(s -> "MIN".equalsIgnoreCase(s.getMergeStrategy()));

        DataScopeType result = DataScopeType.fromCode(scopes.get(0).getScopeType());
        for (int i = 1; i < scopes.size(); i++) {
            DataScopeType current = DataScopeType.fromCode(scopes.get(i).getScopeType());
            if (hasMin) {
                // MIN strategy: take least permissive (lowest priority)
                if (current.priority() < result.priority()) {
                    result = current;
                }
            } else {
                // MAX strategy (default): take most permissive (highest priority)
                if (current.priority() > result.priority()) {
                    result = current;
                }
            }
        }
        return result;
    }

    /**
     * Convert a merged scope type into a concrete DataScopeCondition.
     */
    private DataScopeCondition resolveCondition(Long memberId, DataScopeType scopeType, String resourceCode, boolean strict) {
        switch (scopeType) {
            case ALL:
                return DataScopeCondition.all();
            case NONE:
                return DataScopeCondition.none();
            case SELF:
                return buildSelfCondition(resourceCode);
            case DEPT:
                return buildDeptCondition(memberId, false, resourceCode, strict);
            case TEAM:
                return buildTeamCondition(memberId, resourceCode, strict);
            case DEPT_AND_SUB:
                return buildDeptCondition(memberId, true, resourceCode, strict);
            default:
                return DataScopeCondition.all();
        }
    }

    /**
     * SELF scope: filter by ownerField = current user.
     * Reads ownerField from model extension.dataScope config; defaults to "created_by".
     * Note: created_by stores userId, not memberId.
     */
    private DataScopeCondition buildSelfCondition(String resourceCode) {
        String ownerField = getDataScopeOwnerField(resourceCode);
        Object ownerValue = resolveOwnerValue(resourceCode, ownerField);
        if (ownerValue == null) {
            // String owner column but no userPid in context: deny rather than emit broken SQL
            log.warn("SELF data scope on {}.{} has no usable owner value; denying", resourceCode, ownerField);
            return DataScopeCondition.none();
        }
        return new DataScopeCondition(
                DataScopeType.SELF.code(),
                ownerField,
                ownerValue,
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * Choose the SELF comparison value by the owner COLUMN type: numeric columns store the
     * numeric userId; string columns (reference/ULID owner fields like crm_acc_owner) store
     * the user pid. Undeclared fields (e.g. the created_by system column) have no meta field
     * definition — fall back to the numeric userId, which is what created_by stores.
     * Comparing the numeric userId against a varchar/ULID owner column is a SQL type error
     * ("operator does not exist: character varying = bigint").
     */
    private Object resolveOwnerValue(String resourceCode, String ownerField) {
        if (isStringOwnerColumn(resourceCode, ownerField)) {
            String pid = MetaContext.getCurrentUserPid();
            return pid == null || pid.isBlank() ? null : pid;
        }
        return MetaContext.getCurrentUserId();
    }

    private boolean isStringOwnerColumn(String resourceCode, String ownerField) {
        // CATCH: type lookup is best-effort — system columns (created_by) are not declared meta
        // fields and throw "Field not found"; unknown type keeps the numeric-userId behaviour.
        try {
            var mapping = metaModelService.getFieldDataType(resourceCode, ownerField);
            return mapping != null && "String".equals(mapping.getJavaType());
        } catch (Exception e) {
            log.debug("Owner column type unknown for {}.{}: {}", resourceCode, ownerField, e.getMessage());
            return false;
        }
    }

    /**
     * DEPT / DEPT_AND_SUB scope: find member's employee -> department -> dept IDs.
     * Reads ownerField and departmentField from model extension.dataScope config.
     * Falls back to SELF if member has no linked employee.
     */
    private DataScopeCondition buildDeptCondition(Long memberId, boolean includeSub, String resourceCode, boolean strict) {
        // Get member's PID to find linked employee
        TenantMember member = tenantMemberMapper.selectById(memberId);
        if (member == null || member.getPid() == null) {
            log.warn("Member {} not found, department inputs unavailable", memberId);
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }

        Map<String, Object> employee = organizationService.getEmployeeByMemberPid(member.getPid());
        if (employee == null) {
            log.warn("No employee linked to member {} (pid={}), department inputs unavailable",
                    memberId, member.getPid());
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }

        // Get department PID from employee record
        Object deptPidObj = employee.get("org_emp_dept_id");
        if (deptPidObj == null) {
            log.warn("Employee for member {} has no department, department inputs unavailable", memberId);
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }
        String deptPid = String.valueOf(deptPidObj);

        List<String> deptPids;
        if (includeSub) {
            deptPids = organizationService.getDeptAndSubPids(deptPid);
        } else {
            deptPids = List.of(deptPid);
        }

        if (deptPids.isEmpty()) {
            log.warn("No departments resolved for member {}, department inputs unavailable", memberId);
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }

        String ownerField = getDataScopeOwnerField(resourceCode);
        String deptField = getDataScopeDeptField(resourceCode);
        String deptOwnerField = getDataScopeDeptOwnerField(resourceCode);
        String scopeTypeCode = includeSub ? DataScopeType.DEPT_AND_SUB.code() : DataScopeType.DEPT.code();
        return new DataScopeCondition(
                scopeTypeCode,
                ownerField,
                resolveOwnerValue(resourceCode, ownerField),
                deptField,
                deptOwnerField,
                deptPids,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * TEAM scope (minimal approved口径): the member sees records associated with the
     * teams they belong to. TEAM is an explicit member group layered on the existing
     * DEPT system — no cross-team implicit merged views, so a record matches only when
     * its team field names one of the caller's teams. Models opt in by declaring
     * {@code extension.dataScope.teamField}; without it the scope fails closed (deny +
     * warn) rather than guessing a column. Falls back to SELF when the member belongs
     * to no team, mirroring the DEPT fallback.
     */
    private DataScopeCondition buildTeamCondition(Long memberId, String resourceCode, boolean strict) {
        return buildTeamCondition(memberId, resourceCode, strict, true);
    }

    private DataScopeCondition buildTeamCondition(Long memberId, String resourceCode, boolean strict, boolean recordScope) {
        String teamField = getDataScopeTeamField(resourceCode);
        if (recordScope && teamField == null) {
            log.warn("TEAM data scope on {} has no extension.dataScope.teamField; denying", resourceCode);
            return DataScopeCondition.none();
        }
        TenantMember member = tenantMemberMapper.selectById(memberId);
        if (member == null || member.getUserId() == null) {
            log.warn("Member {} not found, team inputs unavailable", memberId);
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }
        List<String> teamPids = teamMemberService.getTeamPidsByUserId(
                member.getUserId(), member.getTenantId());
        if (teamPids == null || teamPids.isEmpty()) {
            log.warn("Member {} belongs to no team, team inputs unavailable", memberId);
            return strict ? DataScopeCondition.none() : buildSelfCondition(resourceCode);
        }
        String ownerField = getDataScopeOwnerField(resourceCode);
        return new DataScopeCondition(
                DataScopeType.TEAM.code(),
                ownerField,
                resolveOwnerValue(resourceCode, ownerField),
                teamField,
                teamPids,
                Collections.emptyList()
        );
    }

    // ========================================================================
    // Model dataScope config helpers
    // ========================================================================

    /** Default owner field when no dataScope config is present on the model */
    private static final String DEFAULT_OWNER_FIELD = "created_by";

    /** Default department field when no dataScope config is present on the model */
    private static final String DEFAULT_DEPT_FIELD = "org_emp_dept_id";

    /**
     * Read the ownerField from model extension.dataScope config.
     * Falls back to "created_by" if not configured.
     *
     * <p>Expected model extension format:
     * <pre>
     * {
     *   "dataScope": {
     *     "ownerField": "crm_account_owner_id",
     *     "departmentField": "crm_account_dept_id"
     *   }
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private String getDataScopeOwnerField(String resourceCode) {
        Map<String, Object> dataScopeConfig = getDataScopeConfig(resourceCode);
        if (dataScopeConfig == null) {
            return DEFAULT_OWNER_FIELD;
        }
        Object ownerField = dataScopeConfig.get("ownerField");
        return ownerField != null ? ownerField.toString() : DEFAULT_OWNER_FIELD;
    }

    /**
     * Read the departmentField from model extension.dataScope config.
     * Falls back to "org_emp_dept_id" if not configured.
     */
    @SuppressWarnings("unchecked")
    private String getDataScopeDeptField(String resourceCode) {
        Map<String, Object> dataScopeConfig = getDataScopeConfig(resourceCode);
        if (dataScopeConfig == null) {
            return DEFAULT_DEPT_FIELD;
        }
        Object deptField = dataScopeConfig.get("departmentField");
        return deptField != null ? deptField.toString() : DEFAULT_DEPT_FIELD;
    }

    /**
     * Read the team field from model extension.dataScope config.
     * Returns null when unset — TEAM scope is opt-in per model (no default column).
     */
    private String getDataScopeTeamField(String resourceCode) {
        Map<String, Object> dataScopeConfig = getDataScopeConfig(resourceCode);
        if (dataScopeConfig == null) {
            return null;
        }
        Object field = dataScopeConfig.get("teamField");
        return field != null && !field.toString().isBlank() ? field.toString() : null;
    }

    /**
     * Read an owner field whose user PID should be resolved through org employee membership.
     * This takes precedence over departmentField when configured.
     */
    private String getDataScopeDeptOwnerField(String resourceCode) {
        Map<String, Object> dataScopeConfig = getDataScopeConfig(resourceCode);
        if (dataScopeConfig == null) {
            return null;
        }
        Object field = dataScopeConfig.get("departmentOwnerField");
        return field != null && !field.toString().isBlank() ? field.toString() : null;
    }

    /**
     * Get the dataScope config map from the model's extension.
     * Returns null if model not found or dataScope not configured.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getDataScopeConfig(String resourceCode) {
        // CATCH: non-transactional helper, safe to handle — model lookup may fail for non-existent codes
        try {
            var modelOpt = metaModelService.getModelDefinition(resourceCode);
            if (modelOpt.isEmpty()) {
                return null;
            }
            // Preserve the fresh current-model policy source without presentation statistics.
            var extension = metaModelService.findExtensionByCode(resourceCode);
            if (extension == null) {
                return null;
            }
            Object dataScopeObj = extension.get("dataScope");
            if (dataScopeObj instanceof Map) {
                return (Map<String, Object>) dataScopeObj;
            }
            return null;
        } catch (Exception e) {
            // codeql[java/log-injection] Resource codes are validated metadata identifiers and are logged as structured parameters only.
            log.debug("Could not load dataScope config for model {}: {}", resourceCode, e.getMessage());
            return null;
        }
    }
}
