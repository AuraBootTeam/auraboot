package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.*;
import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.LoginRateLimiter;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.UserInfoService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.auth.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login, register, and current user APIs")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserInfoService userInfoService;
    private final UserService userService;
    private final PasswordManagementService passwordManagementService;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    @Autowired
    private ApiRateLimiter apiRateLimiter;

    @Autowired(required = false)
    private SystemModeService systemModeService;

    @Autowired(required = false)
    private TenantMemberService tenantMemberService;

    @PostMapping("/login")
    @ResponseBody
    @Operation(summary = "Password login", description = "Authenticate with email/username and password. Returns JWT token.")
    public ApiResponse<AuthenticationResponse> login(@RequestBody AuthenticationRequest request,
                                                      HttpServletRequest httpRequest) {
            if (!loginRateLimiter.isAllowed(extractIp(httpRequest), request.getEmail())) {
                return ApiResponse.error(ResponseCode.BadParam, "Too many login attempts. Please try again later.", null);
            }

            AuthenticationResponse authenticate = authService.authenticate(request);
            return ApiResponse.success(authenticate);
    }
    
    @PostMapping("/register")
    @ResponseBody
    @Operation(summary = "Register new user", description = "Register a new user account. Returns JWT token.")
    public ApiResponse<AuthenticationResponse> register(@jakarta.validation.Valid @RequestBody RegisterRequest request) {

            // SINGLE mode: check if self-registration is allowed
            if (systemModeService != null && systemModeService.isSingleTenant() && !systemModeService.isRegistrationAllowed()) {
                return ApiResponse.error("Self-registration is disabled in single-tenant mode");
            }

            AuthenticationResponse response = authService.register(request);

            // SINGLE mode: auto-join user to default tenant
            if (systemModeService != null && systemModeService.isSingleTenant() && response.getUserId() != null) {
                Long defaultTenantId = systemModeService.getDefaultTenantId();
                if (defaultTenantId != null && defaultTenantId > 0 && tenantMemberService != null) {
                    try {
                        tenantMemberService.addMember(response.getUserId(), defaultTenantId, "active");
                        // Re-generate JWT with tenantId included
                        User user = userService.findByUserId(response.getUserId());
                        if (user != null) {
                            String newJwt = jwtUtil.generateTokenWithTenantId(
                                userDetailsService.loadUserByUsername(user.getPid()),
                                user.getPid(), defaultTenantId);
                            response = new AuthenticationResponse(newJwt, user.getId(), user.getPid(),
                                user.getNickName(), defaultTenantId, "member");
                        }
                    } catch (Exception e) {
                        log.warn("Failed to auto-join user to default tenant: {}", e.getMessage());
                    }
                }
            }

            return ApiResponse.success(response);

    }

    @PostMapping("/login/sms")
    @ResponseBody
    public ApiResponse<AuthenticationResponse> loginBySms(
            @RequestBody AuthStrategyRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);
        if (!loginRateLimiter.isAllowed(ip, request.getEmail())) {
            return ApiResponse.error(ResponseCode.BadParam, "Too many login attempts. Please try again later.", null);
        }
        request.setChannelCode("sms");
        request.setIpAddress(ip);
        request.setUserAgent(httpRequest.getHeader("User-Agent"));
        return ApiResponse.success(authService.authenticateByChannel(request));
    }

    @PostMapping("/login/email-code")
    @ResponseBody
    public ApiResponse<AuthenticationResponse> loginByEmailCode(
            @RequestBody AuthStrategyRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);
        if (!loginRateLimiter.isAllowed(ip, request.getEmail())) {
            return ApiResponse.error(ResponseCode.BadParam, "Too many login attempts. Please try again later.", null);
        }
        request.setChannelCode("email_code");
        request.setIpAddress(ip);
        request.setUserAgent(httpRequest.getHeader("User-Agent"));
        return ApiResponse.success(authService.authenticateByChannel(request));
    }

    private String extractIp(HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank()) {
            // X-Forwarded-For may contain multiple IPs; take the first one
            return ip.split(",")[0].trim();
        }
        ip = httpRequest.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        return httpRequest.getRemoteAddr();
    }
    
    /**
     * 获取当前用户完整信息（包括Permission）
     * 
     * 企业级安全设计：
     * 1. 从JWT中提取用户ID（后端验证签名）
     * 2. 从数据库获取最新用户信息
     * 3. 获取用户的实时Permission（支持动态变更）
     * 4. 一次性返回所有信息
     * 
     * @return 用户信息和Permission
     */
    @GetMapping("/me")
    @ResponseBody
    @Operation(summary = "Get current user", description = "Returns the authenticated user's profile, roles, and permissions.")
    public ApiResponse<UserInfoResponse> getCurrentUser() {
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        Long tenantId = MetaContext.getCurrentTenantId();

        UserInfoResponse response = userInfoService.buildCurrentUserInfo(userId, userPid, tenantId);
        return ApiResponse.success(response);
    }

    @PostMapping("/forgot-password")
    @ResponseBody
    public ApiResponse<Void> forgotPassword(@jakarta.validation.Valid @RequestBody ForgotPasswordRequest request,
                                             HttpServletRequest httpRequest) {
        // Rate limit: max 3 forgot-password requests per IP per minute (prevents email bombing)
        String ip = extractIp(httpRequest);
        if (!apiRateLimiter.isAllowed("forgot-pwd:ip:" + ip, 3)) {
            return ApiResponse.error(ResponseCode.BadParam, "Too many requests. Please try again later.", null);
        }
        passwordManagementService.initiatePasswordReset(request.getEmail());
        // Always return success to avoid email enumeration
        return ApiResponse.success(null);
    }

    @PostMapping("/reset-password")
    @ResponseBody
    public ApiResponse<Void> resetPassword(@jakarta.validation.Valid @RequestBody ResetPasswordRequest request,
                                            HttpServletRequest httpRequest) {
        // Rate limit: max 5 reset-password attempts per IP per minute (prevents token brute force)
        String ip = extractIp(httpRequest);
        if (!apiRateLimiter.isAllowed("reset-pwd:ip:" + ip, 5)) {
            return ApiResponse.error(ResponseCode.BadParam, "Too many requests. Please try again later.", null);
        }
        passwordManagementService.resetPasswordWithToken(request.getToken(), request.getNewPassword());
        return ApiResponse.success(null);
    }

}
