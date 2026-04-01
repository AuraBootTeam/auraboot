package com.auraboot.framework.organization.service;

import com.auraboot.framework.organization.dto.CreateEmployeeRequest;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.dto.TransferRequest;

import java.util.List;

/**
 * Employee CRUD service with bidirectional member-employee linking.
 *
 * <p>Manages the lifecycle link between {@code ab_tenant_member} (Access domain)
 * and {@code mt_org_employee} (HR domain):
 * <ul>
 *   <li>{@code ab_tenant_member.employee_id} ← employee record ID</li>
 *   <li>{@code mt_org_employee.org_emp_member_id} ← member PID</li>
 * </ul>
 */
public interface OrgEmployeeService {

    /**
     * One-stop creation: creates user + member + employee with bidirectional linking.
     *
     * @param request employee creation details
     * @return the created employee DTO
     */
    OrgEmployeeDTO createWithUser(CreateEmployeeRequest request);

    /**
     * Link an existing tenant member to a new employee record.
     *
     * @param request link details (member PID + department)
     * @return the created employee DTO
     */
    OrgEmployeeDTO linkMember(LinkMemberRequest request);

    /**
     * Clear bidirectional link between member and employee.
     *
     * @param employeePid employee PID to unlink
     */
    void unlinkMember(String employeePid);

    /**
     * Transfer an employee to a new department and/or position.
     *
     * @param employeePid employee PID
     * @param request     transfer details
     */
    void transfer(String employeePid, TransferRequest request);

    /**
     * Batch transfer multiple employees.
     *
     * @param employeePids list of employee PIDs
     * @param request      transfer details
     */
    void batchTransfer(List<String> employeePids, TransferRequest request);
}
