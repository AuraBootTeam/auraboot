package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user/sessions")
@RequiredArgsConstructor
@Tag(name = "Session Management", description = "User session management")
public class SessionController {

    private final SessionManagementService sessionManagementService;

    @GetMapping
    @Operation(summary = "Get active sessions")
    public ApiResponse<List<Map<String, Object>>> getActiveSessions(@CurrentUserId Long userId) {
        List<UserSession> sessions = sessionManagementService.getActiveSessions(userId);
        List<Map<String, Object>> result = sessions.stream().map(s -> Map.<String, Object>of(
                "pid", s.getPid(),
                "deviceInfo", s.getDeviceInfo() != null ? s.getDeviceInfo() : "Unknown",
                "ipAddress", s.getIpAddress() != null ? s.getIpAddress() : "Unknown",
                "createdAt", s.getCreatedAt().toString(),
                "lastActiveAt", s.getLastActiveAt().toString()
        )).toList();
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{sessionPid}")
    @Operation(summary = "Revoke a specific session")
    public ApiResponse<Void> revokeSession(@CurrentUserId Long userId, @PathVariable String sessionPid) {
        sessionManagementService.revokeSession(userId, sessionPid);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    @Operation(summary = "Revoke all sessions (logout everywhere)")
    public ApiResponse<Void> revokeAllSessions(@CurrentUserId Long userId) {
        sessionManagementService.revokeAllSessions(userId);
        return ApiResponse.success(null);
    }
}
