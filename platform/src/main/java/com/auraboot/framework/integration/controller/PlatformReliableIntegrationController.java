package com.auraboot.framework.integration.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.QuarantineResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorService;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/** Global control-plane quarantine only; no cross-tenant replay or payload access. */
@RestController
@RequirePlatformAdmin
@DisallowImpersonation
@RequiredArgsConstructor
@RequestMapping("/api/admin/infrastructure/reliable-integration/dead-letters")
public class PlatformReliableIntegrationController {
    private final ReliableIntegrationOperatorService operations;
    private final TenantService tenants;
    private final AdminRoleChecker roles;
    private final AdminEventLogService audit;
    private final ObjectMapper objectMapper;

    public record PlatformQuarantineRequest(
            @NotBlank @Size(max = 26) String tenantPid,
            @NotBlank String eventId,
            @NotBlank @Size(max = 512) String reason,
            @NotNull @PositiveOrZero Integer expectedReplayCount) {
    }

    @PostMapping("/quarantine")
    public ApiResponse<QuarantineResult> quarantine(@Valid @RequestBody PlatformQuarantineRequest request) {
        if (MetaContext.isImpersonating() || !"platform".equals(MetaContext.getCurrentExecutionScope())
                || !roles.hasRole(MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(),
                RoleCodes.PLATFORM_ADMIN)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Platform administrator session required");
        }
        var tenant = tenants.findByPid(request.tenantPid());
        if (tenant == null || Boolean.TRUE.equals(tenant.getDeletedFlag())) {
            return ApiResponse.error(404, "tenant not found");
        }
        QuarantineResult result;
        try {
            result = operations.quarantine(tenant.getId(), request.eventId(),
                    MetaContext.getCurrentUserPid(), request.reason(), request.expectedReplayCount());
        } catch (NoSuchElementException missing) {
            return ApiResponse.error(404, missing.getMessage());
        } catch (IllegalStateException conflict) {
            return ApiResponse.error(409, conflict.getMessage());
        }
        audit.record(AdminEventLog.builder().tenantId(MetaContext.getCurrentTenantId())
                .actorUserId(MetaContext.getCurrentUserId()).actionType("integration.dead-letter.quarantine")
                .resourceType("tenant").resourcePid(tenant.getPid()).success(true).reason(request.reason())
                .payload(objectMapper.createObjectNode().put("eventId", request.eventId())
                        .put("quarantineRecordPid", result.recordPid())).build());
        return ApiResponse.success(result);
    }
}
