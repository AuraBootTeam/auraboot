package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.openplatform.entity.OpenApiCallAudit;
import com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper;
import com.auraboot.framework.openplatform.security.OpenApiRequestIdFilter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OpenPlatformOAuthFailureAudit {
    private final OpenApiCallAuditMapper auditMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(HttpServletRequest request, String requestId, int status, String code,
                       OpenPlatformTokenService.AuditIdentity identity) {
        OpenApiCallAudit audit = new OpenApiCallAudit();
        audit.setPid(UniqueIdGenerator.generate());
        if (identity != null) {
            audit.setTenantId(identity.tenantId());
            audit.setApplicationPid(identity.applicationPid());
            audit.setInstallationPid(identity.installationPid());
        }
        audit.setRequestId(requestId);
        audit.setHttpMethod("POST");
        audit.setRequestPath("/oauth2/token");
        audit.setResponseStatus(status);
        Object started = request.getAttribute(OpenApiRequestIdFilter.STARTED_NANOS_ATTRIBUTE);
        audit.setDurationMs(started instanceof Long nanos ? Math.max(0, (System.nanoTime() - nanos) / 1_000_000) : 0L);
        String remote = request.getRemoteAddr();
        audit.setRemoteAddress(remote != null && remote.matches("[0-9a-fA-F:.]{1,96}") ? remote : null);
        audit.setOccurredAt(Instant.now());
        audit.setBusinessDetails(JsonNodeFactory.instance.objectNode()
                .put("actorKind", "unauthenticated").put("phase", "authentication").put("code", code));
        if (auditMapper.insertAuthenticationFailure(audit) != 1) {
            throw new IllegalStateException("OAuth failure audit was not persisted");
        }
    }
}
