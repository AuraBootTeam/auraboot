package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 用户角色关联服务实现类
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {

    @Resource
    private UserRoleMapper userRoleMapper;

    @Override
    @Transactional
    public boolean assignRolesToUser(Long userId, List<Long> roleIds, Long tenantId, Long operatorId) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return true;
        }

        List<UserRole> userRoles = new ArrayList<>();
        Instant now = Instant.now();

        for (Long roleId : roleIds) {
            // 检查是否已存在关联关系
            UserRole existing = findByUserIdAndRoleIdAndTenantId(userId, roleId, tenantId);
            if (existing == null) {
                UserRole userRole = new UserRole();
                userRole.setUserId(userId);
                userRole.setPid(UniqueIdGenerator.generate());
                userRole.setTenantId(tenantId);
                userRole.setRoleId(roleId);
                userRole.setStatus(StatusConstants.ACTIVE);
                userRole.setCreatedAt(Instant.now());
        userRole.setUpdatedAt(Instant.now());
                userRoles.add(userRole);
            }
        }

        return CollectionUtils.isEmpty(userRoles) || saveBatch(userRoles);
    }

    @Override
    @Transactional
    public boolean removeRolesFromUser(Long userId, List<Long> roleIds, Long tenantId) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return true;
        }

        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .in("role_id", roleIds);
        
        if (tenantId != null) {
            wrapper.eq("tenant_id", tenantId);
        }
        
        return remove(wrapper);
    }


    @Override
    @Transactional
    public boolean removeAllRolesFromUserInTenant(Long userId, Long tenantId) {
        return userRoleMapper.deleteByUserIdAndTenantId(userId, tenantId) >= 0;
    }


    @Override
    public List<UserRole> findByUserIdAndTenantId(Long userId, Long tenantId) {
        return userRoleMapper.findByUserIdAndTenantId(userId, tenantId);
    }

    @Override
    public UserRole findByUserIdAndRoleIdAndTenantId(Long userId, Long roleId, Long tenantId) {
        return userRoleMapper.findByUserIdAndRoleIdAndTenantId(userId, roleId, tenantId);
    }

    @Override
    public UserRole findByPid(String pid) {
        return userRoleMapper.findByPid(pid);
    }


    @Override
    public Page<UserRole> findUserRoles(int pageNum, int pageSize, Long userId, Long roleId, Long tenantId, Long storeId) {
        Page<UserRole> page = new Page<>(pageNum, pageSize);
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        
        if (roleId != null) {
            wrapper.eq("role_id", roleId);
        }
        
        if (tenantId != null) {
            wrapper.eq("tenant_id", tenantId);
        }
        
        if (storeId != null) {
            wrapper.eq("store_id", storeId);
        }
        
        wrapper.orderByDesc("create_time");
        
        return page(page, wrapper);
    }




    @Override
    public long countByUserId(Long userId) {
        // 临时实现：统计用户角色数量
        // TODO: 需要实现具体的统计逻辑
        return 0;
    }

    @Override
    public long countByRoleId(Long roleId) {
        // 临时实现：统计角色用户数量
        // TODO: 需要实现具体的统计逻辑
        return 0;
    }

    @Override
    public long countByTenantId(Long tenantId) {
        return userRoleMapper.countByTenantId(tenantId);
    }

    @Override
    @Transactional
    public int batchAssignRoles(List<UserRole> userRoles) {
        if (CollectionUtils.isEmpty(userRoles)) {
            return 0;
        }

        Instant now = Instant.now();
        for (UserRole userRole : userRoles) {
            userRole.setCreatedAt(now);
            userRole.setUpdatedAt(now);
            if (userRole.getStatus() == null) {
                userRole.setStatus(StatusConstants.ACTIVE);
            }
        }

        return saveBatch(userRoles) ? userRoles.size() : 0;
    }

    @Override
    @Transactional
    public int batchRemoveRoles(List<Long> userRoleIds) {
        if (CollectionUtils.isEmpty(userRoleIds)) {
            return 0;
        }

        // Verify all records belong to the current tenant before deleting
        Long currentTenantId = MetaContext.getCurrentTenantId();
        if (currentTenantId != null) {
            List<UserRole> records = listByIds(userRoleIds);
            for (UserRole record : records) {
                if (!currentTenantId.equals(record.getTenantId())) {
                    throw new BusinessException("Cannot remove user roles from another tenant");
                }
            }
        }

        return removeByIds(userRoleIds) ? userRoleIds.size() : 0;
    }

    @Override
    @Transactional
    public boolean copyUserRoles(Long sourceUserId, Long targetUserId, Long tenantId) {
        // 获取源用户在指定租户下的所有角色
        List<UserRole> sourceRoles = findByUserIdAndTenantId(sourceUserId, tenantId);
        if (CollectionUtils.isEmpty(sourceRoles)) {
            return true;
        }

        // 先清除目标用户在该租户下的所有角色
        removeAllRolesFromUserInTenant(targetUserId, tenantId);

        // 复制角色到目标用户
        List<UserRole> targetRoles = new ArrayList<>();
        Instant now = Instant.now();

        for (UserRole sourceRole : sourceRoles) {
            UserRole targetRole = new UserRole();
            targetRole.setUserId(targetUserId);
            targetRole.setPid(UniqueIdGenerator.generate());

            targetRole.setTenantId(tenantId);

            targetRole.setRoleId(sourceRole.getRoleId());
            targetRole.setStatus(sourceRole.getStatus());
            targetRole.setCreatedAt(Instant.now());
        targetRole.setUpdatedAt(Instant.now());
            targetRoles.add(targetRole);
        }

        return saveBatch(targetRoles);
    }





    @Override
    @Transactional
    public boolean syncUserRoles(Long userId, List<Long> roleIds, Long tenantId, Long operatorId) {
        // 获取用户在指定租户下的当前角色ID列表
        List<Long> currentRoleIds = getRoleIdsByUserIdAndTenantId(userId, tenantId);
        
        // 计算需要添加的角色
        List<Long> toAdd = roleIds.stream()
                .filter(id -> !currentRoleIds.contains(id))
                .collect(Collectors.toList());
        
        // 计算需要移除的角色
        List<Long> toRemove = currentRoleIds.stream()
                .filter(id -> !roleIds.contains(id))
                .collect(Collectors.toList());
        
        // 执行添加操作
        if (!CollectionUtils.isEmpty(toAdd)) {
            assignRolesToUser(userId, toAdd, tenantId, operatorId);
        }
        
        // 执行移除操作
        if (!CollectionUtils.isEmpty(toRemove)) {
            removeRolesFromUser(userId, toRemove, tenantId);
        }
        
        return true;
    }



    @Override
    public List<Long> getRoleIdsByUserIdAndTenantId(Long userId, Long tenantId) {
        List<UserRole> userRoles = findByUserIdAndTenantId(userId, tenantId);
        return userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
    }



    @Override
    public boolean isRoleInUse(Long roleId) {
        return countByRoleId(roleId) > 0;
    }

    @Override
    public boolean isRoleInUseInTenant(Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId).eq("tenant_id", tenantId);
        return count(wrapper) > 0;
    }


    @Override
    public List<Map<String, Object>> getTenantUserRoles(Long tenantId) {
        return userRoleMapper.getTenantUserRoles(tenantId);
    }

    @Override
    public Map<String, Object> validateUserRoles(Long userId, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        // 检查用户角色配置
        List<UserRole> userRoles = findByUserIdAndTenantId(userId, tenantId);
        
        if (CollectionUtils.isEmpty(userRoles)) {
            warnings.add("用户在该租户下未分配任何角色");
        }
        
        // 检查角色是否存在和有效
        for (UserRole userRole : userRoles) {
            if (userRole.getRoleId() == null) {
                errors.add("存在无效的角色ID");
            }
            if (StatusConstants.INACTIVE.equals(userRole.getStatus())) {
                warnings.add("存在已停用的角色分配");
            }
        }
        
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        
        return result;
    }

    @Override
    @Transactional
    public int cleanupInvalidUserRoles() {
        // 临时实现：清理无效的用户角色关联
        // TODO: 需要实现具体的清理逻辑
        return 0;
    }

    @Override
    public List<UserRole> findByUserIds(List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return new ArrayList<>();
        }
        
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.in("user_id", userIds);
        return list(wrapper);
    }

    @Override
    public List<UserRole> findByRoleIds(List<Long> roleIds) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return new ArrayList<>();
        }
        
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.in("role_id", roleIds);
        return list(wrapper);
    }

    @Override
    public List<Map<String, Object>> getUserRoleHistory(Long userId, Long tenantId, int days) {
        // 临时实现：获取用户角色历史
        // TODO: 需要实现具体的历史查询逻辑
        return new ArrayList<>();
    }

    @Override
    @Transactional
    public boolean transferUserRolesToTenant(Long userId, Long fromTenantId, Long toTenantId) {
        // 获取用户在源租户下的所有角色
        List<UserRole> sourceRoles = findByUserIdAndTenantId(userId, fromTenantId);
        if (CollectionUtils.isEmpty(sourceRoles)) {
            return true;
        }

        // 更新租户ID
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("tenant_id", fromTenantId)
                .set("tenant_id", toTenantId)
                .set("update_time", Instant.now());
        
        return update(wrapper);
    }

    @Override
    @Transactional
    public boolean activateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.ACTIVE)
                .set("update_time", Instant.now());
        return update(wrapper);
    }

    @Override
    @Transactional
    public boolean deactivateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.INACTIVE)
                .set("update_time", Instant.now());
        return update(wrapper);
    }

    @Override
    @Transactional
    public int batchActivateUserRoles(List<Long> userRoleIds) {
        if (CollectionUtils.isEmpty(userRoleIds)) {
            return 0;
        }

        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.in("id", userRoleIds)
                .set("status", StatusConstants.ACTIVE)
                .set("update_time", Instant.now());
        
        return update(wrapper) ? userRoleIds.size() : 0;
    }

    @Override
    @Transactional
    public int batchDeactivateUserRoles(List<Long> userRoleIds) {
        if (CollectionUtils.isEmpty(userRoleIds)) {
            return 0;
        }

        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.in("id", userRoleIds)
                .set("status", StatusConstants.INACTIVE)
                .set("update_time", Instant.now());
        
        return update(wrapper) ? userRoleIds.size() : 0;
    }


    @Override
    public boolean removeUserRole(Long userId, Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .eq("role_id", roleId)
                .eq("tenant_id", tenantId);
        return remove(wrapper);
    }
}