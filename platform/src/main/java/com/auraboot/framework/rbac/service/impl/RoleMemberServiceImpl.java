package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleMemberService;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Role member management service implementation.
 * Phase 1: ab_user_role uses user_id, so we convert between member IDs and user IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleMemberServiceImpl implements RoleMemberService {

    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final TenantMemberService tenantMemberService;
    private final UserService userService;
    private final OrganizationService organizationService;

    @Override
    public PaginationResult<RoleMemberDTO> getMembers(Long roleId, int pageNum, int pageSize) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Find all user_ids assigned to this role in current tenant
        List<UserRole> userRoles = findUserRolesByRoleId(roleId, tenantId);
        if (userRoles.isEmpty()) {
            return PaginationResult.empty(pageNum, pageSize);
        }

        // 2. Build userId -> UserRole map (for assignedAt)
        Map<Long, UserRole> userIdToUserRole = userRoles.stream()
            .collect(Collectors.toMap(UserRole::getUserId, ur -> ur, (a, b) -> a));

        // 3. Find corresponding tenant members
        List<TenantMember> allMembers = tenantMemberService.findByTenantId(tenantId);
        List<TenantMember> assignedMembers = allMembers.stream()
            .filter(m -> m.getUserId() != null && userIdToUserRole.containsKey(m.getUserId()))
            .sorted(Comparator.comparing(TenantMember::getId))
            .collect(Collectors.toList());

        // 4. Paginate
        long total = assignedMembers.size();
        int fromIndex = (pageNum - 1) * pageSize;
        if (fromIndex >= total) {
            return PaginationResult.empty(pageNum, pageSize);
        }
        int toIndex = Math.min(fromIndex + pageSize, (int) total);
        List<TenantMember> pageMembers = assignedMembers.subList(fromIndex, toIndex);

        // 5. Enrich with user + employee data
        List<RoleMemberDTO> dtos = pageMembers.stream()
            .map(member -> buildRoleMemberDTO(member, userIdToUserRole.get(member.getUserId())))
            .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, pageNum, pageSize);
    }

    @Override
    @Transactional
    public void addMembers(Long roleId, List<Long> memberIds) {
        if (CollectionUtils.isEmpty(memberIds)) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long operatorId = MetaContext.getCurrentUserId();

        // Validate role exists
        if (roleService.getById(roleId) == null) {
            throw new BusinessException("Role not found: " + roleId);
        }

        // Convert member IDs to user IDs and assign
        for (Long memberId : memberIds) {
            TenantMember member = tenantMemberService.getById(memberId);
            if (member == null || member.getUserId() == null) {
                log.warn("Skipping invalid member ID {} — member not found or no userId", memberId);
                continue;
            }
            // Verify member belongs to current tenant
            if (!tenantId.equals(member.getTenantId())) {
                log.warn("Skipping member ID {} — belongs to different tenant", memberId);
                continue;
            }
            userRoleService.assignRolesToUser(member.getUserId(), List.of(roleId), tenantId, operatorId);
        }
    }

    @Override
    @Transactional
    public void removeMembers(Long roleId, List<Long> memberIds) {
        if (CollectionUtils.isEmpty(memberIds)) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();

        for (Long memberId : memberIds) {
            TenantMember member = tenantMemberService.getById(memberId);
            if (member == null || member.getUserId() == null) {
                continue;
            }
            if (!tenantId.equals(member.getTenantId())) {
                continue;
            }
            userRoleService.removeRolesFromUser(member.getUserId(), List.of(roleId), tenantId);
        }
    }

    @Override
    public List<RoleMemberDTO> getCandidates(Long roleId, String keyword) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Get all user IDs already assigned to this role
        List<UserRole> existingAssignments = findUserRolesByRoleId(roleId, tenantId);
        Set<Long> assignedUserIds = existingAssignments.stream()
            .map(UserRole::getUserId)
            .collect(Collectors.toSet());

        // 2. Get all active tenant members
        List<TenantMember> allMembers = tenantMemberService.findByTenantId(tenantId);

        // 3. Filter out already-assigned and inactive members
        List<TenantMember> candidates = allMembers.stream()
            .filter(m -> m.getUserId() != null)
            .filter(m -> !assignedUserIds.contains(m.getUserId()))
            .filter(m -> !Boolean.TRUE.equals(m.getDeletedFlag()))
            .collect(Collectors.toList());

        // 4. If keyword provided, filter by name/email
        if (StringUtils.hasText(keyword)) {
            String lowerKeyword = keyword.toLowerCase();
            candidates = candidates.stream()
                .filter(m -> matchesKeyword(m, lowerKeyword))
                .collect(Collectors.toList());
        }

        // 5. Limit results (avoid returning thousands)
        int limit = 50;
        List<TenantMember> limited = candidates.stream()
            .limit(limit)
            .collect(Collectors.toList());

        // 6. Enrich with user + employee data
        return limited.stream()
            .map(member -> buildRoleMemberDTO(member, null))
            .collect(Collectors.toList());
    }

    // --- private helpers ---

    /**
     * Find all UserRole entries for a given roleId in the current tenant.
     */
    private List<UserRole> findUserRolesByRoleId(Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId)
            .eq("tenant_id", tenantId);
        return userRoleService.list(wrapper);
    }

    /**
     * Build a RoleMemberDTO from a TenantMember, enriching with user and employee data.
     */
    private RoleMemberDTO buildRoleMemberDTO(TenantMember member, UserRole userRole) {
        String userName = null;
        String email = null;
        String departmentName = null;
        String positionName = null;

        // Get user info
        if (member.getUserId() != null) {
            User user = userService.findByUserId(member.getUserId());
            if (user != null) {
                userName = user.getNickName() != null ? user.getNickName() : user.getUserName();
                email = user.getEmail();
            }
        }

        // Get employee info (department, position) via OrganizationService
        if (member.getPid() != null) {
            Map<String, Object> empRecord = organizationService.getEmployeeByMemberPid(member.getPid());
            if (empRecord != null) {
                // Override name from employee record if available
                Object empName = empRecord.get("org_emp_name");
                if (empName != null && StringUtils.hasText(empName.toString())) {
                    userName = empName.toString();
                }

                departmentName = resolveRefName(empRecord, "org_emp_dept_id");
                positionName = resolveRefName(empRecord, "org_emp_position_id");
            }
        }

        return new RoleMemberDTO(
            member.getId(),
            member.getPid(),
            userName,
            email,
            departmentName,
            positionName,
            userRole != null ? userRole.getCreatedAt() : null
        );
    }

    /**
     * Check if a member matches the keyword by user name or email.
     */
    private boolean matchesKeyword(TenantMember member, String lowerKeyword) {
        if (member.getUserId() == null) {
            return false;
        }
        User user = userService.findByUserId(member.getUserId());
        if (user == null) {
            return false;
        }
        String name = user.getNickName() != null ? user.getNickName() : user.getUserName();
        if (name != null && name.toLowerCase().contains(lowerKeyword)) {
            return true;
        }
        if (user.getEmail() != null && user.getEmail().toLowerCase().contains(lowerKeyword)) {
            return true;
        }
        return false;
    }

    /**
     * Resolve a reference field's display name from a dynamic record.
     * Reference fields in dynamic records store the PID, but may also have
     * a resolved display value under "{fieldCode}_display" or similar patterns.
     */
    private String resolveRefName(Map<String, Object> record, String fieldCode) {
        // Try common display-name patterns for reference fields
        Object displayValue = record.get(fieldCode + "_display");
        if (displayValue != null && StringUtils.hasText(displayValue.toString())) {
            return displayValue.toString();
        }
        // Fallback: return the raw value (usually a PID)
        Object rawValue = record.get(fieldCode);
        return rawValue != null ? rawValue.toString() : null;
    }
}
