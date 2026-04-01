package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.enums.DataScopeType;
import com.auraboot.framework.permission.mapper.RoleDataScopeMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
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
 *   <li>Each scope type has a priority: NONE(1) &lt; SELF(2) &lt; DEPT(3) &lt; DEPT_AND_SUB(4) &lt; ALL(5)</li>
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
public class DataScopeServiceImpl implements DataScopeService {

    private final RoleDataScopeMapper roleDataScopeMapper;
    private final UserRoleMapper userRoleMapper;
    private final TenantMemberMapper tenantMemberMapper;

    @Autowired @Lazy
    private OrganizationService organizationService;

    @Override
    @Cacheable(value = "dataScopeCondition",
            key = "#memberId + ':' + #resourceCode + ':' + #actionCode")
    public DataScopeCondition resolveScope(Long memberId, String resourceCode, String actionCode) {
        // 1. Get member's role IDs
        List<Long> roleIds = userRoleMapper.findRoleIdsByMemberId(memberId);
        if (roleIds == null || roleIds.isEmpty()) {
            // No roles assigned — full access (model not participating)
            return DataScopeCondition.all();
        }

        // 2. Query matching data scope entries
        List<RoleDataScope> scopes = roleDataScopeMapper.findByRoleIdsAndResource(roleIds, resourceCode, actionCode);
        if (scopes == null || scopes.isEmpty()) {
            // No data scope configured for this resource/action — full access
            return DataScopeCondition.all();
        }

        // 3. Merge across roles
        DataScopeType merged = mergeScopes(scopes);

        // 4. Resolve concrete condition
        return resolveCondition(memberId, merged);
    }

    @Override
    @CacheEvict(value = "dataScopeCondition", allEntries = true)
    public void setScope(Long tenantId, Long roleId, String resourceCode, String actionCode,
                         String scopeType, String mergeStrategy) {
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
    }

    @Override
    @CacheEvict(value = "dataScopeCondition", allEntries = true)
    public void removeScope(Long tenantId, Long roleId, String resourceCode, String actionCode) {
        LambdaQueryWrapper<RoleDataScope> query = new LambdaQueryWrapper<RoleDataScope>()
                .eq(RoleDataScope::getTenantId, tenantId)
                .eq(RoleDataScope::getRoleId, roleId)
                .eq(RoleDataScope::getResourceCode, resourceCode)
                .eq(RoleDataScope::getActionCode, actionCode);
        roleDataScopeMapper.delete(query);
    }

    @Override
    public List<RoleDataScope> getScopesByRole(Long tenantId, Long roleId) {
        return roleDataScopeMapper.findByTenantAndRole(tenantId, roleId);
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
    private DataScopeCondition resolveCondition(Long memberId, DataScopeType scopeType) {
        switch (scopeType) {
            case ALL:
                return DataScopeCondition.all();
            case NONE:
                return DataScopeCondition.none();
            case SELF:
                return buildSelfCondition();
            case DEPT:
                return buildDeptCondition(memberId, false);
            case DEPT_AND_SUB:
                return buildDeptCondition(memberId, true);
            default:
                return DataScopeCondition.all();
        }
    }

    /**
     * SELF scope: filter by created_by = current userId.
     * Note: created_by stores userId, not memberId.
     */
    private DataScopeCondition buildSelfCondition() {
        Long userId = MetaContext.getCurrentUserId();
        return new DataScopeCondition(
                DataScopeType.SELF.code(),
                "created_by",
                userId,
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    /**
     * DEPT / DEPT_AND_SUB scope: find member's employee -> department -> dept IDs.
     * Falls back to SELF if member has no linked employee.
     */
    private DataScopeCondition buildDeptCondition(Long memberId, boolean includeSub) {
        // Get member's PID to find linked employee
        TenantMember member = tenantMemberMapper.selectById(memberId);
        if (member == null || member.getPid() == null) {
            log.warn("Member {} not found, falling back to SELF scope", memberId);
            return buildSelfCondition();
        }

        Map<String, Object> employee = organizationService.getEmployeeByMemberPid(member.getPid());
        if (employee == null) {
            log.warn("No employee linked to member {} (pid={}), falling back to SELF scope",
                    memberId, member.getPid());
            return buildSelfCondition();
        }

        // Get department PID from employee record
        Object deptPidObj = employee.get("org_emp_dept_id");
        if (deptPidObj == null) {
            log.warn("Employee for member {} has no department, falling back to SELF scope", memberId);
            return buildSelfCondition();
        }
        String deptPid = String.valueOf(deptPidObj);

        List<String> deptPids;
        if (includeSub) {
            deptPids = organizationService.getDeptAndSubPids(deptPid);
        } else {
            deptPids = List.of(deptPid);
        }

        if (deptPids.isEmpty()) {
            log.warn("No departments resolved for member {}, falling back to SELF scope", memberId);
            return buildSelfCondition();
        }

        String scopeTypeCode = includeSub ? DataScopeType.DEPT_AND_SUB.code() : DataScopeType.DEPT.code();
        return new DataScopeCondition(
                scopeTypeCode,
                "created_by",
                MetaContext.getCurrentUserId(),
                "org_emp_dept_id",
                deptPids,
                Collections.emptyList()
        );
    }
}
