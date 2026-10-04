package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.dto.ImpersonationSessionRequest;
import com.auraboot.framework.auth.dto.ImpersonationSessionResponse;
import com.auraboot.framework.auth.dto.ImpersonationAuditRecordResponse;
import com.auraboot.framework.auth.service.ImpersonationSessionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ImpersonationSessionController {

    private final ImpersonationSessionService impersonationSessionService;

    @PostMapping("/impersonation-sessions")
    @RequirePermission(MetaPermission.CUSTOMER_IMPERSONATE)
    public ApiResponse<ImpersonationSessionResponse> start(
            @Valid @RequestBody ImpersonationSessionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(impersonationSessionService.start(
                request,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")));
    }

    @GetMapping("/impersonation-sessions/history")
    @RequirePermission(MetaPermission.CUSTOMER_IMPERSONATE)
    public ApiResponse<List<ImpersonationAuditRecordResponse>> history(
            @RequestParam String targetMemberPid,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(impersonationSessionService.history(targetMemberPid, limit));
    }

    @PostMapping("/impersonation-sessions/current/end")
    @AuthenticatedAccess("ends only the caller's current server-side impersonation session")
    public ApiResponse<Void> endCurrent(
            @RequestHeader("Authorization") String authorizationHeader) {
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        impersonationSessionService.endCurrent(token);
        return ApiResponse.success(null);
    }
}
