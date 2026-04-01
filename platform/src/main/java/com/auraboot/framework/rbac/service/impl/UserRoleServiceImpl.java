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
 * User-role association service implementation.
 * Phase 2: ab_user_role uses member_id (tenant_member.id).
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {

    @Resource
    private UserRoleMapper userRoleMapper;

    @Override
    @Transactional
    public boolean assignRolesToMember(Long memberId, List<Long> roleIds, Long tenantId, Long operatorId) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return true;
        }

        List<UserRole> userRoles = new ArrayList<>();

        for (Long roleId : roleIds) {
            UserRole existing = findByMemberIdAndRoleIdAndTenantId(memberId, roleId, tenantId);
            if (existing == null) {
                UserRole userRole = new UserRole();
                userRole.setMemberId(memberId);
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
    public boolean removeRolesFromMember(Long memberId, List<Long> roleIds, Long tenantId) {
        if (CollectionUtils.isEmpty(roleIds)) {
            return true;
        }

        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("member_id", memberId)
                .in("role_id", roleIds);

        if (tenantId != null) {
            wrapper.eq("tenant_id", tenantId);
        }

        return remove(wrapper);
    }

    @Override
    @Transactional
    public boolean removeAllRolesFromMemberInTenant(Long memberId, Long tenantId) {
        return userRoleMapper.deleteByMemberIdAndTenantId(memberId, tenantId) >= 0;
    }

    @Override
    public List<UserRole> findByMemberIdAndTenantId(Long memberId, Long tenantId) {
        return userRoleMapper.findByMemberIdAndTenantId(memberId, tenantId);
    }

    @Override
    public UserRole findByMemberIdAndRoleIdAndTenantId(Long memberId, Long roleId, Long tenantId) {
        return userRoleMapper.findByMemberIdAndRoleIdAndTenantId(memberId, roleId, tenantId);
    }

    @Override
    public UserRole findByPid(String pid) {
        return userRoleMapper.findByPid(pid);
    }

    @Override
    public Page<UserRole> findUserRoles(int pageNum, int pageSize, Long memberId, Long roleId, Long tenantId, Long storeId) {
        Page<UserRole> page = new Page<>(pageNum, pageSize);
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();

        if (memberId != null) {
            wrapper.eq("member_id", memberId);
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

        wrapper.orderByDesc("created_at");

        return page(page, wrapper);
    }

    @Override
    public long countByMemberId(Long memberId) {
        return 0;
    }

    @Override
    public long countByRoleId(Long roleId) {
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
    public boolean copyMemberRoles(Long sourceMemberId, Long targetMemberId, Long tenantId) {
        List<UserRole> sourceRoles = findByMemberIdAndTenantId(sourceMemberId, tenantId);
        if (CollectionUtils.isEmpty(sourceRoles)) {
            return true;
        }

        removeAllRolesFromMemberInTenant(targetMemberId, tenantId);

        List<UserRole> targetRoles = new ArrayList<>();

        for (UserRole sourceRole : sourceRoles) {
            UserRole targetRole = new UserRole();
            targetRole.setMemberId(targetMemberId);
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
    public boolean syncMemberRoles(Long memberId, List<Long> roleIds, Long tenantId, Long operatorId) {
        List<Long> currentRoleIds = getRoleIdsByMemberIdAndTenantId(memberId, tenantId);

        List<Long> toAdd = roleIds.stream()
                .filter(id -> !currentRoleIds.contains(id))
                .collect(Collectors.toList());

        List<Long> toRemove = currentRoleIds.stream()
                .filter(id -> !roleIds.contains(id))
                .collect(Collectors.toList());

        if (!CollectionUtils.isEmpty(toAdd)) {
            assignRolesToMember(memberId, toAdd, tenantId, operatorId);
        }

        if (!CollectionUtils.isEmpty(toRemove)) {
            removeRolesFromMember(memberId, toRemove, tenantId);
        }

        return true;
    }

    @Override
    public List<Long> getRoleIdsByMemberIdAndTenantId(Long memberId, Long tenantId) {
        List<UserRole> userRoles = findByMemberIdAndTenantId(memberId, tenantId);
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
    public Map<String, Object> validateMemberRoles(Long memberId, Long tenantId) {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<UserRole> userRoles = findByMemberIdAndTenantId(memberId, tenantId);

        if (CollectionUtils.isEmpty(userRoles)) {
            warnings.add("Member has no roles in this tenant");
        }

        for (UserRole userRole : userRoles) {
            if (userRole.getRoleId() == null) {
                errors.add("Invalid role ID found");
            }
            if (StatusConstants.INACTIVE.equals(userRole.getStatus())) {
                warnings.add("Inactive role assignment found");
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
        return 0;
    }

    @Override
    public List<UserRole> findByMemberIds(List<Long> memberIds) {
        if (CollectionUtils.isEmpty(memberIds)) {
            return new ArrayList<>();
        }

        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.in("member_id", memberIds);
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
    public List<Map<String, Object>> getMemberRoleHistory(Long memberId, Long tenantId, int days) {
        return new ArrayList<>();
    }

    @Override
    @Transactional
    public boolean transferMemberRolesToTenant(Long memberId, Long fromTenantId, Long toTenantId) {
        List<UserRole> sourceRoles = findByMemberIdAndTenantId(memberId, fromTenantId);
        if (CollectionUtils.isEmpty(sourceRoles)) {
            return true;
        }

        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("member_id", memberId)
                .eq("tenant_id", fromTenantId)
                .set("tenant_id", toTenantId)
                .set("updated_at", Instant.now());

        return update(wrapper);
    }

    @Override
    @Transactional
    public boolean activateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.ACTIVE)
                .set("updated_at", Instant.now());
        return update(wrapper);
    }

    @Override
    @Transactional
    public boolean deactivateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.INACTIVE)
                .set("updated_at", Instant.now());
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
                .set("updated_at", Instant.now());

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
                .set("updated_at", Instant.now());

        return update(wrapper) ? userRoleIds.size() : 0;
    }

    @Override
    public boolean removeMemberRole(Long memberId, Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("member_id", memberId)
                .eq("role_id", roleId)
                .eq("tenant_id", tenantId);
        return remove(wrapper);
    }
}
