package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Role-Permission Service Implementation
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePermissionServiceImpl implements RolePermissionService {
    
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final UserPermissionService userPermissionService;
    
    @Override
    @Transactional
    public boolean assignPermissionsToRole(Long roleId, List<Long> permissionIds) {
        log.info("分配Permission到角色: roleId={}, permissionCount={}", roleId, permissionIds.size());
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            // Build bindings — batchInsert uses ON CONFLICT DO UPDATE for duplicates
            Instant now = Instant.now();
            List<RolePermission> bindings = new ArrayList<>(permissionIds.size());
            for (Long permissionId : permissionIds) {
                RolePermission binding = new RolePermission();
                binding.setPid(UniqueIdGenerator.generate());
                binding.setRoleId(roleId);
                binding.setPermissionId(permissionId);
                binding.setGrantType(StatusConstants.GRANT);
                binding.setStatus(StatusConstants.ACTIVE);
                binding.setDeletedFlag(false);
                binding.setTenantId(tenantId);
                binding.setCreatedAt(now);
                binding.setUpdatedAt(now);
                bindings.add(binding);
            }
            
            if (!bindings.isEmpty()) {
                rolePermissionMapper.batchInsert(bindings);
                log.info("成功分配{}个Permission到角色: roleId={}", bindings.size(), roleId);
            }
            
            // 清除用户Permission缓存
            userPermissionService.evictRoleUsers(roleId);
            
            return true;
            
        } catch (Exception e) {
            log.error("分配Permission到角色失败: roleId={}", roleId, e);
            throw new BusinessException("分配Permission失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @Transactional
    public boolean removePermission(Long roleId, Long permissionId) {
        log.info("从角色移除Permission: roleId={}, permissionId={}", roleId, permissionId);
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            int deletedCount = rolePermissionMapper.deleteByRoleAndPermission(
                roleId, permissionId, tenantId );
            
            log.info("成功从角色移除Permission: roleId={}, permissionId={}, deletedCount={}", 
                roleId, permissionId, deletedCount);
            
            // 清除用户Permission缓存
            userPermissionService.evictRoleUsers(roleId);
            
            return deletedCount > 0;
            
        } catch (Exception e) {
            log.error("从角色移除Permission失败: roleId={}, permissionId={}", roleId, permissionId, e);
            throw new BusinessException("移除Permission失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @Transactional
    public boolean removeAllPermissionsByRoleId(Long roleId) {
        log.info("移除角色的所有Permission: roleId={}", roleId);
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            int deletedCount = rolePermissionMapper.deleteByRoleId(roleId, tenantId  );
            
            log.info("成功移除角色的所有Permission: roleId={}, deletedCount={}", roleId, deletedCount);
            
            // 清除用户Permission缓存
            userPermissionService.evictRoleUsers(roleId);
            
            return true;
            
        } catch (Exception e) {
            log.error("移除角色的所有Permission失败: roleId={}", roleId, e);
            throw new BusinessException("移除所有Permission失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Set<Long> getPermissionIdsByRoleId(Long roleId) {
        log.debug("获取角色的Permission IDs: roleId={}", roleId);
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            List<RolePermission> bindings = rolePermissionMapper.findByRoleId(
                roleId, tenantId  );
            
            return bindings.stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toSet());
                
        } catch (Exception e) {
            log.error("获取角色的Permission IDs失败: roleId={}", roleId, e);
            return Collections.emptySet();
        }
    }
    
    @Override
    public List<String> getPermissionPidsByRoleId(Long roleId) {
        log.debug("获取角色的Permission PIDs: roleId={}", roleId);
        
        try {
            Set<Long> permissionIds = getPermissionIdsByRoleId(roleId);
            
            if (permissionIds.isEmpty()) {
                return Collections.emptyList();
            }
            
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            List<Permission> permissions = permissionMapper.findByIds(
                new ArrayList<>(permissionIds) );
            
            return permissions.stream()
                .map(Permission::getPid)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("获取角色的Permission PIDs失败: roleId={}", roleId, e);
            return Collections.emptyList();
        }
    }
    
    @Override
    @Transactional
    public boolean syncRolePermissionsByPids(Long roleId, List<String> permissionPids, String grantType) {
        log.info("同步角色Permission (by PIDs): roleId={}, permissionCount={}", roleId, permissionPids.size());
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            // 1. 移除现有绑定
            removeAllPermissionsByRoleId(roleId);
            
            // 2. 查询Permission IDs by PIDs
            List<Permission> permissions = permissionMapper.findByPids(
                permissionPids  );
            
            if (permissions.isEmpty()) {
                log.warn("未找到任何Permission: pids={}", permissionPids);
                return true; // 空列表也算成功
            }
            
            List<Long> permissionIds = permissions.stream()
                .map(Permission::getId)
                .collect(Collectors.toList());
            
            // 3. 创建新绑定
            return assignPermissionsToRole(roleId, permissionIds);
            
        } catch (Exception e) {
            log.error("同步角色Permission失败: roleId={}", roleId, e);
            throw new BusinessException("同步Permission失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    @Transactional
    public boolean removePermissionsFromRoleByPids(Long roleId, List<String> permissionPids) {
        log.info("从角色移除Permission (by PIDs): roleId={}, permissionCount={}", roleId, permissionPids.size());
        
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
                  
                  
            
            // 查询Permission IDs by PIDs
            List<Permission> permissions = permissionMapper.findByPids(
                permissionPids  );
            
            if (permissions.isEmpty()) {
                log.warn("未找到任何Permission: pids={}", permissionPids);
                return true;
            }
            
            // 批量删除
            for (Permission permission : permissions) {
                removePermission(roleId, permission.getId());
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("从角色移除Permission失败: roleId={}", roleId, e);
            throw new BusinessException("移除Permission失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Map<String, Object> getRolePermissionStatistics(Long roleId) {
        log.debug("获取角色Permission统计: roleId={}", roleId);
        
        try {
            Set<Long> permissionIds = getPermissionIdsByRoleId(roleId);
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalPermissions", permissionIds.size());
            statistics.put("roleId", roleId);
            
            if (!permissionIds.isEmpty()) {
                Long tenantId = MetaContext.getCurrentTenantId();
                      
                      
                
                List<Permission> permissions = permissionMapper.findByIds(
                    new ArrayList<>(permissionIds)  );
                
                // 按资源类型分组
                Map<String, Long> byResource = permissions.stream()
                    .collect(Collectors.groupingBy(
                        Permission::getResourceType,
                        Collectors.counting()
                    ));
                statistics.put("byResource", byResource);
                
                // 按操作分组
                Map<String, Long> byAction = permissions.stream()
                    .collect(Collectors.groupingBy(
                        Permission::getAction,
                        Collectors.counting()
                    ));
                statistics.put("byAction", byAction);
            } else {
                statistics.put("byResource", Collections.emptyMap());
                statistics.put("byAction", Collections.emptyMap());
            }
            
            return statistics;
            
        } catch (Exception e) {
            log.error("获取角色Permission统计失败: roleId={}", roleId, e);
            return Collections.emptyMap();
        }
    }
    
    @Override
    @Transactional
    public boolean copyRolePermissions(Long sourceRoleId, Long targetRoleId) {
        log.info("复制角色Permission: sourceRoleId={}, targetRoleId={}", sourceRoleId, targetRoleId);
        
        try {
            // 获取源角色的所有Permission IDs
            Set<Long> permissionIds = getPermissionIdsByRoleId(sourceRoleId);
            
            if (permissionIds.isEmpty()) {
                log.info("源角色没有Permission,跳过复制: sourceRoleId={}", sourceRoleId);
                return true;
            }
            
            // 分配到目标角色
            return assignPermissionsToRole(targetRoleId, new ArrayList<>(permissionIds));
            
        } catch (Exception e) {
            log.error("复制角色Permission失败: sourceRoleId={}, targetRoleId={}", 
                sourceRoleId, targetRoleId, e);
            throw new BusinessException("复制Permission失败: " + e.getMessage(), e);
        }
    }
}
