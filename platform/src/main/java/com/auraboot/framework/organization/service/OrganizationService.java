package com.auraboot.framework.organization.service;

import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;

import java.util.Collection;
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
     * Batch find employees by member PIDs.
     *
     * @param memberPids collection of member PIDs
     * @return map of memberPid -> employee record
     */
    Map<String, Map<String, Object>> getEmployeesByMemberPids(Collection<String> memberPids);

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

    // ======================== BPM / Approval Extension Methods ========================

    /**
     * Walk up the report-to chain from the given employee, collecting managers.
     *
     * @param employeePid starting employee PID
     * @param level       max number of managers to collect
     * @return ordered list of manager records (direct manager first)
     */
    List<Map<String, Object>> getManagersUpToLevel(String employeePid, int level);

    /**
     * Get the designated leader/manager of a department.
     *
     * @param deptPid department PID
     * @return the manager employee record, or null if department has no manager
     */
    Map<String, Object> getDeptLeader(String deptPid);

    /**
     * Find all employees whose linked tenant member has the given role code.
     *
     * @param tenantId tenant ID
     * @param roleCode role code to match
     * @return list of employee dynamic records
     */
    List<Map<String, Object>> getEmployeesByRole(Long tenantId, String roleCode);

    /**
     * Find all employees holding the given position (by position code).
     *
     * @param tenantId     tenant ID (unused — tenant isolation is automatic)
     * @param positionCode position code to match
     * @return list of employee dynamic records
     */
    List<Map<String, Object>> getEmployeesByPosition(Long tenantId, String positionCode);

    /**
     * Get all employees in the same department as the given employee (excluding self).
     *
     * @param employeePid employee PID
     * @return list of peer employee records
     */
    List<Map<String, Object>> getPeers(String employeePid);

    /**
     * Get all subordinates of an employee (recursive, via report-to chain).
     *
     * @param employeePid employee PID
     * @return list of all subordinate employee records (breadth-first)
     */
    List<Map<String, Object>> getSubordinates(String employeePid);

    /**
     * Get the organizational path from the employee's department up to the root.
     *
     * @param employeePid employee PID
     * @return ordered list of department records [current_dept, parent_dept, ..., root_dept]
     */
    List<Map<String, Object>> getOrgPath(String employeePid);
}
