package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.permission.enums.DataScopeType;
import com.auraboot.framework.rbac.constant.RoleConstants;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 角色服务实现类
 */
@Slf4j
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.auraboot.framework.permission.service.RoleAuthorizationWriteLock authorizationWriteLock;


    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private com.auraboot.framework.menu.service.RoleNavigationService roleNavigation;

    @Autowired
    private com.auraboot.framework.permission.service.RoleAuthorizationAuthoringAccess authoringAccess;

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private UserRoleService userRoleService;
    
    @Autowired
    private PermissionService permissionService;
    
    @Autowired
    private PermissionMapper permissionMapper;

    @Override
    @Transactional
    public Role createRole(Role role) {
        authorizationWriteLock.mutate();
        if (!StringUtils.hasText(role.getCode())) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Role code is required");
        }
        log.info("Creating role: {} for tenant: {}", role.getName(), role.getTenantId());

        validatePlatformDefinition(role.getCode(), role.getTenantId(), role.getScopeType());

        // 设置默认值
        role.setStatus(StatusConstants.ACTIVE);
        role.setPid(UniqueIdGenerator.generate());
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        role.setDeletedFlag(false);
        role.setIsSystem(RoleConstants.isPlatformOnly(role.getCode()));
        
        save(role);
        // Bootstrap administrator/member roles retain the marked compatibility projection.
        if(!Set.of("tenant_admin","tenant_member","platform_admin").contains(role.getCode()))roleNavigation.initialize(role);
        
        log.info("Role created successfully: {}", role.getId());
        return role;
    }

    @Override
    @Transactional
    public Role updateRole(Role role) {
        authorizationWriteLock.mutate();
        if (role.getCode() != null && !StringUtils.hasText(role.getCode())) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Role code must not be blank");
        }
        log.info("Updating role: {}", role.getId());
        
        Role existingRole = getById(role.getId());
        if (existingRole == null) {
            throw BusinessException.i18n("role.not_found", role.getId());
        }
        
        requireOwnedRole(existingRole);
        if (role.getTenantId() != null && !java.util.Objects.equals(role.getTenantId(), existingRole.getTenantId())) {
            throw new org.springframework.security.access.AccessDeniedException("A role cannot move between tenants");
        }
        String nextCode = role.getCode() == null ? existingRole.getCode() : role.getCode();
        if ((RoleConstants.isPlatformOnly(nextCode) || RoleConstants.isPlatformOnly(existingRole.getCode()))
                && !java.util.Objects.equals(nextCode, existingRole.getCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Platform role codes cannot be promoted or renamed");
        }
        validatePlatformDefinition(nextCode, existingRole.getTenantId(),
                role.getScopeType() == null ? existingRole.getScopeType() : role.getScopeType());
        role.setTenantId(existingRole.getTenantId());

        // 系统角色不允许修改某些字段
        role.setIsSystem(Boolean.TRUE.equals(existingRole.getIsSystem()) || RoleConstants.isPlatformOnly(nextCode));
        

        
        role.setUpdatedAt(Instant.now());
        updateById(role);
        
        log.info("Role updated successfully: {}", role.getId());
        return role;
    }

    private static void validatePlatformDefinition(String code, Long tenant, String scope) {
        if (RoleConstants.isPlatformOnly(code)
                && (!com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID.equals(tenant)
                || !"global".equals(scope) || !MetaContext.exists()
                || !tenant.equals(MetaContext.getCurrentTenantId()))) {
            throw new org.springframework.security.access.AccessDeniedException("Platform roles require the System tenant and global scope");
        }
    }

    private static void requireOwnedRole(Role role) {
        if (MetaContext.exists()) {
            Long tenant = MetaContext.getCurrentTenantId();
            boolean ownsGlobalTemplate = role.getTenantId() == null
                    && com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID.equals(tenant);
            if (!ownsGlobalTemplate && !java.util.Objects.equals(role.getTenantId(), tenant)) {
                throw new org.springframework.security.access.AccessDeniedException("Role belongs to another tenant");
            }
        }
    }

    @Override
    @Transactional
    public void setDefaultDataScopeType(Long roleId, String scopeType) {
        authorizationWriteLock.mutate();
        authoringAccess.requireWrite();
        authoringAccess.requireTarget(roleId);
        if (scopeType != null) {
            try {
                DataScopeType.fromCode(scopeType);
            } catch (IllegalArgumentException e) {
                throw new RootUnCheckedException(ResponseCode.BadParam, "Invalid default data scope type", e);
            }
        }
        // Plain VARCHAR column — a scoped LambdaUpdate set is safe (no typeHandler concern) and
        // updates only this column (null clears the default, reverting to deny-by-default).
        boolean updated=lambdaUpdate()
                .eq(Role::getId, roleId)
                .eq(Role::getTenantId, MetaContext.getCurrentTenantId())
                .eq(Role::getDeletedFlag,false)
                .set(Role::getDefaultDataScopeType, scopeType)
                .set(Role::getUpdatedAt, Instant.now())
                .update();
        if(!updated) throw new com.auraboot.framework.exception.BusinessException(ResponseCode.CasVersionConflict,"Role scope target changed during authoring");
        log.info("Set role default data scope: roleId={}, scopeType={}", roleId, scopeType);
    }
//
//    @Override
//    public Role findByCode(String code) {
//        return roleMapper.findByCode(code);
//    }

    @Override
    public Role findByPid(String pid) {
        // REG-7 (DDR-2026-06-30): roles are per-tenant, and ab_role is exempt from the
        // MyBatis-Plus tenant-line interceptor, so the raw findByPid has no tenant predicate.
        // A tenant-A admin holding the ordinary ROLE_MANAGE/PERMISSION_MANAGE permission could
        // therefore read/mutate a tenant-B role by learning its PID (RoleController /
        // PermissionMatrixController / CapabilityController / RoleMemberController all resolve
        // roles this way). When a tenant context is present (every request-scoped caller),
        // scope the lookup to it; system/background callers without a context keep the
        // unscoped behaviour. Same-tenant resolution is unchanged; only cross-tenant PID
        // resolution now returns null (the fix).
        // NOTE: MetaContext.getCurrentTenantId() throws IllegalStateException when no context
        // is initialized (system/background threads), so gate on exists() first.
        if (MetaContext.exists()) {
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId != null) {
                return roleMapper.findByTenantIdAndPid(tenantId, pid);
            }
        }
        return roleMapper.findByPid(pid);
    }

    @Override
    public List<Role> findByTenantId(Long tenantId) {
        return roleMapper.findByTenantId(tenantId);
    }

//    @Override
//    public List<Role> findSystemRoles() {
//        return roleMapper.findSystemRoles();
//    }

//    @Override
//    public List<Role> findByType(String type) {
//        return roleMapper.findByType(type);
//    }

    @Override
    public List<Role> findByTenantIdAndType(Long tenantId, String type) {
        return roleMapper.findByTenantIdAndType(tenantId, type);
    }

    @Override
    public Page<Role> findRoles(int pageNum, int pageSize, Long tenantId, String keyword, String type, String status) {
        Page<Role> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Role> queryWrapper = new QueryWrapper<>();
        
        queryWrapper.eq("deleted_flag", false);
        
        if (tenantId != null) {
            queryWrapper.and(wrapper -> wrapper
                .eq("tenant_id", tenantId));
        }
        
        if (StringUtils.hasText(keyword)) {
            queryWrapper.and(wrapper -> wrapper
                .like("name", keyword)
                .or().like("code", keyword)
                .or().like("description", keyword));
        }
        
        if (StringUtils.hasText(type)) {
            queryWrapper.eq("type", type);
        }
        
        if (StringUtils.hasText(status)) {
            queryWrapper.eq("status", status);
        }
        
        queryWrapper.orderByAsc("priority").orderByDesc("created_at");
        
        return page(page, queryWrapper);
    }

    @Override
    public Role findDefaultRole(Long tenantId) {
        return roleMapper.findDefaultRole(tenantId);
    }

//    @Override
//    public List<Role> findUserRoles(Long userId) {
//        return roleMapper.findUserRoles(userId);
//    }
//
//    @Override
//    public List<Role> findUserRolesByTenant(Long userId, Long tenantId) {
//        return roleMapper.findUserRolesByTenant(userId, tenantId);
//    }

    @Override
    @Transactional
    public boolean enableRole(Long roleId) {
        authorizationWriteLock.mutate();
        log.info("Enabling role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw BusinessException.i18n("role.not_found", roleId);
        }
        requireOwnedRole(role);
        
        role.setStatus(StatusConstants.ACTIVE);
        role.setUpdatedAt(Instant.now());
        
        return updateById(role);
    }

    @Override
    @Transactional
    public boolean disableRole(Long roleId) {
        authorizationWriteLock.mutate();
        log.info("Disabling role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw BusinessException.i18n("role.not_found", roleId);
        }
        requireOwnedRole(role);
        if (RoleConstants.isPlatformOnly(role.getCode())
                && com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID.equals(role.getTenantId())) {
            throw new org.springframework.security.access.AccessDeniedException("Reserved platform roles cannot be disabled or deleted");
        }
        
        // 系统角色不允许禁用
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException("$i18n:role.system_no_disable");
        }
        
        role.setStatus(StatusConstants.INACTIVE);
        role.setUpdatedAt(Instant.now());
        
        return updateById(role);
    }

    @Override
    @Transactional
    public boolean deleteRole(Long roleId) {
        authorizationWriteLock.mutate();
        log.info("Deleting role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw BusinessException.i18n("role.not_found", roleId);
        }
        requireOwnedRole(role);
        if (RoleConstants.isPlatformOnly(role.getCode())
                && com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID.equals(role.getTenantId())) {
            throw new org.springframework.security.access.AccessDeniedException("Reserved platform roles cannot be disabled or deleted");
        }
        
        // 系统角色不允许删除
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException("$i18n:role.system_no_delete");
        }
        
        // 检查是否有用户正在使用该角色
        long userCount = userRoleService.countByRoleId(roleId);
        if (userCount > 0) {
            throw new BusinessException("$i18n:role.in_use");
        }
        
        // 同时删除角色Permission关联
        rolePermissionService.removeAllPermissionsByRoleId(roleId);

        return getBaseMapper().deleteById(role.getId()) > 0;
    }

    @Override
    public boolean isCodeAvailable(String code, Long tenantId) {
        QueryWrapper<Role> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("code", code)
                   .eq("deleted_flag", false);
        
        if (tenantId != null) {
            queryWrapper.and(wrapper -> wrapper
                .eq("tenant_id", tenantId));
        }
        
        return count(queryWrapper) == 0;
    }

    @Override
    @Transactional
    public boolean assignPermissions(Long roleId, List<Long> permissionIds) {
        log.info("Assigning permissions to role: {}, permissions: {}", roleId, permissionIds);

        Role role = getById(roleId);
        if (role == null) {
            throw new BusinessException("Role not found: " + roleId);
        }

        // Remove existing permissions first
        rolePermissionService.removeAllPermissionsByRoleId(roleId);

        // Add new permissions
        rolePermissionService.assignPermissionsToRole(roleId, permissionIds);

        return true;
    }

    @Override
    @Transactional
    public boolean removePermissions(Long roleId, List<Long> permissionIds) {
        log.info("Removing permissions from role: {}, permissions: {}", roleId, permissionIds);

        for (Long permissionId : permissionIds) {
            rolePermissionService.removePermission(roleId, permissionId);
        }

        return true;
    }

    @Override
    public List<Long> getRolePermissionIds(Long roleId) {
        Set<Long> permissionIds = rolePermissionService.getPermissionIdsByRoleId(roleId);
        return new ArrayList<>(permissionIds);
    }

    @Override
    @Transactional
    public boolean assignRoleToMember(Long memberId, Long roleId, Long tenantId) {
        log.info("Assigning role {} to member {} in tenant {}", roleId, memberId, tenantId);

        Long operatorId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        return userRoleService.assignRolesToMember(memberId, List.of(roleId), tenantId, operatorId);
    }



    @Override
    @Transactional
    public boolean removeRoleFromMember(Long memberId, Long roleId, Long tenantId) {
        log.info("Removing role {} from member {} in tenant {}", roleId, memberId, tenantId);

        return userRoleService.removeMemberRole(memberId, roleId, tenantId);
    }

    @Override
    public long countByTenantId(Long tenantId) {
        return roleMapper.countByTenantId(tenantId);
    }

    @Override
    @Transactional
    public Role copyRole(Long roleId, String newName, String newCode) {
        authorizationWriteLock.mutate();
        log.info("Copying role: {} to new role: {}", roleId, newName);
        authoringAccess.requireWrite();
        
        Role originalRole = getById(roleId);
        if (originalRole == null) {
            throw BusinessException.i18n("role.original_not_found", roleId);
        }
        
        requireOwnedRole(originalRole);
        if (RoleConstants.isPlatformOnly(originalRole.getCode()) || "platform".equalsIgnoreCase(originalRole.getRoleScope()))
            throw new org.springframework.security.access.AccessDeniedException("Platform roles cannot be copied through business role authoring");
        if (RoleConstants.isPlatformOnly(newCode)) {
            throw new org.springframework.security.access.AccessDeniedException("Copying cannot create a platform role");
        }

        // 检查新编码是否可用
        if (!isCodeAvailable(newCode, originalRole.getTenantId())) {
            throw BusinessException.i18n("role.code_exists", newCode);
        }
        
        // 创建新角色
        Role newRole = new Role();
        newRole.setTenantId(originalRole.getTenantId());
        newRole.setPid(UniqueIdGenerator.generate());
        newRole.setCode(newCode);
        newRole.setName(newName);
        newRole.setDescription("复制自: " + originalRole.getName());
        newRole.setType(originalRole.getType());
        newRole.setRoleScope(originalRole.getRoleScope());
        newRole.setScopeType(originalRole.getScopeType());
        newRole.setScopeContent(originalRole.getScopeContent());
        newRole.setDefaultDataScopeType(originalRole.getDefaultDataScopeType());

        newRole.setPriority(originalRole.getPriority());
        newRole.setStatus(originalRole.getStatus());
        newRole.setIsDefault(false);
        newRole.setIsSystem(false);
        newRole.setCreatedAt(Instant.now());
        newRole.setUpdatedAt(Instant.now());
        newRole.setDeletedFlag(false);
        
        if (!save(newRole)) throw new IllegalStateException("Copied role was not persisted");
        
        // Copy complete bindings and shared role constraints in this same transaction.
        if (!rolePermissionService.copyRolePermissions(roleId,newRole.getId()))
            throw new IllegalStateException("Role authorization copy did not complete");
        
        return newRole;
    }

    @Override
    @Transactional
    public void createDefaultRolesForTenant(Long tenantId) {
        log.info("Creating default roles for tenant: {}", tenantId);
        
        // 创建租户管理员角色
        Role adminRole = new Role();
        adminRole.setTenantId(tenantId);
        adminRole.setName("租户管理员");
        adminRole.setPid(UniqueIdGenerator.generate());
        adminRole.setDescription("租户管理员，拥有租户内所有权限");
        adminRole.setType("business");
        adminRole.setPriority(1);
        adminRole.setStatus(StatusConstants.ACTIVE);
        // The admin role must never be the tenant's default role: provisioning with empty
        // roleCodes assigns the default role (RoleAssignmentMode.DEFAULT), so a default
        // tenant_admin silently grants admin to every casually created user (P0, 2026-09-20).
        adminRole.setIsDefault(false);
        adminRole.setIsSystem(false);
        adminRole.setCreatedAt(Instant.now());
        adminRole.setUpdatedAt(Instant.now());
        adminRole.setDeletedFlag(false);
        save(adminRole);

        
        log.info("Default roles created for tenant: {}", tenantId);
    }

    @Override
    public Map<String, Object> getRoleStatistics(Long tenantId) {
        Map<String, Object> statistics = new HashMap<>();
        
        // 总角色数
        long totalRoles = countByTenantId(tenantId);
        statistics.put("totalRoles", totalRoles);
        
        // 按类型统计
        QueryWrapper<Role> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tenant_id", tenantId)
                   .eq("deleted_flag", false)
                   .select("type", "COUNT(*) as count")
                   .groupBy("type");
        
        List<Map<String, Object>> typeStats = listMaps(queryWrapper);
        statistics.put("rolesByType", typeStats);
        
        // 按状态统计
        QueryWrapper<Role> statusQueryWrapper = new QueryWrapper<>();
        statusQueryWrapper.eq("tenant_id", tenantId)
                         .eq("deleted_flag", false)
                         .select("status", "COUNT(*) as count")
                         .groupBy("status");
        
        List<Map<String, Object>> statusStats = listMaps(statusQueryWrapper);
        statistics.put("rolesByStatus", statusStats);
        
        return statistics;
    }




    @Override
    public List<Map<String, Object>> getRoleHierarchy(Long tenantId) {
        List<Role> roles = findByTenantId(tenantId);
        List<Map<String, Object>> hierarchy = new ArrayList<>();
        
        for (Role role : roles) {
            Map<String, Object> roleInfo = new HashMap<>();
            roleInfo.put("id", role.getId());
            roleInfo.put("name", role.getName());
            roleInfo.put("type", role.getType());
            roleInfo.put("priority", role.getPriority());
            roleInfo.put("permissionCount", getRolePermissionIds(role.getId()).size());
            hierarchy.add(roleInfo);
        }
        
        // 按优先级排序
        hierarchy.sort((a, b) -> {
            Integer priorityA = (Integer) a.get("priority");
            Integer priorityB = (Integer) b.get("priority");
            return priorityA.compareTo(priorityB);
        });
        
        return hierarchy;
    }

    @Override
    @Transactional
    public void initializeSystemRoles() {
        log.info("Initializing system roles");
        
        // 检查是否已存在系统角色
        QueryWrapper<Role> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("is_system", true);
        long systemRoleCount = count(queryWrapper);
        
        if (systemRoleCount > 0) {
            log.info("System roles already exist, skipping initialization");
            return;
        }
        
        // 创建超级管理员角色
        Role superAdminRole = new Role();
        superAdminRole.setTenantId(null); // 系统角色不属于任何租户
        superAdminRole.setPid(UniqueIdGenerator.generate());
        superAdminRole.setName("超级管理员");
        superAdminRole.setCode("super_admin");
        superAdminRole.setDescription("系统超级管理员，拥有所有Permission");
        superAdminRole.setType("system");
        superAdminRole.setPriority(0);
        superAdminRole.setStatus(StatusConstants.ACTIVE);
        superAdminRole.setIsDefault(false);
        superAdminRole.setIsSystem(true);
        superAdminRole.setCreatedAt(Instant.now());
        superAdminRole.setUpdatedAt(Instant.now());
        superAdminRole.setDeletedFlag(false);
        save(superAdminRole);
        
        // 为超级管理员角色分配所有Permission
        // 直接从Mapper查询所有ACTIVE状态的Permission
        List<Permission> allPermissions = permissionMapper.findByStatus(StatusConstants.ACTIVE);
        if (!allPermissions.isEmpty()) {
            List<Long> permissionIds = allPermissions.stream()
                    .map(Permission::getId)
                    .collect(Collectors.toList());
            assignPermissions(superAdminRole.getId(), permissionIds);
            log.info("Assigned {} permissions to super admin role", permissionIds.size());
        }

        log.info("System roles initialized successfully");
    }
}
