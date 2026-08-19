package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.user.dto.EmployeeAccountImportPreviewResponse;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionRequest;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.dto.RoleAssignmentMode;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Customer employee account provisioning for private deployments.
 */
@Service
@RequiredArgsConstructor
public class EmployeeAccountProvisioningService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_IMPORT_ROWS = 500;

    private static final String MODEL_DEPARTMENT = "org_department";
    private static final String MODEL_POSITION = "org_position";
    private static final String MODEL_EMPLOYEE = "org_employee";
    private static final String DEPARTMENT_CODE = "org_dept_code";
    private static final String POSITION_CODE = "org_pos_code";
    private static final String POSITION_DEPARTMENT_ID = "org_pos_dept_id";
    private static final String EMPLOYEE_CODE = "org_emp_code";
    private static final String EMPLOYEE_DEPARTMENT_ID = "org_emp_dept_id";
    private static final String EMPLOYEE_POSITION_ID = "org_emp_position_id";
    private static final String EMPLOYEE_MEMBER_ID = "org_emp_member_id";

    private final UserProvisioningService userProvisioningService;
    private final UserService userService;
    private final RoleService roleService;
    private final DynamicDataService dynamicDataService;
    private final OrgEmployeeService orgEmployeeService;

    /**
     * Validate a parsed workbook without creating accounts or organization records.
     */
    public EmployeeAccountImportPreviewResponse preview(List<EmployeeAccountRow> rows, Long tenantId) {
        if (tenantId == null) {
            throw new BusinessException("tenantId is required");
        }
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException("Employees are required");
        }
        validateBatchSize(rows);

        ValidationBatch validation = validateRows(rows, tenantId);
        List<EmployeeAccountImportPreviewResponse.Row> previewRows = validation.rows().stream()
                .map(item -> EmployeeAccountImportPreviewResponse.Row.builder()
                        .rowNumber(item.rowNumber())
                        .name(item.name())
                        .userName(item.userName())
                        .mobile(item.mobile())
                        .employeeCode(item.employeeCode())
                        .departmentCode(item.departmentCode())
                        .positionCode(item.positionCode())
                        .action(item.action())
                        .errors(item.errors())
                        .build())
                .toList();
        int errorCount = (int) previewRows.stream().filter(row -> !row.getErrors().isEmpty()).count();
        return EmployeeAccountImportPreviewResponse.builder()
                .totalRows(previewRows.size())
                .validCount(previewRows.size() - errorCount)
                .errorCount(errorCount)
                .rows(previewRows)
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public EmployeeAccountProvisionResponse provision(EmployeeAccountProvisionRequest request,
                                                       Long tenantId,
                                                       Long creatorId) {
        if (tenantId == null) {
            throw new BusinessException("tenantId is required");
        }
        if (request == null || request.getEmployees() == null || request.getEmployees().isEmpty()) {
            throw new BusinessException("Employees are required");
        }
        validateBatchSize(request.getEmployees());

        String passwordPrefix = normalizeOrDefault(request.getPasswordPrefix(), "jjzz@");
        int randomDigitCount = request.getRandomDigitCount() == null ? 4 : request.getRandomDigitCount();
        if (passwordPrefix.length() > 32) {
            throw new BusinessException("passwordPrefix must be at most 32 characters");
        }
        if (randomDigitCount < 1 || randomDigitCount > 12) {
            throw new BusinessException("randomDigitCount must be between 1 and 12");
        }

        ValidationBatch validation = validateRows(request.getEmployees(), tenantId);
        ValidatedEmployee invalid = validation.rows().stream()
                .filter(row -> !row.errors().isEmpty())
                .findFirst()
                .orElse(null);
        if (invalid != null) {
            throw new BusinessException("Row " + invalid.rowNumber() + ": " + String.join("; ", invalid.errors()));
        }

        validateRolesExist(validation.rows(), tenantId);

        List<EmployeeAccountProvisionResponse.Account> accounts = new ArrayList<>();
        for (ValidatedEmployee employee : validation.rows()) {
            String password = generatePassword(passwordPrefix, randomDigitCount);
            UserProvisionRequest provisionRequest = new UserProvisionRequest();
            provisionRequest.setEmail(employee.email());
            provisionRequest.setDisplayName(employee.name());
            provisionRequest.setUserName(employee.userName());
            provisionRequest.setMobile(employee.mobile());
            provisionRequest.setInitialPassword(password);
            provisionRequest.setRoleCodes(employee.roleCodes());
            provisionRequest.setRoleAssignmentMode(employee.roleCodes().isEmpty()
                    ? RoleAssignmentMode.NONE
                    : RoleAssignmentMode.EXPLICIT);

            UserProvisionResponse response = userProvisioningService.provision(
                    provisionRequest, tenantId, creatorId);
            OrganizationLink organizationLink = linkOrganization(employee, response);
            accounts.add(EmployeeAccountProvisionResponse.Account.builder()
                    .userId(response.getUserId())
                    .userPid(response.getUserPid())
                    .memberPid(response.getMemberPid())
                    .employeePid(organizationLink.employeePid())
                    .name(employee.name())
                    .type(employee.type())
                    .userName(employee.userName())
                    .email(response.getEmail())
                    .mobile(employee.mobile())
                    .employeeCode(employee.employeeCode())
                    .departmentCode(employee.departmentCode())
                    .positionCode(employee.positionCode())
                    .organizationAction(organizationLink.action())
                    .initialPassword(password)
                    .assignedRoles(response.getAssignedRoles())
                    .mustChangePassword(response.isMustChangePassword())
                    .build());
        }

        return EmployeeAccountProvisionResponse.builder()
                .total(accounts.size())
                .accounts(accounts)
                .build();
    }

    private ValidationBatch validateRows(List<EmployeeAccountRow> rows, Long tenantId) {
        List<ValidatedEmployee> employees = new ArrayList<>();
        Set<String> seenUserNames = new LinkedHashSet<>();
        Set<String> seenEmployeeCodes = new LinkedHashSet<>();

        for (int index = 0; index < rows.size(); index++) {
            EmployeeAccountRow row = rows.get(index);
            int rowNumber = row != null && row.getSourceRowNumber() != null
                    ? row.getSourceRowNumber()
                    : index + 1;
            List<String> errors = new ArrayList<>();
            if (row == null) {
                employees.add(invalidRow(rowNumber, "Employee row is required"));
                continue;
            }

            String name = normalizeOrNull(row.getName());
            String userName = normalizeOrDefault(row.getUserName(), name);
            String email = normalizeOrNull(row.getEmail());
            String mobile = normalizeOrNull(row.getMobile());
            String employeeCode = normalizeOrNull(row.getEmployeeCode());
            String departmentCode = normalizeOrNull(row.getDepartmentCode());
            String positionCode = normalizeOrNull(row.getPositionCode());

            if (name == null) {
                errors.add("Employee name is required");
            } else if (name.length() > 64) {
                errors.add("Employee name must be at most 64 characters");
            }
            if (userName == null) {
                errors.add("Login name is required");
            } else {
                if (userName.length() > 64) {
                    errors.add("Login name must be at most 64 characters");
                }
                if (!seenUserNames.add(normalizedKey(userName))) {
                    errors.add("Duplicate login name in workbook: " + userName);
                }
                if (userService.findByUserName(userName) != null) {
                    errors.add("User already exists: " + userName);
                }
            }
            if (email != null && userService.findByEmail(email) != null) {
                errors.add("User already exists: " + email);
            }
            validateMaxLength(mobile, 64, "Mobile", errors);
            validateMaxLength(employeeCode, 50, "Employee code", errors);
            validateMaxLength(departmentCode, 50, "Department code", errors);
            validateMaxLength(positionCode, 50, "Position code", errors);
            if (employeeCode != null && !seenEmployeeCodes.add(normalizedKey(employeeCode))) {
                errors.add("Duplicate employee code in workbook: " + employeeCode);
            }

            List<String> roleCodes = normalizeRoleCodes(row.getRoles());
            OrganizationResolution organization = resolveOrganization(
                    employeeCode, departmentCode, positionCode, errors);
            employees.add(new ValidatedEmployee(
                    rowNumber,
                    name,
                    userName,
                    normalizeOrNull(row.getType()),
                    email,
                    mobile,
                    employeeCode,
                    departmentCode,
                    positionCode,
                    roleCodes,
                    organization.employeePid(),
                    organization.departmentPid(),
                    organization.positionPid(),
                    errors.isEmpty() ? organization.action() : "ERROR",
                    List.copyOf(errors)
            ));
        }
        return new ValidationBatch(employees);
    }

    private OrganizationResolution resolveOrganization(String employeeCode,
                                                         String departmentCode,
                                                         String positionCode,
                                                         List<String> errors) {
        if (employeeCode == null && departmentCode == null && positionCode == null) {
            return OrganizationResolution.accountOnly();
        }
        if (employeeCode == null) {
            errors.add("Employee code is required when organization fields are provided");
            return OrganizationResolution.invalid();
        }

        Map<String, Object> employee = findUniqueByCode(
                MODEL_EMPLOYEE, EMPLOYEE_CODE, employeeCode, "employee", false, errors);
        Map<String, Object> department = departmentCode == null ? null : findUniqueByCode(
                MODEL_DEPARTMENT, DEPARTMENT_CODE, departmentCode, "department", true, errors);
        Map<String, Object> position = positionCode == null ? null : findUniqueByCode(
                MODEL_POSITION, POSITION_CODE, positionCode, "position", true, errors);

        String departmentPid = value(department, "pid");
        String positionPid = value(position, "pid");
        if (position != null && department != null) {
            String positionDepartmentPid = value(position, POSITION_DEPARTMENT_ID);
            if (!departmentPid.equals(positionDepartmentPid)) {
                errors.add("Position " + positionCode + " does not belong to department " + departmentCode);
            }
        }

        if (employee != null) {
            if (hasText(value(employee, EMPLOYEE_MEMBER_ID))) {
                errors.add("Employee is already linked to a tenant member: " + employeeCode);
            }
            String employeeDepartmentPid = value(employee, EMPLOYEE_DEPARTMENT_ID);
            String employeePositionPid = value(employee, EMPLOYEE_POSITION_ID);
            if (departmentPid != null && !departmentPid.equals(employeeDepartmentPid)) {
                errors.add("Employee department does not match department code: " + departmentCode);
            }
            if (positionPid != null && !positionPid.equals(employeePositionPid)) {
                errors.add("Employee position does not match position code: " + positionCode);
            }
            return new OrganizationResolution(value(employee, "pid"), employeeDepartmentPid,
                    employeePositionPid, "LINK_EXISTING_EMPLOYEE");
        }

        if (department == null) {
            errors.add("Department code is required when creating an organization employee");
        }
        return new OrganizationResolution(null, departmentPid, positionPid, "CREATE_EMPLOYEE");
    }

    private Map<String, Object> findUniqueByCode(String modelCode,
                                                  String fieldCode,
                                                  String code,
                                                  String label,
                                                  boolean required,
                                                  List<String> errors) {
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(2)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName(fieldCode)
                        .operator(QueryCondition.Operator.EQ)
                        .value(code)
                        .build()))
                .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
        List<Map<String, Object>> records = result == null || result.getRecords() == null
                ? List.of()
                : result.getRecords();
        if (records.size() > 1) {
            errors.add("Duplicate " + label + " code in tenant: " + code);
            return null;
        }
        if (records.isEmpty()) {
            if (required) {
                errors.add("Unknown " + label + " code: " + code);
            }
            return null;
        }
        return records.get(0);
    }

    private OrganizationLink linkOrganization(ValidatedEmployee employee, UserProvisionResponse response) {
        if (employee.employeeCode() == null) {
            return new OrganizationLink(null, "NONE");
        }
        if (!hasText(response.getMemberPid())) {
            throw new BusinessException("Provisioned tenant member PID is missing");
        }
        OrgEmployeeDTO linked;
        if (employee.existingEmployeePid() != null) {
            linked = orgEmployeeService.linkExistingMember(
                    employee.existingEmployeePid(), response.getMemberPid());
            return new OrganizationLink(linked.pid(), "LINKED");
        }

        LinkMemberRequest request = new LinkMemberRequest();
        request.setMemberPid(response.getMemberPid());
        request.setEmployeeCode(employee.employeeCode());
        request.setDeptPid(employee.departmentPid());
        request.setPositionPid(employee.positionPid());
        linked = orgEmployeeService.linkMember(request);
        return new OrganizationLink(linked.pid(), "CREATED");
    }

    private void validateRolesExist(List<ValidatedEmployee> employees, Long tenantId) {
        Set<String> requiredCodes = employees.stream()
                .flatMap(employee -> employee.roleCodes().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requiredCodes.isEmpty()) {
            return;
        }
        List<Role> tenantRoles = roleService.findByTenantId(tenantId);
        Set<String> existingCodes = (tenantRoles == null ? List.<Role>of() : tenantRoles).stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());
        List<String> missingCodes = requiredCodes.stream()
                .filter(code -> !existingCodes.contains(code))
                .toList();
        if (!missingCodes.isEmpty()) {
            throw new BusinessException("Missing tenant roles: " + String.join(", ", missingCodes));
        }
    }

    private List<String> normalizeRoleCodes(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
                .map(this::normalizeOrNull)
                .filter(value -> value != null)
                .distinct()
                .toList();
    }

    private void validateBatchSize(List<EmployeeAccountRow> rows) {
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new BusinessException("At most " + MAX_IMPORT_ROWS + " employees can be imported at once");
        }
    }

    private void validateMaxLength(String value, int maxLength, String label, List<String> errors) {
        if (value != null && value.length() > maxLength) {
            errors.add(label + " must be at most " + maxLength + " characters");
        }
    }

    private ValidatedEmployee invalidRow(int rowNumber, String error) {
        return new ValidatedEmployee(rowNumber, null, null, null, null, null,
                null, null, null, List.of(), null, null, null, "ERROR", List.of(error));
    }

    private String generatePassword(String prefix, int digits) {
        StringBuilder value = new StringBuilder(prefix);
        for (int i = 0; i < digits; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    private String normalizeOrDefault(String value, String fallback) {
        String normalized = normalizeOrNull(value);
        return normalized == null ? fallback : normalized;
    }

    private String normalizeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizedKey(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String value(Map<String, Object> record, String key) {
        if (record == null || record.get(key) == null) {
            return null;
        }
        return record.get(key).toString();
    }

    private record ValidationBatch(List<ValidatedEmployee> rows) {
    }

    private record ValidatedEmployee(
            int rowNumber,
            String name,
            String userName,
            String type,
            String email,
            String mobile,
            String employeeCode,
            String departmentCode,
            String positionCode,
            List<String> roleCodes,
            String existingEmployeePid,
            String departmentPid,
            String positionPid,
            String action,
            List<String> errors) {
    }

    private record OrganizationResolution(
            String employeePid,
            String departmentPid,
            String positionPid,
            String action) {
        private static OrganizationResolution accountOnly() {
            return new OrganizationResolution(null, null, null, "CREATE_ACCOUNT");
        }

        private static OrganizationResolution invalid() {
            return new OrganizationResolution(null, null, null, "ERROR");
        }
    }

    private record OrganizationLink(String employeePid, String action) {
    }
}
