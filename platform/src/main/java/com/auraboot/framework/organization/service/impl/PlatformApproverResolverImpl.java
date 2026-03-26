package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.service.PlatformApproverResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Platform-level approver resolver implementation.
 * Uses DynamicDataService to query org_employee, org_position,
 * and org_department dynamic tables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformApproverResolverImpl implements PlatformApproverResolver {

    private static final List<String> LEVEL_ORDER = List.of(
        "staff", "supervisor", "manager", "director", "VP", "ceo"
    );

    private final DynamicDataService dynamicDataService;

    @Override
    public List<String> resolveApprovers(String employeeUserId, String requiredLevel) {
        Map<String, Object> employee = findEmployeeByUserId(employeeUserId);
        if (employee == null) {
            throw new RuntimeException("Employee not found for user: " + employeeUserId);
        }

        int requiredIdx = LEVEL_ORDER.indexOf(requiredLevel);
        if (requiredIdx < 0) {
            throw new RuntimeException("Unknown position level: " + requiredLevel);
        }

        Set<String> visited = new HashSet<>();
        String currentEmpId = (String) employee.get("org_emp_report_to");

        while (currentEmpId != null && !visited.contains(currentEmpId)) {
            visited.add(currentEmpId);
            Map<String, Object> superior = dynamicDataService.getById("org_employee", currentEmpId);
            if (superior == null) break;

            String positionId = (String) superior.get("org_emp_position_id");
            if (positionId != null) {
                Map<String, Object> position = dynamicDataService.getById("org_position", positionId);
                if (position != null) {
                    String level = (String) position.get("org_pos_level");
                    int levelIdx = LEVEL_ORDER.indexOf(level);
                    if (levelIdx >= requiredIdx) {
                        return List.of((String) superior.get("org_emp_user_id"));
                    }
                }
            }
            currentEmpId = (String) superior.get("org_emp_report_to");
        }

        // Fallback: department manager
        String deptId = (String) employee.get("org_emp_dept_id");
        if (deptId != null) {
            Map<String, Object> dept = dynamicDataService.getById("org_department", deptId);
            if (dept != null) {
                String managerId = (String) dept.get("org_dept_manager_id");
                if (managerId != null) {
                    Map<String, Object> manager = dynamicDataService.getById("org_employee", managerId);
                    if (manager != null) {
                        return List.of((String) manager.get("org_emp_user_id"));
                    }
                }
            }
        }

        return List.of();
    }

    @Override
    public String resolveDirectSupervisor(String employeeUserId) {
        Map<String, Object> employee = findEmployeeByUserId(employeeUserId);
        if (employee == null) {
            throw new RuntimeException("Employee not found for user: " + employeeUserId);
        }

        String reportTo = (String) employee.get("org_emp_report_to");
        if (reportTo != null) {
            Map<String, Object> superior = dynamicDataService.getById("org_employee", reportTo);
            if (superior != null) {
                return (String) superior.get("org_emp_user_id");
            }
        }

        // Fallback: department manager
        return resolveDeptManagerForEmployee(employee);
    }

    @Override
    public String resolveDepartmentManager(String employeeUserId) {
        Map<String, Object> employee = findEmployeeByUserId(employeeUserId);
        if (employee == null) return null;

        return resolveDeptManagerForEmployee(employee);
    }

    private String resolveDeptManagerForEmployee(Map<String, Object> employee) {
        String deptId = (String) employee.get("org_emp_dept_id");
        if (deptId == null) return null;

        Map<String, Object> dept = dynamicDataService.getById("org_department", deptId);
        if (dept == null) return null;

        String managerId = (String) dept.get("org_dept_manager_id");
        if (managerId == null) return null;

        Map<String, Object> manager = dynamicDataService.getById("org_employee", managerId);
        if (manager == null) return null;

        return (String) manager.get("org_emp_user_id");
    }

    private Map<String, Object> findEmployeeByUserId(String userId) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(1)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName("org_emp_user_id")
                    .operator(QueryCondition.Operator.EQ)
                    .value(userId)
                    .build()
            ))
            .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list("org_employee", request);
        List<Map<String, Object>> records = result.getRecords();
        return (records == null || records.isEmpty()) ? null : records.get(0);
    }
}
