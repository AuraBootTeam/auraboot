package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.dto.UserRoleResponse;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private TenantMemberMapper tenantMemberMapper;

    @Resource
    private ApplicationEventPublisher eventPublisher;

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
                userRole.setAssignType("direct");
                userRole.setStatus(StatusConstants.ACTIVE);
                userRole.setDeletedFlag(false);
                userRole.setCreatedAt(Instant.now());
                userRole.setUpdatedAt(Instant.now());
                userRoles.add(userRole);
            }
        }

        if (CollectionUtils.isEmpty(userRoles)) {
            return true;
        }
        boolean saved = saveBatch(userRoles);
        if (saved) {
            publishMemberRoleChange(memberId, null, "CREATE");
        }
        return saved;
    }

    @Override
    @Transactional
    public boolean assignRolesToMemberByRolePids(String memberPid, List<String> rolePids, Long tenantId, Long operatorId) {
        if (CollectionUtils.isEmpty(rolePids)) {
            return true;
        }

        TenantMember member = resolveMember(memberPid, tenantId);
        List<Long> roleIds = new ArrayList<>();
        for (String rolePid : rolePids) {
            if (!StringUtils.hasText(rolePid)) {
                throw new BusinessException("Role PID is required");
            }
            Role role = roleMapper.findByTenantIdAndPid(tenantId, rolePid);
            if (role == null) {
                throw new BusinessException("Role not found for pid: " + rolePid);
            }
            roleIds.add(role.getId());
        }

        return assignRolesToMember(member.getId(), roleIds, tenantId, operatorId);
    }

    @Override
    @Transactional
    public boolean assignRolesToMemberByRoleCodes(String memberPid, List<String> roleCodes, Long tenantId, Long operatorId) {
        if (CollectionUtils.isEmpty(roleCodes)) {
            return true;
        }

        TenantMember member = resolveMember(memberPid, tenantId);
        List<Long> roleIds = new ArrayList<>();
        for (String roleCode : roleCodes) {
            if (!StringUtils.hasText(roleCode)) {
                throw new BusinessException("Role code is required");
            }
            Role role = roleMapper.findByTenantIdAndCode(tenantId, roleCode);
            if (role == null) {
                throw new BusinessException("Role not found for code: " + roleCode);
            }
            roleIds.add(role.getId());
        }

        return assignRolesToMember(member.getId(), roleIds, tenantId, operatorId);
    }

    private TenantMember resolveMember(String memberPid, Long tenantId) {
        if (!StringUtils.hasText(memberPid)) {
            throw new BusinessException("Member PID is required");
        }
        TenantMember member = tenantMemberMapper.findByTenantIdAndPid(tenantId, memberPid);
        if (member == null) {
            throw new BusinessException("Member not found for pid: " + memberPid);
        }
        return member;
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

        boolean removed = remove(wrapper);
        if (removed) {
            publishMemberRoleChange(memberId, null, "DELETE");
        }
        return removed;
    }

    @Override
    @Transactional
    public boolean removeRolesFromMemberByRolePids(String memberPid, List<String> rolePids, Long tenantId) {
        if (CollectionUtils.isEmpty(rolePids)) {
            return true;
        }
        TenantMember member = resolveMember(memberPid, tenantId);
        return removeRolesFromMember(member.getId(), resolveRolePids(rolePids, tenantId), tenantId);
    }

    @Override
    @Transactional
    public boolean removeAllRolesFromMemberInTenant(Long memberId, Long tenantId) {
        boolean removed = userRoleMapper.deleteByMemberIdAndTenantId(memberId, tenantId) >= 0;
        publishMemberRoleChange(memberId, null, "DELETE");
        return removed;
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
    public Page<UserRoleResponse> findUserRoleResponses(
            int pageNum,
            int pageSize,
            String memberPid,
            String rolePid,
            Long legacyMemberId,
            Long legacyRoleId,
            Long tenantId,
            Long storeId) {
        Long memberId = resolveMemberId(memberPid, legacyMemberId, tenantId);
        Long roleId = resolveRoleId(rolePid, legacyRoleId, tenantId);
        Page<UserRole> source = findUserRoles(pageNum, pageSize, memberId, roleId, tenantId, storeId);

        Page<UserRoleResponse> response = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        response.setPages(source.getPages());
        response.setRecords(source.getRecords().stream()
                .map(userRole -> toResponse(userRole, tenantId))
                .toList());
        return response;
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

        if (!saveBatch(userRoles)) {
            return 0;
        }
        publishUserRoleRecordsChange(userRoles, "CREATE");
        return userRoles.size();
    }

    @Override
    @Transactional
    public int batchRemoveRoles(List<Long> userRoleIds) {
        if (CollectionUtils.isEmpty(userRoleIds)) {
            return 0;
        }

        Long currentTenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        List<UserRole> records = listByIds(userRoleIds);
        if (currentTenantId != null) {
            for (UserRole record : records) {
                if (!currentTenantId.equals(record.getTenantId())) {
                    throw new BusinessException("Cannot remove user roles from another tenant");
                }
            }
        }

        if (!removeByIds(userRoleIds)) {
            return 0;
        }
        publishUserRoleRecordsChange(records, "DELETE");
        return userRoleIds.size();
    }

    @Override
    @Transactional
    public int batchRemoveRolesByPids(List<String> userRolePids, Long tenantId) {
        if (CollectionUtils.isEmpty(userRolePids)) {
            return 0;
        }

        List<String> normalizedPids = userRolePids.stream()
                .filter(StringUtils::hasText)
                .toList();
        if (normalizedPids.isEmpty()) {
            return 0;
        }

        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.in("pid", normalizedPids);
        if (tenantId != null) {
            wrapper.eq("tenant_id", tenantId);
        }
        List<UserRole> records = list(wrapper);
        if (CollectionUtils.isEmpty(records)) {
            return 0;
        }

        List<Long> ids = records.stream()
                .map(UserRole::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!removeByIds(ids)) {
            return 0;
        }
        publishUserRoleRecordsChange(records, "DELETE");
        return ids.size();
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
    @Transactional
    public boolean syncMemberRolesByRolePids(String memberPid, List<String> rolePids, Long tenantId, Long operatorId) {
        TenantMember member = resolveMember(memberPid, tenantId);
        List<Long> roleIds = CollectionUtils.isEmpty(rolePids)
                ? List.of()
                : resolveRolePids(rolePids, tenantId);
        return syncMemberRoles(member.getId(), roleIds, tenantId, operatorId);
    }

    @Override
    public List<Long> getRoleIdsByMemberIdAndTenantId(Long memberId, Long tenantId) {
        List<UserRole> userRoles = findByMemberIdAndTenantId(memberId, tenantId);
        return userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getRolePidsByMemberPidAndTenantId(String memberPid, Long tenantId) {
        Long memberId = resolveMemberId(memberPid, null, tenantId);
        return findByMemberIdAndTenantId(memberId, tenantId).stream()
                .map(userRole -> resolveRole(userRole, tenantId))
                .filter(Objects::nonNull)
                .map(Role::getPid)
                .filter(StringUtils::hasText)
                .toList();
    }

    @Override
    public List<UserRoleResponse> findRoleMemberResponsesByRolePid(String rolePid, Long tenantId) {
        Long roleId = resolveRoleId(rolePid, null, tenantId);
        return findByRoleIds(List.of(roleId)).stream()
                .filter(userRole -> tenantId == null || tenantId.equals(userRole.getTenantId()))
                .map(userRole -> toResponse(userRole, tenantId))
                .toList();
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
    public Map<String, Object> validateMemberRolesByPid(String memberPid, Long tenantId) {
        return validateMemberRoles(resolveMemberId(memberPid, null, tenantId), tenantId);
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

        if (!update(wrapper)) {
            return false;
        }
        publishUserRoleRecordsChange(sourceRoles, "TRANSFER");
        return true;
    }

    @Override
    @Transactional
    public boolean activateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.ACTIVE)
                .set("updated_at", Instant.now());
        if (!update(wrapper)) {
            return false;
        }
        publishUserRoleRowsChange(List.of(userRoleId), "UPDATE");
        return true;
    }

    @Override
    @Transactional
    public boolean deactivateUserRole(Long userRoleId) {
        UpdateWrapper<UserRole> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", userRoleId)
                .set("status", StatusConstants.INACTIVE)
                .set("updated_at", Instant.now());
        if (!update(wrapper)) {
            return false;
        }
        publishUserRoleRowsChange(List.of(userRoleId), "UPDATE");
        return true;
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

        if (!update(wrapper)) {
            return 0;
        }
        publishUserRoleRowsChange(userRoleIds, "UPDATE");
        return userRoleIds.size();
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

        if (!update(wrapper)) {
            return 0;
        }
        publishUserRoleRowsChange(userRoleIds, "UPDATE");
        return userRoleIds.size();
    }

    @Override
    public boolean removeMemberRole(Long memberId, Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("member_id", memberId)
                .eq("role_id", roleId)
                .eq("tenant_id", tenantId);
        boolean removed = remove(wrapper);
        if (removed) {
            publishMemberRoleChange(memberId, roleId, "DELETE");
        }
        return removed;
    }

    private Long resolveMemberId(String memberPid, Long legacyMemberId, Long tenantId) {
        if (StringUtils.hasText(memberPid)) {
            return resolveMember(memberPid, tenantId).getId();
        }
        return legacyMemberId;
    }

    private Long resolveRoleId(String rolePid, Long legacyRoleId, Long tenantId) {
        if (StringUtils.hasText(rolePid)) {
            Role role = roleMapper.findByTenantIdAndPid(tenantId, rolePid);
            if (role == null) {
                throw new BusinessException("Role not found for pid: " + rolePid);
            }
            return role.getId();
        }
        return legacyRoleId;
    }

    private List<Long> resolveRolePids(List<String> rolePids, Long tenantId) {
        if (CollectionUtils.isEmpty(rolePids)) {
            return List.of();
        }
        List<Long> roleIds = new ArrayList<>();
        for (String rolePid : rolePids) {
            if (!StringUtils.hasText(rolePid)) {
                throw new BusinessException("Role PID is required");
            }
            Role role = roleMapper.findByTenantIdAndPid(tenantId, rolePid);
            if (role == null) {
                throw new BusinessException("Role not found for pid: " + rolePid);
            }
            roleIds.add(role.getId());
        }
        return roleIds;
    }

    private UserRoleResponse toResponse(UserRole userRole, Long tenantId) {
        Long effectiveTenantId = tenantId != null ? tenantId : userRole.getTenantId();
        TenantMember member = resolveMember(userRole, effectiveTenantId);
        Role role = resolveRole(userRole, effectiveTenantId);
        return UserRoleResponse.from(userRole, member, role);
    }

    private TenantMember resolveMember(UserRole userRole, Long tenantId) {
        if (userRole == null || userRole.getMemberId() == null || tenantId == null) {
            return null;
        }
        return tenantMemberMapper.findByTenantIdAndId(tenantId, userRole.getMemberId());
    }

    private Role resolveRole(UserRole userRole, Long tenantId) {
        if (userRole == null || userRole.getRoleId() == null || tenantId == null) {
            return null;
        }
        return roleMapper.findByTenantIdAndId(tenantId, userRole.getRoleId());
    }

    /**
     * Member-role bindings changed — publish so PermissionCacheEvictionListener can evict the
     * member's user-permissions cache AFTER COMMIT. Without this, granted roles take up to the
     * cache TTL (30min) to appear and — worse — revoked roles keep working for up to 30min.
     */
    private void publishMemberRoleChange(Long memberId, Long roleId, String operation) {
        if (memberId == null) {
            return;
        }
        TenantMember member = tenantMemberMapper.selectById(memberId);
        Long userId = member == null ? null : member.getUserId();
        if (userId == null) {
            return;
        }
        eventPublisher.publishEvent(new UserRoleChangedEvent(
                this, member.getTenantId(), userId, roleId, operation));
    }

    private void publishUserRoleRowsChange(List<Long> userRoleIds, String operation) {
        if (CollectionUtils.isEmpty(userRoleIds)) {
            return;
        }
        publishUserRoleRecordsChange(listByIds(userRoleIds), operation);
    }

    private void publishUserRoleRecordsChange(Collection<UserRole> userRoles, String operation) {
        if (CollectionUtils.isEmpty(userRoles)) {
            return;
        }
        Set<Long> publishedMemberIds = new LinkedHashSet<>();
        for (UserRole userRole : userRoles) {
            if (userRole == null || userRole.getMemberId() == null) {
                continue;
            }
            if (publishedMemberIds.add(userRole.getMemberId())) {
                publishMemberRoleChange(userRole.getMemberId(), userRole.getRoleId(), operation);
            }
        }
    }
}
