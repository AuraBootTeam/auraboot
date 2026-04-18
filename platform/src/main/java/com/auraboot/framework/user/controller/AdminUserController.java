package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserProvisioningService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin User Management", description = "Administrator user management operations")
public class AdminUserController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordManagementService passwordManagementService;
    private final UserProvisioningService userProvisioningService;
    private final UserService userService;

    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Admin Create User (User Provisioning).
     * Creates a user account, adds to the current tenant, and assigns roles.
     * This is the primary user entry mode for private deployment / ERP / internal systems.
     */
    @PostMapping
    @Operation(summary = "Provision a new user in the current tenant")
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<UserProvisionResponse> provisionUser(
            @Valid @RequestBody UserProvisionRequest request,
            @CurrentUserId Long currentUserId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context — admin must be in a tenant");
        }
        UserProvisionResponse response = userProvisioningService.provision(request, tenantId, currentUserId);
        return ApiResponse.success(response);
    }

    /**
     * Tenant-scoped user search for picker UIs (MemberPicker, reference-field dropdown,
     * approval assignee selection, etc.).
     *
     * <p>Authenticated endpoint — authentication is enforced by the default Spring Security
     * filter chain; any tenant member may call it because picker selection is a UX need, not
     * an admin action. The query is strictly scoped to the caller's current tenant via
     * ab_tenant_member (status=active), so callers can never see users outside their tenant.</p>
     *
     * <p>Replaces the previously-removed public {@code /api/users/search} endpoint, which was
     * deleted during the 2026-04 security review because it lacked authentication.</p>
     */
    @GetMapping("/search")
    @Operation(summary = "Search users in the current tenant by keyword (for picker UIs)")
    public ApiResponse<List<UserSearchDTO>> search(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "20") int size) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "No tenant context — caller must be in a tenant");
        }
        List<UserSearchDTO> results = userService.searchInTenant(tenantId, keyword, size);
        return ApiResponse.success(results);
    }

    @PostMapping("/{userPid}/reset-password")
    @Operation(summary = "Admin reset user password")
    public ApiResponse<Map<String, String>> resetPassword(@PathVariable String userPid) {
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("pid", userPid);
        User user = userMapper.selectOne(qw);

        if (user == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "User not found");
        }

        // Generate random temporary password
        String tempPassword = generateRandomPassword(12);

        user.setPassword(passwordEncoder.encode(tempPassword));
        user.setMustChangePassword(true);
        user.setSecurityVersion((user.getSecurityVersion() != null ? user.getSecurityVersion() : 0) + 1);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("Admin reset password for user {}, mustChangePassword=true", userPid);

        return ApiResponse.success(Map.of("tempPassword", tempPassword));
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
