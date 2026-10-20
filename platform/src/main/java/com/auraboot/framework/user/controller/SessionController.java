package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.auraboot.framework.user.dto.ActiveSessionResponse;

@RestController
@RequestMapping("/api/user/sessions")
@RequiredArgsConstructor
@Tag(name = "Session Management", description = "User session management")
@AuthenticatedAccess("operates only on the caller's own sessions (@CurrentUserId)")
public class SessionController {

    private final SessionManagementService sessionManagementService;

    @GetMapping
    @Operation(summary = "Get active sessions")
    public ApiResponse<List<ActiveSessionResponse>> getActiveSessions(@CurrentUserId Long userId) {
        List<UserSession> sessions = sessionManagementService.getActiveSessions(userId);
        return ApiResponse.success(sessions.stream().map(ActiveSessionResponse::from).toList());
    }

    @DeleteMapping("/{sessionPid}")
    @DisallowImpersonation
    @Operation(summary = "Revoke a specific session")
    public ApiResponse<Void> revokeSession(@CurrentUserId Long userId, @PathVariable String sessionPid) {
        sessionManagementService.revokeSession(userId, sessionPid);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/current")
    @DisallowImpersonation
    @Operation(summary = "Revoke current session")
    public ApiResponse<Void> revokeCurrentSession(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            sessionManagementService.revokeSessionByToken(header.substring(7));
        }
        return ApiResponse.success(null);
    }

    @DeleteMapping
    @DisallowImpersonation
    @Operation(summary = "Revoke all sessions (logout everywhere)")
    public ApiResponse<Void> revokeAllSessions(@CurrentUserId Long userId) {
        sessionManagementService.revokeAllSessions(userId);
        return ApiResponse.success(null);
    }
}
