package com.auraboot.framework.organization.service;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;

import java.util.List;
import java.util.Map;

/**
 * Organization Engine interface — reads org structure (departments, employees).
 * All queries go through DynamicDataService since org tables are dynamic (mt_*).
 */
public interface OrganizationService {

    /**
     * Build the full department tree for a tenant.
     *
     * @param tenantId tenant ID
     * @return root-level department nodes with nested children
     */
    List<DepartmentTreeNode> getDepartmentTree(Long tenantId);

    /**
     * Get a department and all its descendant department PIDs (recursive).
     *
     * @param departmentPid the root department PID
     * @return list of department PIDs including the given one and all descendants
     */
    List<String> getDeptAndSubPids(String departmentPid);

    /**
     * Get the employee record linked to a tenant member.
     *
     * @param memberPid tenant member PID
     * @return employee dynamic record, or null if not linked
     */
    Map<String, Object> getEmployeeByMemberPid(String memberPid);

    /**
     * Get the manager (report-to) of an employee.
     *
     * @param employeePid employee PID
     * @return manager's employee dynamic record, or null if no manager
     */
    Map<String, Object> getManager(String employeePid);

    /**
     * Query employees by department with optional recursive sub-department inclusion.
     *
     * @param deptPid   department PID
     * @param recursive whether to include sub-departments
     * @param pageNum   page number (1-based)
     * @param pageSize  page size
     * @param keyword   optional search keyword (name/phone)
     * @return paginated employee DTOs
     */
    PaginationResult<OrgEmployeeDTO> getEmployeesByDept(
        String deptPid, boolean recursive, int pageNum, int pageSize, String keyword);

    /**
     * Query all employees in the current tenant.
     */
    PaginationResult<OrgEmployeeDTO> getEmployeesByTenant(int pageNum, int pageSize, String keyword);
}
