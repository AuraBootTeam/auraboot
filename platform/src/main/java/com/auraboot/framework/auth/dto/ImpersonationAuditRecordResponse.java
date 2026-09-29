package com.auraboot.framework.auth.dto;

import java.time.Instant;

/** Safe, tenant-scoped audit projection for one customer's impersonation sessions. */
public record ImpersonationAuditRecordResponse(
        String sessionPid,
        String operatorDisplayName,
        String authorizationMethod,
        String reason,
        String reference,
        String clientType,
        String status,
        Instant startedAt,
        Instant expiresAt,
        Instant endedAt) {
}
