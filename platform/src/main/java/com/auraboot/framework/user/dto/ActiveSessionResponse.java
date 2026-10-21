package com.auraboot.framework.user.dto;

import com.auraboot.framework.auth.entity.UserSession;
import io.swagger.v3.oas.annotations.media.Schema;

/** Public session metadata; bearer hashes and internal user IDs are never exposed. */
public record ActiveSessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String deviceInfo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ipAddress,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") String createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") String lastActiveAt) {
    public static ActiveSessionResponse from(UserSession session) {
        return new ActiveSessionResponse(
                session.getPid(),
                session.getDeviceInfo() != null ? session.getDeviceInfo() : "Unknown",
                session.getIpAddress() != null ? session.getIpAddress() : "Unknown",
                session.getCreatedAt().toString(),
                session.getLastActiveAt().toString());
    }
}
