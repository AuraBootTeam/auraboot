package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.auraboot.framework.rbac.dto.RoleMemberTreeResponse;
import com.auraboot.framework.rbac.entity.Role;
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
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Role member management service implementation.
 * Phase 2: ab_user_role uses member_id directly — no userId↔memberId bridging needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleMemberServiceImpl implements RoleMemberService {

    private static final int MEMBER_TREE_PAGE_SIZE = 500;
    private static final int MEMBER_TREE_MAX_PAGES = 10;

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

        // 3. Batch load TenantMember objects for the assigned member IDs
        List<TenantMember> assignedMembers = tenantMemberService.listByIds(memberIdToUserRole.keySet())
            .stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(TenantMember::getId))
            .collect(Collectors.toList());

        // 4. Paginate
        long total = assignedMembers.size();
        int safePageNum = PaginationSafetyUtils.pageNumber(pageNum);
        int safePageSize = PaginationSafetyUtils.pageSize(pageSize, 200);
        int fromIndex = PaginationSafetyUtils.offset(safePageNum, safePageSize, 200);
        if (fromIndex >= total) {
            return PaginationResult.empty(safePageNum, safePageSize);
        }
        int toIndex = Math.min(Math.addExact(fromIndex, safePageSize), (int) total);
        List<TenantMember> pageMembers = assignedMembers.subList(fromIndex, toIndex);

        // 5. Batch pre-fetch users and employees for this page
        Set<Long> userIds = pageMembers.stream()
            .map(TenantMember::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User u : userService.findByUserIds(userIds)) {
                userMap.put(u.getId(), u);
            }
        }

        Set<String> memberPids = pageMembers.stream()
            .map(TenantMember::getPid)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, Map<String, Object>> empMap = optionalEmployeeData(memberPids);

        // 6. Build DTOs from pre-fetched data
        List<RoleMemberDTO> dtos = pageMembers.stream()
            .map(member -> buildRoleMemberDTO(member, memberIdToUserRole.get(member.getId()), userMap, empMap))
            .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, safePageNum, safePageSize);
    }

    @Override
    @Transactional
    public void addMembers(Long roleId, List<String> memberPids) {
        addMembers(roleId, memberPids, null, null);
    }

    @Override
    @Transactional
    public void addMembers(Long roleId, List<String> memberPids,
                           LocalDate effectiveDate, LocalDate expiryDate) {
        if (CollectionUtils.isEmpty(memberPids)) {
            return;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long operatorId = MetaContext.getCurrentUserId();

        Role role = roleService.getById(roleId);
        if (role == null) {
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
            userRoleService.assignRolesToMemberByRolePids(
                    memberPid, List.of(role.getPid()),
                    effectiveDate, expiryDate, tenantId, operatorId);
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

        // 4. Batch pre-fetch users for all candidates (needed for keyword filter and DTO building)
        Set<Long> candidateUserIds = candidates.stream()
            .map(TenantMember::getUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, User> candidateUserMap = new HashMap<>();
        if (!candidateUserIds.isEmpty()) {
            for (User u : userService.findByUserIds(candidateUserIds)) {
                candidateUserMap.put(u.getId(), u);
            }
        }

        // 5. If keyword provided, filter by name/email using pre-fetched user map
        if (StringUtils.hasText(keyword)) {
            String lowerKeyword = keyword.toLowerCase();
            candidates = candidates.stream()
                .filter(m -> matchesKeyword(m, lowerKeyword, candidateUserMap))
                .collect(Collectors.toList());
        }

        // 6. Limit results
        int limit = 50;
        List<TenantMember> limited = candidates.stream()
            .limit(limit)
            .collect(Collectors.toList());

        // 7. Batch pre-fetch employees for the limited set
        Set<String> limitedMemberPids = limited.stream()
            .map(TenantMember::getPid)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, Map<String, Object>> limitedEmpMap = optionalEmployeeData(limitedMemberPids);

        // 8. Enrich with pre-fetched data
        return limited.stream()
            .map(member -> buildRoleMemberDTO(member, null, candidateUserMap, limitedEmpMap))
            .collect(Collectors.toList());
    }

    @Override
    public RoleMemberTreeResponse getMemberTree(Long roleId) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Member IDs assigned to this role -> PID set for annotation
        Set<Long> assignedMemberIds = findUserRolesByRoleId(roleId, tenantId).stream()
            .map(UserRole::getMemberId)
            .collect(Collectors.toSet());
        Set<String> assignedMemberPids = assignedMemberIds.isEmpty()
            ? Collections.emptySet()
            : tenantMemberService.listByIds(assignedMemberIds).stream()
                .filter(Objects::nonNull)
                .map(TenantMember::getPid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. All tenant employees (paged fetch, hard-capped)
        List<OrgEmployeeDTO> employees = new ArrayList<>();
        int pageNum = 1;
        long total;
        do {
            PaginationResult<OrgEmployeeDTO> page = organizationService.getEmployeesByTenant(
                pageNum, MEMBER_TREE_PAGE_SIZE, null);
            total = page.getTotal() == null ? 0 : page.getTotal();
            employees.addAll(page.getRecords());
            pageNum++;
        } while (employees.size() < total && pageNum <= MEMBER_TREE_MAX_PAGES);

        // 3. Group employees by department, annotating assignment status
        Map<String, List<RoleMemberTreeResponse.DeptUserNodeUser>> usersByDept = new HashMap<>();
        List<RoleMemberTreeResponse.DeptUserNodeUser> ungrouped = new ArrayList<>();
        for (OrgEmployeeDTO employee : employees) {
            RoleMemberTreeResponse.DeptUserNodeUser nodeUser =
                new RoleMemberTreeResponse.DeptUserNodeUser(
                    employee.userPid(), employee.memberPid(), employee.name(),
                    employee.memberPid() != null && assignedMemberPids.contains(employee.memberPid()));
            if (employee.deptPid() == null || employee.deptPid().isBlank()) {
                ungrouped.add(nodeUser);
            } else {
                usersByDept.computeIfAbsent(employee.deptPid(), k -> new ArrayList<>()).add(nodeUser);
            }
        }

        List<RoleMemberTreeResponse.DeptUserTreeNode> tree =
            toTreeNodes(organizationService.getDepartmentTree(tenantId), usersByDept);
        return new RoleMemberTreeResponse(tree, ungrouped);
    }

    private List<RoleMemberTreeResponse.DeptUserTreeNode> toTreeNodes(
            List<DepartmentTreeNode> nodes,
            Map<String, List<RoleMemberTreeResponse.DeptUserNodeUser>> usersByDept) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream()
            .map(node -> new RoleMemberTreeResponse.DeptUserTreeNode(
                node.pid(), node.name(), node.parentPid(),
                toTreeNodes(node.children(), usersByDept),
                usersByDept.getOrDefault(node.pid(), List.of())))
            .collect(Collectors.toList());
    }

    // --- private helpers ---

    private Map<String, Map<String, Object>> optionalEmployeeData(Collection<String> memberPids) {
        try {
            return organizationService.getEmployeesByMemberPids(memberPids);
        } catch (MetaServiceException exception) {
            log.warn("Role member organization enrichment is unavailable: {}", exception.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<UserRole> findUserRolesByRoleId(Long roleId, Long tenantId) {
        QueryWrapper<UserRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId)
            .eq("tenant_id", tenantId);
        return userRoleService.list(wrapper);
    }

    private RoleMemberDTO buildRoleMemberDTO(TenantMember member, UserRole userRole,
                                              Map<Long, User> userMap,
                                              Map<String, Map<String, Object>> empMap) {
        String userName = null;
        String email = null;
        String departmentName = null;
        String positionName = null;

        if (member.getUserId() != null) {
            User user = userMap.get(member.getUserId());
            if (user != null) {
                userName = user.getNickName() != null ? user.getNickName() : user.getUserName();
                email = user.getEmail();
            }
        }

        if (member.getPid() != null) {
            Map<String, Object> empRecord = empMap.get(member.getPid());
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
            userRole != null ? userRole.getCreatedAt() : null,
            userRole != null ? userRole.getEffectiveDate() : null,
            userRole != null ? userRole.getExpiryDate() : null
        );
    }

    private boolean matchesKeyword(TenantMember member, String lowerKeyword, Map<Long, User> userMap) {
        if (member.getUserId() == null) {
            return false;
        }
        User user = userMap.get(member.getUserId());
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
