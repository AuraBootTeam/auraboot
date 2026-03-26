package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.exception.DuplicateException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.converter.PermissionConverter;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionReferenceDTO;
import com.auraboot.framework.permission.dto.PermissionUpdateRequest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.common.util.DateUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Permission服务实现 (V4)
 *
 * 职责:
 * - Permission CRUD操作
 * - 生命周期管理 (ACTIVE → DEPRECATED → ARCHIVED)
 *
 * @author Kiro
 * @since 2025-01-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {
    
    private final PermissionMapper permissionMapper;
    private final PermissionConverter permissionConverter;
    private final com.auraboot.framework.permission.service.UserPermissionService userPermissionService;
    private final com.auraboot.framework.rbac.mapper.RolePermissionMapper rolePermissionMapper;
    private final com.auraboot.framework.rbac.mapper.RoleMapper roleMapper;
    
    /**
     * 创建Permission
     * 
     * 注意: 通常由系统自动生成，不建议手动创建
     */
    @Override
    @Transactional
    public PermissionDTO create(PermissionCreateRequest request) {
        log.info("Creating permission: code={}", request.getCode());
        
        // 1. 验证请求
        validateCreateRequest(request);
        
        // 2. 检查code唯一性
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        int count = permissionMapper.countByCode(request.getCode(), null);
        
        if (count > 0) {
            throw new DuplicateException("Permission code already exists: " + request.getCode());
        }
        
        // 3. 转换为Entity
        Permission permission = permissionConverter.toEntity(request);
        permission.setPid(UniqueIdGenerator.generate());
        permission.setTenantId(tenantId);

        permission.setStatus(StatusConstants.ACTIVE);
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permission.setCreatedBy(MetaContext.getCurrentUserId());
        permission.setUpdatedBy(MetaContext.getCurrentUserId());

        // Set plugin_pid if provided
        if (request.getPluginPid() != null && !request.getPluginPid().isEmpty()) {
            permission.setPluginPid(request.getPluginPid());
        }

        // 4. 插入数据库
        permissionMapper.insert(permission);
        
        log.info("Permission created: id={}, code={}", permission.getId(), permission.getCode());

        // 6. 返回DTO
        return permissionConverter.toDTO(permission);
    }
    
    /**
     * 更新Permission
     */
    @Override
    @Transactional
    public PermissionDTO update(Long id, PermissionUpdateRequest request) {
        log.info("Updating permission: id={}", id);
        
        // 1. 查询现有记录
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + id);
        }
        
        // 2. 检查状态
        if (StatusConstants.ARCHIVED.equals(permission.getStatus())) {
            throw new IllegalStateException("Cannot update archived permission");
        }
        
        // 3. 更新字段
        permissionConverter.updateEntity(permission, request);
        permission.setUpdatedAt(Instant.now());
        permission.setUpdatedBy(MetaContext.getCurrentUserId());
        
        // 4. 更新数据库
        permissionMapper.updateById(permission);
        
        log.info("Permission updated: id={}, code={}", permission.getId(), permission.getCode());
        

        
        return permissionConverter.toDTO(permission);
    }
    
    /**
     * 删除Permission (软删除)
     */
    @Override
    @Transactional
    public void delete(Long id) {
        log.info("Deleting permission: id={}", id);
        
        // 1. 查询现有记录
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + id);
        }
        
        // 2. 检查是否有子Permission
        List<Permission> children = permissionMapper.findChildren(id);
        
        if (!children.isEmpty()) {
            throw new IllegalStateException(
                String.format("Cannot delete permission with %d children", children.size()));
        }
        
        // 3. 软删除 - 使用 LambdaUpdateWrapper 避免 JSONB 字段类型问题
        LambdaUpdateWrapper<Permission> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Permission::getId, id)
                .set(Permission::getDeletedFlag, true)
                .set(Permission::getUpdatedAt, Instant.now())
                .set(Permission::getUpdatedBy, MetaContext.getCurrentUserId());
        permissionMapper.update(null, updateWrapper);

        log.info("Permission deleted: id={}, code={}", permission.getId(), permission.getCode());
        

    }
    
    /**
     * 查询Permission
     */
    @Override
    public PermissionDTO findById(Long id) {
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + id);
        }
        return permissionConverter.toDTO(permission);
    }
    
    /**
     * 根据code查询Permission
     */
    @Override
    public PermissionDTO findByCode(String code) {
        Permission permission = permissionMapper.findByCode(
            code
        );
        
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + code);
        }
        
        return permissionConverter.toDTO(permission);
    }
    
    /**
     * 查询指定资源类型的所有Permission
     */
    @Override
    public List<PermissionDTO> findByResourceType(String resourceType) {
        List<Permission> permissions = permissionMapper.findByResourceType(
            resourceType
        );
        
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 查询指定资源的所有Permission
     */
    @Override
    public List<PermissionDTO> findByResource(String resourceType, String resourceCode) {
        List<Permission> permissions = permissionMapper.findByResource(
            resourceType,
            resourceCode
        );
        
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 查询所有ACTIVE状态的Permission
     */
    @Override
    public List<PermissionDTO> findAllActive() {
        List<Permission> permissions = permissionMapper.findByStatus(
            "active"
        );
        
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 废弃Permission (6个月过渡期)
     */
    @Override
    @Transactional
    public void deprecate(Long id) {
        log.warn("Deprecating permission: id={}", id);
        
        // 1. 查询现有记录
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + id);
        }
        
        // 2. 检查状态
        if (StatusConstants.DEPRECATED.equals(permission.getStatus())
            || StatusConstants.ARCHIVED.equals(permission.getStatus())) {
            throw new IllegalStateException("Permission is already deprecated or archived");
        }
        
        // 3. 检查是否有子Permission
        List<Permission> children = permissionMapper.findChildren(id);
        
        if (!children.isEmpty()) {
            throw new IllegalStateException(
                String.format("Cannot deprecate permission with %d active children", children.size()));
        }
        
        // 4. 更新状态 - 使用 LambdaUpdateWrapper 避免 JSONB 字段类型问题
        LambdaUpdateWrapper<Permission> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Permission::getId, id)
                .set(Permission::getStatus, "deprecated")
                .set(Permission::getDeprecatedAt, Instant.now())
                .set(Permission::getUpdatedAt, Instant.now())
                .set(Permission::getUpdatedBy, MetaContext.getCurrentUserId());
        permissionMapper.update(null, updateWrapper);

        log.warn("Permission deprecated: id={}, code={}, will be archived after 6 months",
            permission.getId(), permission.getCode());
    }
    
    /**
     * 归档Permission (永久归档)
     */
    @Override
    @Transactional
    public void archive(Long id) {
        log.warn("Archiving permission: id={}", id);
        
        // 1. 查询现有记录
        Permission permission = permissionMapper.selectById(id);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + id);
        }
        
        // 2. 检查状态
        if (!StatusConstants.DEPRECATED.equals(permission.getStatus())) {
            throw new IllegalStateException("Only deprecated permissions can be archived");
        }
        
        // 3. 检查废弃时间是否超过6个月
        Instant sixMonthsAgo = Instant.now().minus(180, ChronoUnit.DAYS);
        if (permission.getDeprecatedAt().isAfter(sixMonthsAgo)) {
            throw new IllegalStateException("Permission has not been deprecated for 6 months yet");
        }
        
        // 4. 更新状态 — 使用 LambdaUpdateWrapper 避免 JSONB 字段类型问题 (consistent with delete/deprecate)
        LambdaUpdateWrapper<Permission> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Permission::getId, id)
                .set(Permission::getStatus, "archived")
                .set(Permission::getArchivedAt, Instant.now())
                .set(Permission::getUpdatedAt, Instant.now())
                .set(Permission::getUpdatedBy, MetaContext.getCurrentUserId());
        permissionMapper.update(null, updateWrapper);

        log.warn("Permission archived: id={}, code={}", permission.getId(), permission.getCode());
    }
    
    /**
     * 查询过期的DEPRECATED Permission (用于自动归档)
     */
    @Override
    public List<PermissionDTO> findDeprecatedForArchive(int monthsThreshold) {
        log.info("Finding deprecated permissions for archiving (threshold: {} months)", monthsThreshold);
        
        Instant thresholdDate = Instant.now().minus(monthsThreshold * 30L, ChronoUnit.DAYS);
        List<Permission> permissions = permissionMapper.findDeprecatedForArchive(thresholdDate);
        
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 查询用户的所有Permission (通过RBAC)
     * 
     * 实现逻辑:
     * 1. 通过UserPermissionService获取用户的Permission ID集合
     * 2. 批量查询Permission详情
     * 3. 转换为DTO返回
     */
    @Override
    public List<PermissionDTO> findUserPermissions(Long userId) {
        log.debug("Finding user permissions: userId={}", userId);
        
        // 1. 获取用户的Permission ID集合
        Set<Long> permissionIds = userPermissionService.getUserPermissionIds(userId);
        
        if (permissionIds.isEmpty()) {
            log.debug("User has no permissions: userId={}", userId);
            return List.of();
        }
        
        log.debug("User has {} permissions", permissionIds.size());
        
        // 2. 批量查询Permission详情
        List<Permission> permissions = permissionMapper.findByIds(
            new java.util.ArrayList<>(permissionIds)
        );
        
        log.debug("Found {} active permissions for user: userId={}", 
            permissions.size(), userId);
        
        // 3. 转换为DTO
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 验证创建请求
     */
    private void validateCreateRequest(PermissionCreateRequest request) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw new IllegalArgumentException("Permission code cannot be empty");
        }
        
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Permission name cannot be empty");
        }
        
        if (request.getResourceType() == null || request.getResourceType().isBlank()) {
            throw new IllegalArgumentException("Resource type cannot be empty");
        }
        
        if (request.getResourceCode() == null || request.getResourceCode().isBlank()) {
            throw new IllegalArgumentException("Resource code cannot be empty");
        }
        
        if (request.getAction() == null || request.getAction().isBlank()) {
            throw new IllegalArgumentException("Action cannot be empty");
        }
    }
    
    /**
     * 查询角色的所有Permission
     * 
     * 实现逻辑:
     * 1. 通过RolePermissionMapper查询角色绑定的Permission ID集合
     * 2. 批量查询Permission详情
     * 3. 转换为DTO返回
     */
    @Override
    public List<PermissionDTO> findRolePermissions(Long roleId) {
        log.debug("Finding role permissions: roleId={}", roleId);
        
        // 1. 获取角色的Permission ID集合
        Set<Long> permissionIds = rolePermissionMapper.findPermissionIdsByRole(roleId);
        
        if (permissionIds.isEmpty()) {
            log.debug("Role has no permissions: roleId={}", roleId);
            return List.of();
        }
        
        log.debug("Role has {} permissions", permissionIds.size());
        
        // 2. 批量查询Permission详情
        List<Permission> permissions = permissionMapper.findByIds(
            new java.util.ArrayList<>(permissionIds)
        );
        
        log.debug("Found {} active permissions for role: roleId={}", 
            permissions.size(), roleId);
        
        // 3. 转换为DTO
        return permissionConverter.toDTOList(permissions);
    }
    
    /**
     * 绑定Permission到角色
     * 
     * 实现逻辑:
     * 1. 验证Permission存在
     * 2. 检查绑定是否已存在
     * 3. 创建RolePermission绑定记录
     */
    @Override
    @Transactional
    public void bindToRole(Long roleId, Long permissionId) {
        log.info("Binding permission to role: roleId={}, permissionId={}", roleId, permissionId);
        
        // 1. 验证Permission存在
        Permission permission = permissionMapper.selectById(permissionId);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + permissionId);
        }
        
        // 2. 检查绑定是否已存在 — idempotent, warn and skip
        int count = rolePermissionMapper.countByBinding(roleId, permissionId, null);
        if (count > 0) {
            log.warn("Permission already bound to role, skipping: roleId={}, permissionId={}", roleId, permissionId);
            return;
        }
        
        // 3. 创建绑定记录
        com.auraboot.framework.rbac.entity.RolePermission binding =
            new com.auraboot.framework.rbac.entity.RolePermission();
        
        binding.setPid(UniqueIdGenerator.generate());
        binding.setTenantId(MetaContext.getCurrentTenantId());

        binding.setRoleId(roleId);
        binding.setPermissionId(permissionId);
        binding.setGrantType(StatusConstants.GRANT);
        binding.setPriority(0);
        binding.setStatus(StatusConstants.ACTIVE);
        binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        binding.setCreatedBy(MetaContext.getCurrentUserId());
        binding.setUpdatedBy(MetaContext.getCurrentUserId());
        
        rolePermissionMapper.insert(binding);
        
        log.info("Permission bound to role: roleId={}, permissionId={}, bindingId={}",
            roleId, permissionId, binding.getId());
    }
    
    /**
     * 从角色解绑Permission
     * 
     * 实现逻辑:
     * 1. 查询绑定记录
     * 2. 软删除绑定记录
     */
    @Override
    @Transactional
    public void unbindFromRole(Long roleId, Long permissionId) {
        log.info("Unbinding permission from role: roleId={}, permissionId={}", roleId, permissionId);
        
        // 1. 查询绑定记录
        List<com.auraboot.framework.rbac.entity.RolePermission> bindings =
            rolePermissionMapper.findByRole(roleId).stream()
                .filter(b -> b.getPermissionId().equals(permissionId))
                .toList();
        
        if (bindings.isEmpty()) {
            throw new ResourceNotFoundException(
                "Permission binding not found: roleId=" + roleId + ", permissionId=" + permissionId);
        }
        
        // 2. 软删除所有匹配的绑定记录
        for (com.auraboot.framework.rbac.entity.RolePermission binding : bindings) {
            rolePermissionMapper.softDelete(binding.getId());
            log.info("Permission unbound from role: roleId={}, permissionId={}, bindingId={}",
                roleId, permissionId, binding.getId());
        }
    }
    
    /**
     * 查询Permission的引用情况
     * 
     * 实现逻辑:
     * 1. 通过RolePermissionMapper查询所有引用该Permission的绑定
     * 2. 关联查询Role信息
     * 3. 转换为PermissionReferenceDTO返回
     */
    @Override
    public List<PermissionReferenceDTO> findReferences(Long permissionId) {
        log.debug("Finding permission references: permissionId={}", permissionId);
        
        // 1. 验证Permission存在
        Permission permission = permissionMapper.selectById(permissionId);
        if (permission == null) {
            throw new ResourceNotFoundException("Permission not found: " + permissionId);
        }
        
        // 2. 查询所有引用该Permission的绑定
        List<com.auraboot.framework.rbac.entity.RolePermission> bindings =
            rolePermissionMapper.findByPermission(permissionId);
        
        if (bindings.isEmpty()) {
            log.debug("Permission has no references: permissionId={}", permissionId);
            return List.of();
        }
        
        log.debug("Found {} references for permission: permissionId={}",
            bindings.size(), permissionId);
        
        // 3. 转换为DTO
        return bindings.stream()
            .map(this::convertToReferenceDTO)
            .collect(Collectors.toList());
    }
    
    /**
     * 转换RolePermission为PermissionReferenceDTO
     */
    private PermissionReferenceDTO convertToReferenceDTO(
            com.auraboot.framework.rbac.entity.RolePermission binding) {
        
        PermissionReferenceDTO dto = new PermissionReferenceDTO();
        dto.setId(binding.getId());
        dto.setRoleId(binding.getRoleId());
        dto.setGrantType(binding.getGrantType());
        dto.setPriority(binding.getPriority());
        dto.setEffectiveDate(binding.getEffectiveDate());
        dto.setExpiryDate(binding.getExpiryDate());
        dto.setStatus(binding.getStatus());
        dto.setCreatedAt(DateUtil.toUtcLocalDateTime(binding.getCreatedAt()));
        dto.setCreatedBy(binding.getCreatedBy());
        
        // 查询Role信息
        try {
            com.auraboot.framework.rbac.entity.Role role = roleMapper.selectById(binding.getRoleId());
            if (role != null) {
                dto.setRoleName(role.getName());
                dto.setRoleCode(role.getCode());
            }
        } catch (Exception e) {
            log.warn("Failed to load role info: roleId={}", binding.getRoleId(), e);
        }
        
        return dto;
    }
}
