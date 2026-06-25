package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.organization.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Command handler for organization employee lifecycle operations.
 */
@Slf4j
@Component("orgEmployeeCommandHandler")
@RequiredArgsConstructor
public class OrgEmployeeCommandHandler implements CommandHandler {

    private final OrgEmployeeService orgEmployeeService;

    @Override
    public String getHandlerName() {
        return "orgEmployeeCommandHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        String commandCode = context.getCommandCode();
        log.info("OrgEmployeeCommandHandler executing: {}", commandCode);

        if (!"org:open_employee_account".equals(commandCode)) {
            Map<String, Object> skipped = new HashMap<>();
            skipped.put("handlerExecuted", false);
            return skipped;
        }

        String employeePid = extractEmployeePid(context);
        EmployeeAccountProvisionResponse response = orgEmployeeService.openAccount(employeePid);

        Map<String, Object> result = new HashMap<>();
        result.put("handlerExecuted", true);
        result.put("action", "open_employee_account");
        result.put("employeePid", response.getEmployeePid());
        result.put("userPid", response.getUserPid());
        result.put("memberPid", response.getMemberPid());
        result.put("email", response.getEmail());
        result.put("userName", response.getUserName());
        result.put("displayName", response.getDisplayName());
        result.put("createdUser", response.isCreatedUser());
        result.put("createdMember", response.isCreatedMember());
        result.put("adminManaged", response.isAdminManaged());
        result.put("assignedRoles", response.getAssignedRoles());
        if (response.getTemporaryPassword() != null && !response.getTemporaryPassword().isBlank()) {
            result.put("tempPassword", response.getTemporaryPassword());
        }
        return result;
    }

    private String extractEmployeePid(CommandHandlerContext context) {
        String employeePid = context.getTargetRecordId();
        if (employeePid == null || employeePid.isBlank()) {
            Map<String, Object> payload = context.getPayload();
            if (payload != null) {
                Object pid = payload.get("pid");
                if (pid == null) {
                    pid = payload.get("employeePid");
                }
                if (pid != null) {
                    employeePid = pid.toString();
                }
            }
        }
        if (employeePid == null || employeePid.isBlank()) {
            throw new BusinessException("Target record ID (employeePid) is required for employee account operations");
        }
        return employeePid;
    }
}
