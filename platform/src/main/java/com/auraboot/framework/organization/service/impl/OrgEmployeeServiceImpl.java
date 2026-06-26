package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.CreateEmployeeRequest;
import com.auraboot.framework.organization.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.dto.TransferRequest;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Employee CRUD service with bidirectional member-employee linking.
 *
 * <p>Write path:
 * <ol>
 *   <li>User/Member via system services (MyBatis)</li>
 *   <li>Employee via DynamicDataService (dynamic table mt_org_employee)</li>
 *   <li>Bidirectional link: member.employeeId ↔ employee.org_emp_member_id</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgEmployeeServiceImpl implements OrgEmployeeService {

    private static final String MODEL_EMPLOYEE = "org_employee";
    private static final String DEFAULT_PASSWORD = "AuraBoot2026!";
    private static final String TEMP_PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$";
    private static final SecureRandom RANDOM = new SecureRandom();

    // Employee field codes
    private static final String EMP_NAME = "org_emp_name";
    private static final String EMP_EMAIL = "org_emp_email";
    private static final String EMP_PHONE = "org_emp_phone";
    private static final String EMP_GENDER = "org_emp_gender";
    private static final String EMP_DEPT_ID = "org_emp_dept_id";
    private static final String EMP_POSITION_ID = "org_emp_position_id";
    private static final String EMP_STATUS = "org_emp_status";
    private static final String EMP_TYPE = "org_emp_type";
    private static final String EMP_MEMBER_ID = "org_emp_member_id";
    private static final String EMP_USER_ID = "org_emp_user_id";
    private static final String EMP_REPORT_TO = "org_emp_report_to";

    private final DynamicDataService dynamicDataService;
    private final UserService userService;
    private final TenantMemberService tenantMemberService;
    private final OrganizationServiceImpl organizationService;
    private final RoleService roleService;
    private final PasswordPolicyService passwordPolicyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public OrgEmployeeDTO createWithUser(CreateEmployeeRequest request) {
        log.info("Creating employee with user: email={}", request.getEmail());

        // 1. Create user
        User user = userService.signUp(request.getEmail(), DEFAULT_PASSWORD, request.getName());

        // 2. Create tenant member
        Long tenantId = MetaContext.getCurrentTenantId();
        TenantMember member = tenantMemberService.addMember(
            user.getId(), tenantId, StatusConstants.ACTIVE);

        // 3. Create employee dynamic record
        Map<String, Object> empData = buildEmployeeData(request);
        empData.put(EMP_USER_ID, user.getPid());
        empData.put(EMP_MEMBER_ID, member.getPid());
        empData.put(EMP_STATUS, StatusConstants.ACTIVE);
        empData.put(EMP_TYPE, "human");

        Map<String, Object> created = dynamicDataService.create(MODEL_EMPLOYEE, empData);
        String employeePid = (String) created.get("pid");

        // 4. Write back: set member.employeeId = employee.id
        Long employeeId = extractId(created);
        member.setEmployeeId(employeeId);
        tenantMemberService.updateMember(member);

        log.info("Employee created: pid={}, memberPid={}, userId={}",
            employeePid, member.getPid(), user.getId());

        return organizationService.toEmployeeDTO(created);
    }

    @Override
    @Transactional
    public EmployeeAccountProvisionResponse openAccount(String employeePid) {
        log.info("Opening account for employee: {}", employeePid);

        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            throw new BusinessException("Employee not found: " + employeePid);
        }
        if (hasText(value(employee.get(EMP_MEMBER_ID)))) {
            throw new BusinessException("Employee is already linked to a tenant member");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new BusinessException("Tenant context is required");
        }

        String displayName = firstNonBlank(value(employee.get(EMP_NAME)), employeePid);
        String email = normalizeBlankToNull(value(employee.get(EMP_EMAIL)));
        String userName = generatedUserName(employeePid);
        String existingUserPid = normalizeBlankToNull(value(employee.get(EMP_USER_ID)));

        User user = resolveExistingUser(existingUserPid, email, userName);
        boolean createdUser = false;
        String temporaryPassword = null;
        if (user == null) {
            temporaryPassword = generateTemporaryPassword(12);
            user = userService.signUp(email, temporaryPassword, displayName, userName);
            createdUser = true;
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        boolean createdMember = false;
        if (member == null) {
            member = tenantMemberService.addMember(user.getId(), tenantId, StatusConstants.ACTIVE);
            createdMember = true;
        }
        if (member.getEmployeeId() != null) {
            throw new BusinessException("Tenant member is already linked to another employee");
        }

        Long employeeId = extractId(employee);
        member.setEmployeeId(employeeId);
        tenantMemberService.updateMember(member);

        Map<String, Object> updateData = new HashMap<>();
        updateData.put(EMP_USER_ID, user.getPid());
        updateData.put(EMP_MEMBER_ID, member.getPid());
        updateData.put(EMP_STATUS, StatusConstants.ACTIVE);
        dynamicDataService.update(MODEL_EMPLOYEE, employeePid, updateData);

        List<String> assignedRoles = createdMember ? assignDefaultRole(member, tenantId) : List.of();

        log.info("Employee account opened: employeePid={}, userPid={}, memberPid={}, createdUser={}, createdMember={}",
                employeePid, user.getPid(), member.getPid(), createdUser, createdMember);

        return EmployeeAccountProvisionResponse.builder()
                .employeePid(employeePid)
                .userPid(user.getPid())
                .memberPid(member.getPid())
                .email(user.getEmail())
                .userName(user.getUserName())
                .displayName(displayName)
                .createdUser(createdUser)
                .createdMember(createdMember)
                .adminManaged(true)
                .temporaryPassword(temporaryPassword)
                .assignedRoles(assignedRoles)
                .build();
    }

    @Override
    @Transactional
    public OrgEmployeeDTO linkMember(LinkMemberRequest request) {
        log.info("Linking member {} to new employee", request.getMemberPid());

        // 1. Find existing member
        TenantMember member = tenantMemberService.findByPid(request.getMemberPid());
        if (member == null) {
            throw new BusinessException("Member not found: " + request.getMemberPid());
        }

        if (member.getEmployeeId() != null) {
            throw new BusinessException("Member is already linked to an employee");
        }

        // 2. Resolve user for name/email
        User user = userService.findByUserId(member.getUserId());
        if (user == null) {
            throw new BusinessException("User not found for member: " + request.getMemberPid());
        }

        // 3. Create employee record
        Map<String, Object> empData = new HashMap<>();
        empData.put(EMP_NAME, user.getNickName() != null ? user.getNickName() : user.getUserName());
        empData.put(EMP_EMAIL, user.getEmail());
        empData.put(EMP_DEPT_ID, request.getDeptPid());
        empData.put(EMP_MEMBER_ID, member.getPid());
        empData.put(EMP_USER_ID, user.getPid());
        empData.put(EMP_STATUS, StatusConstants.ACTIVE);
        empData.put(EMP_TYPE, "human");

        if (request.getPositionPid() != null) {
            empData.put(EMP_POSITION_ID, request.getPositionPid());
        }

        Map<String, Object> created = dynamicDataService.create(MODEL_EMPLOYEE, empData);

        // 4. Write back: member.employeeId = employee.id
        Long employeeId = extractId(created);
        member.setEmployeeId(employeeId);
        tenantMemberService.updateMember(member);

        log.info("Member linked to employee: memberPid={}, employeePid={}",
            member.getPid(), created.get("pid"));

        return organizationService.toEmployeeDTO(created);
    }

    @Override
    @Transactional
    public void unlinkMember(String employeePid) {
        log.info("Unlinking member from employee: {}", employeePid);

        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            throw new BusinessException("Employee not found: " + employeePid);
        }

        String memberPid = (String) employee.get(EMP_MEMBER_ID);

        // Clear employee side — only clear member link, keep the optional user link.
        // Use direct SQL because DynamicDataService.update() ignores null values
        jdbcTemplate.update(
                "UPDATE mt_org_employee SET org_emp_member_id = NULL, updated_at = NOW() WHERE pid = ?",
                employeePid);

        // Clear member side — use direct SQL to avoid tenant-scope issues
        // (member may belong to a different tenant than current context)
        if (memberPid != null) {
            jdbcTemplate.update(
                    "UPDATE ab_tenant_member SET employee_id = NULL, updated_at = NOW() WHERE pid = ?",
                    memberPid);
        }

        log.info("Unlinked member from employee: employeePid={}, memberPid={}", employeePid, memberPid);
    }

    @Override
    @Transactional
    public void transfer(String employeePid, TransferRequest request) {
        log.info("Transferring employee {} to dept {}", employeePid, request.getNewDeptPid());

        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            throw new BusinessException("Employee not found: " + employeePid);
        }

        Map<String, Object> updateData = new HashMap<>();
        updateData.put(EMP_DEPT_ID, request.getNewDeptPid());

        if (request.getNewPositionPid() != null) {
            updateData.put(EMP_POSITION_ID, request.getNewPositionPid());
        }

        dynamicDataService.update(MODEL_EMPLOYEE, employeePid, updateData);
        log.info("Employee transferred: pid={}", employeePid);
    }

    @Override
    @Transactional
    public void batchTransfer(List<String> employeePids, TransferRequest request) {
        log.info("Batch transferring {} employees to dept {}", employeePids.size(), request.getNewDeptPid());
        for (String pid : employeePids) {
            transfer(pid, request);
        }
    }

    // ======================== Private helpers ========================

    private Map<String, Object> buildEmployeeData(CreateEmployeeRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put(EMP_NAME, request.getName());
        data.put(EMP_EMAIL, request.getEmail());
        data.put(EMP_PHONE, request.getPhone());
        data.put(EMP_DEPT_ID, request.getDeptPid());

        if (request.getGender() != null) {
            data.put(EMP_GENDER, request.getGender());
        }
        if (request.getPositionPid() != null) {
            data.put(EMP_POSITION_ID, request.getPositionPid());
        }
        if (request.getManagerPid() != null) {
            data.put(EMP_REPORT_TO, request.getManagerPid());
        }
        return data;
    }

    private Long extractId(Map<String, Object> record) {
        Object id = record.get("id");
        if (id instanceof Long longId) {
            return longId;
        }
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String strId) {
            return Long.parseLong(strId);
        }
        throw new BusinessException("Cannot extract employee ID from created record");
    }

    private User resolveExistingUser(String userPid, String email, String userName) {
        if (hasText(userPid)) {
            User byPid = userService.findByPid(userPid);
            if (byPid == null) {
                throw new BusinessException("Linked user not found: " + userPid);
            }
            return byPid;
        }
        if (hasText(email)) {
            User byEmail = userService.findByEmail(email);
            if (byEmail != null) {
                return byEmail;
            }
        }
        if (hasText(userName)) {
            return userService.findByUserName(userName);
        }
        return null;
    }

    private List<String> assignDefaultRole(TenantMember member, Long tenantId) {
        List<String> assigned = new ArrayList<>();
        Role defaultRole = roleService.findDefaultRole(tenantId);
        if (defaultRole != null) {
            roleService.assignRoleToMember(member.getId(), defaultRole.getId(), tenantId);
            assigned.add(defaultRole.getCode());
        }
        return assigned;
    }

    private String generateTemporaryPassword(int length) {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
            }
            String candidate = sb.toString();
            if (passwordPolicyService.validate(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException("Unable to generate a temporary password that satisfies policy");
    }

    private String generatedUserName(String employeePid) {
        String suffix = employeePid == null ? "employee" : employeePid.replaceAll("[^A-Za-z0-9]", "");
        if (suffix.length() > 40) {
            suffix = suffix.substring(0, 40);
        }
        return "emp_" + suffix;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : fallback;
    }

    private String normalizeBlankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(Object raw) {
        return raw == null ? null : raw.toString();
    }
}
