package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.dto.UserSpaceDTO;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.auraboot.framework.common.constant.ResponseCode.UnreachableCodePathException;

/**
 * Tenant Selection Controller — handles space selection after login.
 *
 * <p>Three actions:
 * <ul>
 *   <li>create — create a new tenant (public cloud mode)</li>
 *   <li>join — join existing tenant via invite code</li>
 *   <li>select — select an existing space (multi-tenant login)</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant-selection")
@RequiredArgsConstructor
public class TenantSelectionController {

    private final UserService userService;
    private final TenantApplicationService tenantApplicationService;
    private final TenantMemberService tenantMemberService;
    private final TenantService tenantService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final JwtUtil jwtUtil;
    private final SessionManagementService sessionManagementService;

    /**
     * List all spaces (tenants) the current user belongs to.
     * Used by the Space Selection UI after login when tenantId is null.
     */
    @GetMapping("/my-spaces")
    public ApiResponse<List<UserSpaceDTO>> getMySpaces(@CurrentUserId Long userId) {
        List<Long> tenantIds = tenantMemberService.getTenantIdsByUserId(userId);
        if (tenantIds.isEmpty()) {
            return ApiResponse.success(Collections.emptyList());
        }

        List<UserSpaceDTO> spaces = new ArrayList<>();
        for (Long tenantId : tenantIds) {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant == null) continue;

            boolean isSystem = "System".equals(tenant.getName());

            // Get member's roles in this tenant (resolve memberId → roleId → role code)
            TenantMember tenantMember = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
            Long memberId = tenantMember != null ? tenantMember.getId() : null;
            List<UserRole> userRoles = memberId != null
                    ? userRoleService.list(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getMemberId, memberId)
                        .eq(UserRole::getTenantId, tenantId)
                        .eq(UserRole::getDeletedFlag, false))
                    : Collections.emptyList();
            List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
            List<String> roleCodes;
            if (roleIds.isEmpty()) {
                roleCodes = Collections.emptyList();
            } else {
                List<Role> roles = roleService.listByIds(roleIds);
                roleCodes = roles.stream()
                        .map(Role::getCode)
                        .filter(c -> c != null)
                        .toList();
            }

            spaces.add(UserSpaceDTO.builder()
                    .tenantId(tenantId)
                    .tenantName(tenant.getName())
                    .tenantDisplayName(tenant.getDisplayName())
                    .spaceType(isSystem ? "platform" : "business")
                    .roleCodes(roleCodes)
                    .isDefault(!isSystem && spaces.stream().noneMatch(s -> "business".equals(s.getSpaceType())))
                    .build());
        }

        return ApiResponse.success(spaces);
    }

    @PostMapping("/process")
    public ApiResponse<TenantSelectionResponse> processTenantSelection(
            @RequestBody TenantSelectionRequest request,
            @CurrentUserId Long userId) {

        User user = userService.findByUserId(userId);

        TenantSelectionResponse response;

        switch (request.getAction()) {
            case "create" -> response = tenantApplicationService.createTenantForUser(request, user);
            case "join" -> response = tenantApplicationService.joinTenantByInviteCode(request, user);
            case "select" -> response = selectSpace(request, user);
            default -> throw new RootUnCheckedException(UnreachableCodePathException);
        }

        return ApiResponse.success(response);
    }

    /**
     * Select an existing space (tenant) — generates a new JWT with the selected tenantId.
     */
    private TenantSelectionResponse selectSpace(TenantSelectionRequest request, User user) {
        Long tenantId = request.getTenantId();
        if (tenantId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "tenantId is required for 'select' action");
        }

        // Verify user is a member of this tenant
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        if (member == null || !"active".equalsIgnoreCase(member.getStatus())) {
            throw new RootUnCheckedException(ResponseCode.FORBIDDEN, "User is not an active member of this tenant");
        }

        Tenant tenant = tenantService.getById(tenantId);
        if (tenant == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "Tenant not found");
        }

        // Generate new JWT with selected tenantId and memberId
        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                user.getEmail(), "",
                Collections.singletonList(new SimpleGrantedAuthority("role_user")));
        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String jwt = jwtUtil.generateTokenWithTenantId(userDetails, user.getPid(), tenantId, member.getId(), securityVersion);

        // Register new JWT in session store so JwtAuthenticationFilter.isSessionValid() passes
        sessionManagementService.createSession(user.getId(), jwt, null, "space-switch");

        TenantSelectionResponse response = new TenantSelectionResponse();
        response.setStatus("success");
        response.setTenantId(tenantId);
        response.setTenantName(tenant.getDisplayName());
        response.setJwt(jwt);
        response.setMessage("Space selected: " + tenant.getDisplayName());

        log.info("User {} selected space: tenant={} ({})", user.getId(), tenantId, tenant.getName());

        return response;
    }
}