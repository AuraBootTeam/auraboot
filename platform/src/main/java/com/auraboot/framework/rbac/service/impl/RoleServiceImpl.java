package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.constant.RoleConstants;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
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

    @Autowired
    private RoleMapper roleMapper;

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
        log.info("Creating role: {} for tenant: {}", role.getName(), role.getTenantId());

        // Guard: platform-only roles (e.g. platform_admin) must have scope_type=global
        if (RoleConstants.isPlatformOnly(role.getCode())
                && !"global".equals(role.getScopeType())) {
            throw new BusinessException(
                "Role code '" + role.getCode() + "' is reserved for platform level (scope_type=global). "
                + "It cannot be created in a business tenant.");
        }

        // 设置默认值
        role.setStatus(StatusConstants.ACTIVE);
        role.setPid(UniqueIdGenerator.generate());
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        role.setDeletedFlag(false);
        role.setIsSystem(false);
        
        save(role);
        
        log.info("Role created successfully: {}", role.getId());
        return role;
    }

    @Override
    @Transactional
    public Role updateRole(Role role) {
        log.info("Updating role: {}", role.getId());
        
        Role existingRole = getById(role.getId());
        if (existingRole == null) {
            throw new BusinessException("角色不存在: " + role.getId());
        }
        
        // 系统角色不允许修改某些字段
        if (Boolean.TRUE.equals(existingRole.getIsSystem())) {
            role.setIsSystem(true);
        }
        

        
        role.setUpdatedAt(Instant.now());
        updateById(role);
        
        log.info("Role updated successfully: {}", role.getId());
        return role;
    }
//
//    @Override
//    public Role findByCode(String code) {
//        return roleMapper.findByCode(code);
//    }

    @Override
    public Role findByPid(String pid) {
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
        log.info("Enabling role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在: " + roleId);
        }
        
        role.setStatus(StatusConstants.ACTIVE);
        role.setUpdatedAt(Instant.now());
        
        return updateById(role);
    }

    @Override
    @Transactional
    public boolean disableRole(Long roleId) {
        log.info("Disabling role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在: " + roleId);
        }
        
        // 系统角色不允许禁用
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException("系统角色不允许禁用");
        }
        
        role.setStatus(StatusConstants.INACTIVE);
        role.setUpdatedAt(Instant.now());
        
        return updateById(role);
    }

    @Override
    @Transactional
    public boolean deleteRole(Long roleId) {
        log.info("Deleting role: {}", roleId);
        
        Role role = getById(roleId);
        if (role == null) {
            throw new BusinessException("角色不存在: " + roleId);
        }
        
        // 系统角色不允许删除
        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException("系统角色不允许删除");
        }
        
        // 检查是否有用户正在使用该角色
        long userCount = userRoleService.countByRoleId(roleId);
        if (userCount > 0) {
            throw new BusinessException("该角色正在被使用，无法删除");
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
    public boolean assignRoleToUser(Long userId, Long roleId, Long tenantId, Long storeId) {
        log.info("Assigning role {} to user {} in tenant {}", roleId, userId, tenantId);

        
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setPid(UniqueIdGenerator.generate());

        userRole.setTenantId(tenantId);
        userRole.setRoleId(roleId);
        userRole.setAssignType("direct");

        userRole.setStatus(StatusConstants.ACTIVE);
        userRole.setCreatedAt(Instant.now());
            userRole.setUpdatedAt(Instant.now());
        userRole.setDeletedFlag(false);
        
        return userRoleService.save(userRole);
    }



    @Override
    @Transactional
    public boolean removeRoleFromUser(Long userId, Long roleId, Long tenantId) {
        log.info("Removing role {} from user {} in tenant {}", roleId, userId, tenantId);
        
        return userRoleService.removeUserRole(userId, roleId, tenantId);
    }

    @Override
    public long countByTenantId(Long tenantId) {
        return roleMapper.countByTenantId(tenantId);
    }

    @Override
    @Transactional
    public Role copyRole(Long roleId, String newName, String newCode) {
        log.info("Copying role: {} to new role: {}", roleId, newName);
        
        Role originalRole = getById(roleId);
        if (originalRole == null) {
            throw new BusinessException("原角色不存在: " + roleId);
        }
        
        // 检查新编码是否可用
        if (!isCodeAvailable(newCode, originalRole.getTenantId())) {
            throw new BusinessException("角色编码已存在: " + newCode);
        }
        
        // 创建新角色
        Role newRole = new Role();
        newRole.setTenantId(originalRole.getTenantId());
        newRole.setPid(UniqueIdGenerator.generate());
        newRole.setCode(newCode);
        newRole.setName(newName);
        newRole.setDescription("复制自: " + originalRole.getName());
        newRole.setType(originalRole.getType());

        newRole.setPriority(originalRole.getPriority());
        newRole.setStatus(StatusConstants.ACTIVE);
        newRole.setIsDefault(false);
        newRole.setIsSystem(false);
        newRole.setCreatedAt(Instant.now());
        newRole.setUpdatedAt(Instant.now());
        newRole.setDeletedFlag(false);
        
        save(newRole);
        
        // Copy permissions
        List<Long> permissionIds = getRolePermissionIds(roleId);
        if (!permissionIds.isEmpty()) {
            assignPermissions(newRole.getId(), permissionIds);
        }
        
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
        adminRole.setIsDefault(true);
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