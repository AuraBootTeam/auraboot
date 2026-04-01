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
 * Phase 2: ab_user_role uses member_id directly — no userId↔memberId bridging needed.
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

        // 1. Find all UserRole entries for this role in the current tenant
        List<UserRole> userRoles = findUserRolesByRoleId(roleId, tenantId);
        if (userRoles.isEmpty()) {
            return PaginationResult.empty(pageNum, pageSize);
        }

        // 2. Build memberId -> UserRole map
        Map<Long, UserRole> memberIdToUserRole = userRoles.stream()
            .collect(Collectors.toMap(UserRole::getMemberId, ur -> ur, (a, b) -> a));

        // 3. Load TenantMember objects for the assigned member IDs
        List<TenantMember> assignedMembers = memberIdToUserRole.keySet().stream()
            .map(tenantMemberService::getById)
            .filter(Objects::nonNull)
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
            .map(member -> buildRoleMemberDTO(member, memberIdToUserRole.get(member.getId())))
            .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, pageNum, pageSize);
    }

    @Override
    @Transactional
    public void addMembers(Long roleId, List<String> memberPids) {
        if (CollectionUtils.isEmpty(memberPids)) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long operatorId = MetaContext.getCurrentUserId();

        if (roleService.getById(roleId) == null) {
            throw new BusinessException("Role not found: " + roleId);
        }

        for (String memberPid : memberPids) {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                log.warn("Skipping invalid member PID {} — member not found", memberPid);
                continue;
            }
            if (!tenantId.equals(member.getTenantId())) {
                log.warn("Skipping member PID {} — belongs to different tenant", memberPid);
                continue;
            }
            // Phase 2: directly use member.getId() as the subject
            userRoleService.assignRolesToMember(member.getId(), List.of(roleId), tenantId, operatorId);
        }
    }

    @Override
    @Transactional
    public void removeMembers(Long roleId, List<String> memberPids) {
        if (CollectionUtils.isEmpty(memberPids)) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();

        for (String memberPid : memberPids) {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member == null) {
                continue;
            }
            if (!tenantId.equals(member.getTenantId())) {
                continue;
            }
            userRoleService.removeRolesFromMember(member.getId(), List.of(roleId), tenantId);
        }
    }

    @Override
    public List<RoleMemberDTO> getCandidates(Long roleId, String keyword) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Get all member IDs already assigned to this role
        List<UserRole> existingAssignments = findUserRolesByRoleId(roleId, tenantId);
        Set<Long> assignedMemberIds = existingAssignments.stream()
            .map(UserRole::getMemberId)
            .collect(Collectors.toSet());

        // 2. Get all active tenant members
        List<TenantMember> allMembers = tenantMemberService.findByTenantId(tenantId);

        // 3. Filter out already-assigned and inactive members
        List<TenantMember> candidates = allMembers.stream()
            .filter(m -> !assignedMemberIds.contains(m.getId()))
            .filter(m -> !Boolean.TRUE.equals(m.getDeletedFlag()))
            .collect(Collectors.toList());

        // 4. If keyword provided, filter by name/email
        if (StringUtils.hasText(keyword)) {
            String lowerKeyword = keyword.toLowerCase();
            candidates = candidates.stream()
                .filter(m -> matchesKeyword(m, lowerKeyword))
                .collect(Collectors.toList());
        }

        // 5. Limit results
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

    private List<UserRole> findUserRolesByRoleId(Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId)
            .eq("tenant_id", tenantId);
        return userRoleService.list(wrapper);
    }

    private RoleMemberDTO buildRoleMemberDTO(TenantMember member, UserRole userRole) {
        String userName = null;
        String email = null;
        String departmentName = null;
        String positionName = null;

        if (member.getUserId() != null) {
            User user = userService.findByUserId(member.getUserId());
            if (user != null) {
                userName = user.getNickName() != null ? user.getNickName() : user.getUserName();
                email = user.getEmail();
            }
        }

        if (member.getPid() != null) {
            Map<String, Object> empRecord = organizationService.getEmployeeByMemberPid(member.getPid());
            if (empRecord != null) {
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

    private String resolveRefName(Map<String, Object> record, String fieldCode) {
        Object displayValue = record.get(fieldCode + "_display");
        if (displayValue != null && StringUtils.hasText(displayValue.toString())) {
            return displayValue.toString();
        }
        Object rawValue = record.get(fieldCode);
        return rawValue != null ? rawValue.toString() : null;
    }
}
