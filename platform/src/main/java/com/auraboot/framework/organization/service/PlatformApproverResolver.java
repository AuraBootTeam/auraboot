package com.auraboot.framework.organization.service;

import java.util.List;

/**
 * Platform-level approver resolver.
 * Resolves approvers by walking up the employee reporting chain
 * using org_employee, org_position, and org_department dynamic tables.
 */
public interface PlatformApproverResolver {

    /**
     * Resolve approvers by walking up the reporting chain until
     * an employee with the required position level (or higher) is found.
     *
     * @param employeeUserId the user ID of the requesting employee
     * @param requiredLevel  minimum position level (e.g., "manager", "director")
     * @return list of approver user IDs
     */
    List<String> resolveApprovers(String employeeUserId, String requiredLevel);

    /**
     * Resolve the direct supervisor of an employee.
     *
     * @param employeeUserId the user ID of the employee
     * @return the supervisor's user ID, or null if not found
     */
    String resolveDirectSupervisor(String employeeUserId);

    /**
     * Resolve the department manager for the given employee.
     *
     * @param employeeUserId the user ID of the employee
     * @return the department manager's user ID, or null if not found
     */
    String resolveDepartmentManager(String employeeUserId);
}
