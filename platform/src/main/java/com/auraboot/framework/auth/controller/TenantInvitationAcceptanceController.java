package com.auraboot.framework.auth.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Authenticated tenant-invitation acceptance for staged identity flows.
 * Step 1 authenticates or provisions a bare account; step 2 joins
 * the invited tenant and returns a
 * tenant-scoped session. Roles come from the invitation record — never from
 * the client (self-escalation guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "Tenant Invitations")
@RequiredArgsConstructor
public class TenantInvitationAcceptanceController {

    private final TenantInviteService tenantInviteService;
    private final TenantMemberService tenantMemberService;
    private final UserRoleService userRoleService;
    private final UserService userService;
    private final SessionManagementService sessionManagementService;
    private final com.auraboot.framework.auth.util.JwtUtil jwtUtil;

    public record InvitationAcceptanceRequest(
            String inviteCode,
            @JsonAlias("realName") String displayName) {}

    @PostMapping({"/invitations/accept", "/bind-school"})
    @Operation(summary = "Accept a tenant invitation for the authenticated account")
    public ApiResponse<Map<String, Object>> acceptInvitation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody InvitationAcceptanceRequest request) {
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResponseCode.BadParam, "缺少登录凭证");
        }
        String token = authorization.substring(7).trim();
        if (request.inviteCode() == null || request.inviteCode().isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "请输入邀请码");
        }
        Invitation invite = tenantInviteService.findByInvitationCode(request.inviteCode().trim());
        // Platform convention (TenantInviteServiceImpl): generated invites are born
        // status=active and that is the valid state — not the entity-comment PENDING.
        if (invite == null || Boolean.TRUE.equals(invite.getDeletedFlag()) || invite.getTenantId() == null
                || !"active".equalsIgnoreCase(invite.getStatus())
                || (invite.getExpiredAt() != null && invite.getExpiredAt().isBefore(Instant.now()))) {
            throw new BusinessException(ResponseCode.BadParam, "邀请码无效或已过期，请联系组织管理员");
        }

        // Bearer token → platform user. The JWT subject is the user pid — the same
        // identifier JwtAuthenticationFilter resolves via findByPid on every call
        // (JwtUtil.extractUserPid is an alias of extractIdentifier), not an email.
        User user = userService.findByPid(jwtUtil.extractIdentifier(token));
        if (user == null) throw new BusinessException(ResponseCode.BadParam, "账号不存在");
        Long userId = user.getId();

        // A profile display name is never a globally unique login identifier.
        boolean needsName = user.getNickName() == null || user.getNickName().isBlank()
                || "微信用户".equals(user.getNickName());
        if (needsName && (request.displayName() == null || request.displayName().isBlank())) {
            throw new BusinessException(ResponseCode.BadParam, "请填写显示名称");
        }
        if (needsName) {
            String displayName = request.displayName().trim();
            if (displayName.length() > 24) {
                throw new BusinessException(ResponseCode.BadParam, "显示名称不能超过24个字");
            }
            user.setNickName(displayName);
            userService.update(user);
        }

        try {
            tenantMemberService.addMember(user.getId(), invite.getTenantId(), "active");
        } catch (BusinessException e) {
            log.info("invitation-accept: user {} already a member of tenant {}", userId, invite.getTenantId());
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(invite.getTenantId(), userId);

        List<String> roleCodes = invite.getRoleCodes() == null || invite.getRoleCodes().isBlank()
                ? List.of()
                : java.util.Arrays.stream(invite.getRoleCodes().split(","))
                        .map(String::trim)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        Map<String, Boolean> assigned = new HashMap<>();
        for (String roleCode : roleCodes) {
            boolean ok = member != null && member.getPid() != null
                    && userRoleService.assignRolesToMemberByRoleCodes(member.getPid(), List.of(roleCode), invite.getTenantId(), invite.getInviterUserId());
            assigned.put(roleCode, ok);
        }

        int securityVersion = user.getSecurityVersion() != null ? user.getSecurityVersion() : 0;
        String newJwt = jwtUtil.generateTokenWithTenantId(
                new CustomUserDetails(
                        user.getEmail(), user.getPassword() != null ? user.getPassword() : "", user.getId(), user.getPid(),
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("role_user")),
                        true, true, true, true),
                user.getPid(), invite.getTenantId(), member != null ? member.getId() : null, securityVersion);
        // JwtAuthenticationFilter requires a live session row for every bearer
        // token. Do not hand a tenant-scoped JWT to the mini program unless its
        // session was persisted; otherwise the next request is always a 401.
        sessionManagementService.createSession(userId, newJwt, null, "tenant-invitation-acceptance");

        Map<String, Object> out = new HashMap<>();
        out.put("jwt", newJwt);
        out.put("tenantId", invite.getTenantId());
        out.put("assigned", assigned);
        return ApiResponse.success(out);
    }
}
