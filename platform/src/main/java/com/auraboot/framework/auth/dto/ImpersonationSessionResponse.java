package com.auraboot.framework.auth.dto;

import java.time.Instant;

public record ImpersonationSessionResponse(
        String jwt,
        String sessionPid,
        Instant expiresAt,
        String targetUserPid,
        String targetMemberPid,
        String targetDisplayName,
        String operatorDisplayName,
        String clientType) {
}
