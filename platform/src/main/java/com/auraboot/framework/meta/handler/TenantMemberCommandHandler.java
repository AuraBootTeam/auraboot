package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Command handler for tenant member administration operations.
 *
 * Bridges DSL commands to {@link TenantMemberApplicationService} which
 * handles all validation, permission checks, and state transitions.
 *
 * Supported command codes:
 * - admin:approve_member  → approve a pending member
 * - admin:reject_member   → reject a pending member (with optional reason)
 * - admin:suspend_member  → suspend an active member (with optional reason)
 * - admin:restore_member  → restore a suspended member
 * - admin:leave_member    → mark a member as left (INACTIVE + set leave_date)
 * - admin:delete_member   → remove a member from the tenant
 *
 * @author AuraBoot Team
 * @since 4.0.0
 */
@Slf4j
@Component("tenantMemberCommandHandler")
@RequiredArgsConstructor
public class TenantMemberCommandHandler implements CommandHandler {

    private final TenantMemberApplicationService tenantMemberApplicationService;

    @Override
    public String getHandlerName() {
        return "tenantMemberCommandHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        String commandCode = context.getCommandCode();
        log.info("TenantMemberCommandHandler executing: {}", commandCode);

        Map<String, Object> result = new HashMap<>();

        try {
            String memberPid = extractMemberPid(context);
            Long userId = extractUserId(context);

            switch (commandCode) {
                case "admin:approve_member" -> handleApprove(memberPid, userId, result);
                case "admin:reject_member" -> handleReject(memberPid, context.getPayload(), userId, result);
                case "admin:suspend_member" -> handleSuspend(memberPid, context.getPayload(), userId, result);
                case "admin:restore_member" -> handleRestore(memberPid, userId, result);
                case "admin:leave_member" -> handleLeave(memberPid, userId, result);
                case "admin:delete_member" -> handleDelete(memberPid, userId, result);
                default -> {
                    log.warn("Unknown command code for TenantMemberCommandHandler: {}", commandCode);
                    result.put("handlerExecuted", false);
                    return result;
                }
            }

            result.put("handlerExecuted", true);
            log.info("TenantMemberCommandHandler completed: {} for member pid={}", commandCode, memberPid);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("TenantMemberCommandHandler failed for {}: {}", commandCode, e.getMessage(), e);
            throw new BusinessException("Member operation failed: " + e.getMessage(), e);
        }

        return result;
    }

    private void handleApprove(String memberPid, Long userId, Map<String, Object> result) {
        tenantMemberApplicationService.approveMember(memberPid, "approve", null, userId);
        result.put("action", "approve");
        result.put("newStatus", StatusConstants.ACTIVE);
    }

    private void handleReject(String memberPid, Map<String, Object> payload, Long userId, Map<String, Object> result) {
        String reason = extractReason(payload);
        tenantMemberApplicationService.approveMember(memberPid, "reject", reason, userId);
        result.put("action", "reject");
        result.put("newStatus", StatusConstants.REJECTED);
        if (reason != null) {
            result.put("reason", reason);
        }
    }

    private void handleSuspend(String memberPid, Map<String, Object> payload, Long userId, Map<String, Object> result) {
        String reason = extractReason(payload);
        tenantMemberApplicationService.updateMemberStatus(memberPid, "suspended", reason, userId);
        result.put("action", "suspend");
        result.put("newStatus", StatusConstants.SUSPENDED);
        if (reason != null) {
            result.put("reason", reason);
        }
    }

    private void handleRestore(String memberPid, Long userId, Map<String, Object> result) {
        tenantMemberApplicationService.approveMember(memberPid, "approve", null, userId);
        result.put("action", "restore");
        result.put("newStatus", StatusConstants.ACTIVE);
    }

    private void handleLeave(String memberPid, Long userId, Map<String, Object> result) {
        tenantMemberApplicationService.updateMemberStatus(memberPid, "inactive", null, userId);
        result.put("action", "leave");
        result.put("newStatus", StatusConstants.INACTIVE);
    }

    private void handleDelete(String memberPid, Long userId, Map<String, Object> result) {
        tenantMemberApplicationService.removeMember(memberPid, userId);
        result.put("action", "delete");
        result.put("removed", true);
    }

    /**
     * Extract member PID from the context. Tries targetRecordId first,
     * then falls back to the "pid" field in the payload.
     */
    private String extractMemberPid(CommandHandlerContext context) {
        String memberPid = context.getTargetRecordId();
        if (memberPid == null || memberPid.isBlank()) {
            Map<String, Object> payload = context.getPayload();
            if (payload != null) {
                Object pid = payload.get("pid");
                if (pid != null) {
                    memberPid = pid.toString();
                }
            }
        }
        if (memberPid == null || memberPid.isBlank()) {
            throw new BusinessException("Target record ID (memberPid) is required for member operations");
        }
        return memberPid;
    }

    /**
     * Extract the current user ID from the handler context.
     */
    private Long extractUserId(CommandHandlerContext context) {
        Long userId = context.getUserId();
        if (userId == null) {
            throw new BusinessException("User ID is required for member operations");
        }
        return userId;
    }

    /**
     * Extract optional reason string from the payload.
     */
    private String extractReason(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object reason = payload.get("reason");
        return reason != null ? reason.toString() : null;
    }
}
