package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.*;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.exception.UserException;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.auth.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Login, register, and current user APIs")
public class AuthController {

    private final AuthService authService;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private RoleMapper roleMapper;
    
    @Autowired
    private UserPermissionService userPermissionService;
    
    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private PasswordManagementService passwordManagementService;

    @Autowired(required = false)
    private SystemModeService systemModeService;

    @Autowired(required = false)
    private TenantMemberService tenantMemberService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserDetailsService userDetailsService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @ResponseBody
    @Operation(summary = "Password login", description = "Authenticate with email/username and password. Returns JWT token.")
    public ApiResponse<AuthenticationResponse> login(@RequestBody AuthenticationRequest request){


//            在 Spring Security 中，密码比对是由 Spring Security 的认证机制自动完成的.
//            具体来说，是在 DaoAuthenticationProvider 类中进行的。
//            DaoAuthenticationProvider 是 Spring Security 默认的认证提供者，
//            它负责调用 UserDetailsService 获取用户信息，并使用 PasswordEncoder 对用户输入的密码进行比对。

            AuthenticationResponse authenticate = authService.authenticate(request);

            ApiResponse<AuthenticationResponse> success = ApiResponse.success(authenticate);
            return success;


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
        request.setChannelCode("sms");
        request.setIpAddress(extractIp(httpRequest));
        request.setUserAgent(httpRequest.getHeader("User-Agent"));
        return ApiResponse.success(authService.authenticateByChannel(request));
    }

    @PostMapping("/login/email-code")
    @ResponseBody
    public ApiResponse<AuthenticationResponse> loginByEmailCode(
            @RequestBody AuthStrategyRequest request,
            HttpServletRequest httpRequest) {
        request.setChannelCode("email_code");
        request.setIpAddress(extractIp(httpRequest));
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
            // 1. 从ThreadLocal获取当前用户信息（由JwtAuthenticationFilter设置）
            Long userId = MetaContext.getCurrentUserId();
            String userPid = MetaContext.getCurrentUserPid();
            Long tenantId = MetaContext.getCurrentTenantId();

            
            // 2. 从数据库获取最新用户信息
            User user = userService.findByUserId(userId);

            // 3. 构建用户基本信息DTO
            UserInfoResponse.UserDTO userDTO = new UserInfoResponse.UserDTO();
            userDTO.setId(String.valueOf(user.getId()));
            userDTO.setPid(user.getPid());
            userDTO.setName(user.getNickName() != null ? user.getNickName() : user.getUserName());
            userDTO.setEmail(user.getEmail());
            userDTO.setMobile(user.getMobile());
            userDTO.setTenantId(tenantId);
            userDTO.setImgId(user.getImgId());
            
            // 4. 获取用户的实时角色和Permission
            List<Role> roles = List.of();
            List<String> permissionCodes = List.of();
            
            if (tenantId != null) {
                // 有租户：获取该租户下的角色和Permission
                roles = roleMapper.findByUserIdAndTenantId(user.getId(), tenantId);

                // Check for SUPER_ADMIN or TENANT_ADMIN roles
                boolean isSuperAdmin = roles.stream()
                        .anyMatch(r -> "super_admin".equals(r.getCode()));
                boolean isTenantAdmin = roles.stream()
                        .anyMatch(r -> "tenant_admin".equals(r.getCode()));

                if (isSuperAdmin || isTenantAdmin) {
                    // Super admin / tenant admin: return all permissions for this tenant
                    // selectList(null) uses MyBatis Plus auto tenant_id + deleted_flag filter
                    List<Permission> allPermissions = permissionMapper.selectList(null);
                    permissionCodes = allPermissions.stream()
                            .map(Permission::getCode)
                            .collect(Collectors.toList());
                } else {
                    // Normal user: only return assigned permissions
                    Set<Long> permissionIds = userPermissionService.getUserPermissionIds(userId);
                    if (!permissionIds.isEmpty()) {
                        List<Permission> permissions = permissionMapper.findByIds(
                                new ArrayList<>(permissionIds)
                        );
                        permissionCodes = permissions.stream()
                                .map(Permission::getCode)
                                .collect(Collectors.toList());
                    }
                }
            }
            
            // 5. 转换为DTO
            List<UserInfoResponse.RoleDTO> roleDTOs = roles.stream()
                .map(UserInfoResponse.RoleDTO::fromEntity)
                .collect(Collectors.toList());
            
            UserInfoResponse.PermissionsDTO permissionsDTO = new UserInfoResponse.PermissionsDTO(
                roleDTOs, 
                permissionCodes
            );
            
            // 6. 构建响应
            UserInfoResponse response = new UserInfoResponse(userDTO, permissionsDTO);

            return ApiResponse.success(response);
    }

    @PostMapping("/forgot-password")
    @ResponseBody
    public ApiResponse<Void> forgotPassword(@jakarta.validation.Valid @RequestBody ForgotPasswordRequest request) {
        passwordManagementService.initiatePasswordReset(request.getEmail());
        // Always return success to avoid email enumeration
        return ApiResponse.success(null);
    }

    @PostMapping("/reset-password")
    @ResponseBody
    public ApiResponse<Void> resetPassword(@jakarta.validation.Valid @RequestBody ResetPasswordRequest request) {
        passwordManagementService.resetPasswordWithToken(request.getToken(), request.getNewPassword());
        return ApiResponse.success(null);
    }
}