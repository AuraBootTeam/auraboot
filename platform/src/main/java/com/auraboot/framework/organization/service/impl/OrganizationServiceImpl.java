package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Collection;

/**
 * Organization Engine implementation.
 * Queries dynamic tables (mt_org_department, mt_org_employee) via DynamicDataService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private static final String MODEL_DEPARTMENT = "org_department";
    private static final String MODEL_EMPLOYEE = "org_employee";
    private static final String MODEL_POSITION = "org_position";

    // Department field codes
    private static final String DEPT_NAME = "org_dept_name";
    private static final String DEPT_PARENT_ID = "org_dept_parent_id";
    private static final String DEPT_MANAGER_ID = "org_dept_manager_id";

    // Employee field codes
    private static final String EMP_NAME = "org_emp_name";
    private static final String EMP_CODE = "org_emp_code";
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

    // Position field codes
    private static final String POS_NAME = "org_pos_name";
    private static final String POS_CODE = "org_pos_code";

    private static final int MAX_SUBORDINATE_DEPTH = 10;
    private static final int MAX_QUERY_PAGE_SIZE = 500;

    private final DynamicDataService dynamicDataService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final TenantMemberService tenantMemberService;

    @Override
    public List<DepartmentTreeNode> getDepartmentTree(Long tenantId) {
        // Query all departments (no filter — tenant isolation is automatic via TenantLineInterceptor)
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(500)
            .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_DEPARTMENT, request);
        List<Map<String, Object>> departments = result.getRecords();

        if (departments == null || departments.isEmpty()) {
            return List.of();
        }

        // Count employees per department
        Map<String, Integer> empCountByDept = countEmployeesByDepartment();

        // Build tree
        Map<String, List<Map<String, Object>>> childrenMap = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();

        for (Map<String, Object> dept : departments) {
            String parentPid = asString(dept.get(DEPT_PARENT_ID));
            if (parentPid == null || parentPid.isBlank()) {
                roots.add(dept);
            } else {
                childrenMap.computeIfAbsent(parentPid, k -> new ArrayList<>()).add(dept);
            }
        }

        return roots.stream()
            .map(dept -> buildTreeNode(dept, childrenMap, empCountByDept))
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getDeptAndSubPids(String departmentPid) {
        List<DepartmentTreeNode> tree = getDepartmentTree(null);
        List<String> result = new ArrayList<>();
        collectDescendants(tree, departmentPid, result);
        return result;
    }

    @Override
    public Map<String, Object> getEmployeeByMemberPid(String memberPid) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(1)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName(EMP_MEMBER_ID)
                    .operator(QueryCondition.Operator.EQ)
                    .value(memberPid)
                    .build()
            ))
            .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
        List<Map<String, Object>> records = result.getRecords();
        return (records == null || records.isEmpty()) ? null : records.get(0);
    }

    @Override
    public Map<String, Map<String, Object>> getEmployeesByMemberPids(Collection<String> memberPids) {
        if (memberPids == null || memberPids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object> memberPidValues = new ArrayList<>(memberPids);
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(memberPids.size())
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName(EMP_MEMBER_ID)
                    .operator(QueryCondition.Operator.IN)
                    .values(memberPidValues)
                    .build()
            ))
            .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
        List<Map<String, Object>> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> record : records) {
            Object memberIdValue = record.get(EMP_MEMBER_ID);
            if (memberIdValue != null) {
                map.put(memberIdValue.toString(), record);
            }
        }
        return map;
    }

    @Override
    public Map<String, Object> getManager(String employeePid) {
        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            return null;
        }

        String reportTo = asString(employee.get(EMP_REPORT_TO));
        if (reportTo != null) {
            return dynamicDataService.getById(MODEL_EMPLOYEE, reportTo);
        }

        // Fallback: department manager (skip if the employee IS the department manager)
        String deptId = asString(employee.get(EMP_DEPT_ID));
        if (deptId != null) {
            Map<String, Object> dept = dynamicDataService.getById(MODEL_DEPARTMENT, deptId);
            if (dept != null) {
                String managerId = asString(dept.get(DEPT_MANAGER_ID));
                if (managerId != null && !managerId.equals(employeePid)) {
                    return dynamicDataService.getById(MODEL_EMPLOYEE, managerId);
                }
            }
        }
        return null;
    }

    @Override
    public PaginationResult<OrgEmployeeDTO> getEmployeesByDept(
            String deptPid, boolean recursive, int pageNum, int pageSize, String keyword) {

        List<QueryCondition> conditions = new ArrayList<>();

        if (recursive) {
            List<String> deptPids = getDeptAndSubPids(deptPid);
            if (deptPids.isEmpty()) {
                return PaginationResult.empty(pageNum, pageSize);
            }
            conditions.add(QueryCondition.builder()
                .fieldName(EMP_DEPT_ID)
                .operator(QueryCondition.Operator.IN)
                .values(new ArrayList<>(deptPids))
                .build());
        } else {
            conditions.add(QueryCondition.builder()
                .fieldName(EMP_DEPT_ID)
                .operator(QueryCondition.Operator.EQ)
                .value(deptPid)
                .build());
        }

        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(pageNum)
            .pageSize(pageSize)
            .conditions(conditions)
            .keyword(keyword)
            .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);

        // Convert to DTOs
        List<OrgEmployeeDTO> dtos = result.getRecords().stream()
            .map(this::toEmployeeDTO)
            .collect(Collectors.toList());

        return PaginationResult.of(dtos, result.getTotal(), result.getPage(), result.getPageSize());
    }

    @Override
    public PaginationResult<OrgEmployeeDTO> getEmployeesByTenant(int pageNum, int pageSize, String keyword) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(pageNum)
            .pageSize(pageSize)
            .keyword(keyword)
            .build();

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
        List<OrgEmployeeDTO> dtos = result.getRecords().stream()
            .map(this::toEmployeeDTO)
            .collect(Collectors.toList());

        return PaginationResult.of(dtos, result.getTotal(), result.getPage(), result.getPageSize());
    }

    // ======================== BPM / Approval Extension Methods ========================

    @Override
    public List<Map<String, Object>> getManagersUpToLevel(String employeePid, int level) {
        if (employeePid == null || level <= 0) {
            return List.of();
        }

        List<Map<String, Object>> managers = new ArrayList<>();
        String currentPid = employeePid;

        for (int i = 0; i < level; i++) {
            Map<String, Object> current = dynamicDataService.getById(MODEL_EMPLOYEE, currentPid);
            if (current == null) {
                break;
            }
            String reportTo = asString(current.get(EMP_REPORT_TO));
            if (reportTo == null || reportTo.isBlank()) {
                break;
            }
            Map<String, Object> manager = dynamicDataService.getById(MODEL_EMPLOYEE, reportTo);
            if (manager == null) {
                break;
            }
            managers.add(manager);
            currentPid = reportTo;
        }

        return managers;
    }

    @Override
    public Map<String, Object> getDeptLeader(String deptPid) {
        if (deptPid == null) {
            return null;
        }
        Map<String, Object> dept = dynamicDataService.getById(MODEL_DEPARTMENT, deptPid);
        if (dept == null) {
            return null;
        }
        String managerId = asString(dept.get(DEPT_MANAGER_ID));
        if (managerId == null || managerId.isBlank()) {
            return null;
        }
        return dynamicDataService.getById(MODEL_EMPLOYEE, managerId);
    }

    @Override
    public List<Map<String, Object>> getEmployeesByRole(Long tenantId, String roleCode) {
        if (tenantId == null || roleCode == null || roleCode.isBlank()) {
            return List.of();
        }

        // Find the role by code in this tenant
        List<Role> roles = roleService.findByTenantId(tenantId);
        Role matchedRole = roles.stream()
            .filter(r -> roleCode.equals(r.getCode()))
            .findFirst()
            .orElse(null);
        if (matchedRole == null) {
            log.debug("Role not found: code={}, tenantId={}", roleCode, tenantId);
            return List.of();
        }

        // Find all members with this role
        List<UserRole> userRoles = userRoleService.findByRoleIds(List.of(matchedRole.getId()));
        if (userRoles == null || userRoles.isEmpty()) {
            return List.of();
        }

        // For each member, find the linked employee
        List<Map<String, Object>> employees = new ArrayList<>();
        for (UserRole ur : userRoles) {
            if (ur.getMemberId() == null) {
                continue;
            }
            TenantMember member = tenantMemberService.getById(ur.getMemberId());
            if (member == null || member.getPid() == null) {
                continue;
            }
            Map<String, Object> emp = getEmployeeByMemberPid(member.getPid());
            if (emp != null) {
                employees.add(emp);
            }
        }
        return employees;
    }

    @Override
    public List<Map<String, Object>> getEmployeesByPosition(Long tenantId, String positionCode) {
        if (positionCode == null || positionCode.isBlank()) {
            return List.of();
        }

        // Find position by code
        DynamicQueryRequest posRequest = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(1)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName(POS_CODE)
                    .operator(QueryCondition.Operator.EQ)
                    .value(positionCode)
                    .build()
            ))
            .build();
        PaginationResult<Map<String, Object>> posResult = dynamicDataService.list(MODEL_POSITION, posRequest);
        List<Map<String, Object>> positions = posResult.getRecords();
        if (positions == null || positions.isEmpty()) {
            log.debug("Position not found: code={}", positionCode);
            return List.of();
        }
        String positionPid = asString(positions.get(0).get("pid"));

        // Find employees with this position
        DynamicQueryRequest empRequest = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(MAX_QUERY_PAGE_SIZE)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName(EMP_POSITION_ID)
                    .operator(QueryCondition.Operator.EQ)
                    .value(positionPid)
                    .build()
            ))
            .build();
        PaginationResult<Map<String, Object>> empResult = dynamicDataService.list(MODEL_EMPLOYEE, empRequest);
        return empResult.getRecords() != null ? empResult.getRecords() : List.of();
    }

    @Override
    public List<Map<String, Object>> getPeers(String employeePid) {
        if (employeePid == null) {
            return List.of();
        }
        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            return List.of();
        }
        String deptId = asString(employee.get(EMP_DEPT_ID));
        if (deptId == null || deptId.isBlank()) {
            return List.of();
        }

        // Get all employees in the same department
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(MAX_QUERY_PAGE_SIZE)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName(EMP_DEPT_ID)
                    .operator(QueryCondition.Operator.EQ)
                    .value(deptId)
                    .build()
            ))
            .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
        List<Map<String, Object>> records = result.getRecords();
        if (records == null) {
            return List.of();
        }

        // Exclude self
        return records.stream()
            .filter(r -> !employeePid.equals(asString(r.get("pid"))))
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getSubordinates(String employeePid) {
        if (employeePid == null) {
            return List.of();
        }

        List<Map<String, Object>> allSubordinates = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(employeePid);

        List<String> currentLevel = List.of(employeePid);

        for (int depth = 0; depth < MAX_SUBORDINATE_DEPTH && !currentLevel.isEmpty(); depth++) {
            List<String> nextLevel = new ArrayList<>();

            for (String managerPid : currentLevel) {
                DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(MAX_QUERY_PAGE_SIZE)
                    .conditions(List.of(
                        QueryCondition.builder()
                            .fieldName(EMP_REPORT_TO)
                            .operator(QueryCondition.Operator.EQ)
                            .value(managerPid)
                            .build()
                    ))
                    .build();
                PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
                List<Map<String, Object>> directReports = result.getRecords();
                if (directReports == null) {
                    continue;
                }

                for (Map<String, Object> sub : directReports) {
                    String subPid = asString(sub.get("pid"));
                    if (subPid != null && visited.add(subPid)) {
                        allSubordinates.add(sub);
                        nextLevel.add(subPid);
                    }
                }
            }

            currentLevel = nextLevel;
        }

        return allSubordinates;
    }

    @Override
    public List<Map<String, Object>> getOrgPath(String employeePid) {
        if (employeePid == null) {
            return List.of();
        }
        Map<String, Object> employee = dynamicDataService.getById(MODEL_EMPLOYEE, employeePid);
        if (employee == null) {
            return List.of();
        }
        String deptPid = asString(employee.get(EMP_DEPT_ID));
        if (deptPid == null || deptPid.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String currentDeptPid = deptPid;

        while (currentDeptPid != null && !currentDeptPid.isBlank() && visited.add(currentDeptPid)) {
            Map<String, Object> dept = dynamicDataService.getById(MODEL_DEPARTMENT, currentDeptPid);
            if (dept == null) {
                break;
            }
            path.add(dept);
            currentDeptPid = asString(dept.get(DEPT_PARENT_ID));
        }

        return path;
    }

    // ======================== Private helpers ========================

    private DepartmentTreeNode buildTreeNode(
            Map<String, Object> dept,
            Map<String, List<Map<String, Object>>> childrenMap,
            Map<String, Integer> empCountByDept) {

        String pid = asString(dept.get("pid"));
        String name = asString(dept.get(DEPT_NAME));
        String parentPid = asString(dept.get(DEPT_PARENT_ID));
        int empCount = empCountByDept.getOrDefault(pid, 0);

        List<Map<String, Object>> children = childrenMap.getOrDefault(pid, List.of());
        List<DepartmentTreeNode> childNodes = children.stream()
            .map(child -> buildTreeNode(child, childrenMap, empCountByDept))
            .collect(Collectors.toList());

        return new DepartmentTreeNode(pid, name, parentPid, empCount, childNodes);
    }

    private void collectDescendants(List<DepartmentTreeNode> nodes, String targetPid, List<String> result) {
        for (DepartmentTreeNode node : nodes) {
            if (node.pid().equals(targetPid)) {
                collectAllPids(node, result);
                return;
            }
            collectDescendants(node.children(), targetPid, result);
        }
    }

    private void collectAllPids(DepartmentTreeNode node, List<String> result) {
        result.add(node.pid());
        for (DepartmentTreeNode child : node.children()) {
            collectAllPids(child, result);
        }
    }

    private Map<String, Integer> countEmployeesByDepartment() {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(500)
            .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(MODEL_EMPLOYEE, request);
        List<Map<String, Object>> employees = result.getRecords();

        if (employees == null) {
            return Map.of();
        }

        Map<String, Integer> countMap = new HashMap<>();
        for (Map<String, Object> emp : employees) {
            String deptPid = asString(emp.get(EMP_DEPT_ID));
            if (deptPid != null) {
                countMap.merge(deptPid, 1, Integer::sum);
            }
        }
        return countMap;
    }

    public OrgEmployeeDTO toEmployeeDTO(Map<String, Object> record) {
        String deptPid = asString(record.get(EMP_DEPT_ID));
        String deptName = resolveDeptName(deptPid);
        String positionPid = asString(record.get(EMP_POSITION_ID));
        String positionName = resolvePositionName(positionPid);

        return new OrgEmployeeDTO(
            asString(record.get("pid")),
            asString(record.get(EMP_NAME)),
            asString(record.get(EMP_CODE)),
            asString(record.get(EMP_EMAIL)),
            asString(record.get(EMP_PHONE)),
            asString(record.get(EMP_GENDER)),
            deptPid,
            deptName,
            positionPid,
            positionName,
            asString(record.get(EMP_STATUS)),
            asString(record.get(EMP_TYPE)),
            asString(record.get(EMP_MEMBER_ID)),
            asString(record.get(EMP_USER_ID))
        );
    }

    private String resolveDeptName(String deptPid) {
        if (deptPid == null) return null;
        Map<String, Object> dept = dynamicDataService.getById(MODEL_DEPARTMENT, deptPid);
        return dept != null ? asString(dept.get(DEPT_NAME)) : null;
    }

    private String resolvePositionName(String positionPid) {
        if (positionPid == null) return null;
        Map<String, Object> pos = dynamicDataService.getById(MODEL_POSITION, positionPid);
        return pos != null ? asString(pos.get(POS_NAME)) : null;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }
}
