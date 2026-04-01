package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.CreateEmployeeRequest;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.dto.TransferRequest;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        empData.put(EMP_TYPE, "full_time");

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
        empData.put(EMP_TYPE, "full_time");

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

        // Clear employee side
        Map<String, Object> updateData = new HashMap<>();
        updateData.put(EMP_MEMBER_ID, null);
        updateData.put(EMP_USER_ID, null);
        dynamicDataService.update(MODEL_EMPLOYEE, employeePid, updateData);

        // Clear member side
        if (memberPid != null) {
            TenantMember member = tenantMemberService.findByPid(memberPid);
            if (member != null) {
                member.setEmployeeId(null);
                tenantMemberService.updateMember(member);
            }
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
}
