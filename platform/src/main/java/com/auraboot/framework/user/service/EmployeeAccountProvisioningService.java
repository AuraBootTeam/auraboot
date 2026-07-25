package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionRequest;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Customer employee account provisioning for private deployments.
 */
@Service
@RequiredArgsConstructor
public class EmployeeAccountProvisioningService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserProvisioningService userProvisioningService;
    private final UserService userService;
    private final RoleService roleService;

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

        String passwordPrefix = normalizeOrDefault(request.getPasswordPrefix(), "jjzz@");
        int randomDigitCount = request.getRandomDigitCount() == null ? 4 : request.getRandomDigitCount();
        if (passwordPrefix.length() > 32) {
            throw new BusinessException("passwordPrefix must be at most 32 characters");
        }
        if (randomDigitCount < 1 || randomDigitCount > 12) {
            throw new BusinessException("randomDigitCount must be between 1 and 12");
        }

        List<PreparedEmployee> employees = prepareEmployees(request.getEmployees());

        validateNoExistingUsers(employees);
        validateRolesExist(employees, tenantId);

        List<EmployeeAccountProvisionResponse.Account> accounts = new ArrayList<>();
        for (PreparedEmployee employee : employees) {
            String password = generatePassword(passwordPrefix, randomDigitCount);
            UserProvisionRequest provisionRequest = new UserProvisionRequest();
            provisionRequest.setEmail(employee.email());
            provisionRequest.setDisplayName(employee.name());
            provisionRequest.setUserName(employee.name());
            provisionRequest.setInitialPassword(password);
            provisionRequest.setRoleCodes(employee.roleCodes());

            UserProvisionResponse response = userProvisioningService.provision(provisionRequest, tenantId, creatorId);
            accounts.add(EmployeeAccountProvisionResponse.Account.builder()
                    .userId(response.getUserId())
                    .userPid(response.getUserPid())
                    .name(employee.name())
                    .type(employee.type())
                    .userName(employee.name())
                    .email(response.getEmail())
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

    private List<PreparedEmployee> prepareEmployees(List<EmployeeAccountRow> rows) {
        List<PreparedEmployee> employees = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (EmployeeAccountRow row : rows) {
            if (row == null) {
                throw new BusinessException("Employee row is required");
            }
            String name = normalizeOrNull(row.getName());
            String type = normalizeOrNull(row.getType());
            if (name == null) {
                throw new BusinessException("Employee name is required");
            }
            if (!seenNames.add(name)) {
                throw new BusinessException("Duplicate employee name: " + name);
            }

            // Roles are taken verbatim from the row. An employee with no roles is
            // provisioned as a bare account; validateRolesExist rejects any code
            // that does not exist in the tenant.
            List<String> roleCodes = row.getRoles() == null ? List.of() : row.getRoles().stream()
                    .map(this::normalizeOrNull)
                    .filter(value -> value != null)
                    .distinct()
                    .toList();
            employees.add(new PreparedEmployee(
                    name,
                    type,
                    normalizeOrNull(row.getEmail()),
                    roleCodes
            ));
        }
        return employees;
    }

    private void validateNoExistingUsers(List<PreparedEmployee> employees) {
        for (PreparedEmployee employee : employees) {
            if (userService.findByUserName(employee.name()) != null) {
                throw new BusinessException("User already exists: " + employee.name());
            }
            if (employee.email() != null && userService.findByEmail(employee.email()) != null) {
                throw new BusinessException("User already exists: " + employee.email());
            }
        }
    }

    private void validateRolesExist(List<PreparedEmployee> employees, Long tenantId) {
        Set<String> requiredCodes = employees.stream()
                .flatMap(employee -> employee.roleCodes().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    private record PreparedEmployee(String name, String type, String email, List<String> roleCodes) {
    }
}
