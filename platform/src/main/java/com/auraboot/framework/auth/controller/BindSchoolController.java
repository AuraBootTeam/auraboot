package com.auraboot.framework.auth.controller;

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
 * Authenticated school binding (two-step pure-wechat flow, FR-077/078).
 * Step 1 (login) self-provisions a bare account; step 2 (this endpoint) joins
 * the school tenant using the principal's 学校绑定码 and returns a
 * tenant-scoped session. Roles come from the invitation record — never from
 * the client (self-escalation guard).
 */
@Slf4j
@RestController
@RequestMapping("/api/tenant")
@Tag(name = "School Binding")
@RequiredArgsConstructor
public class BindSchoolController {

    private final TenantInviteService tenantInviteService;
    private final TenantMemberService tenantMemberService;
    private final UserRoleService userRoleService;
    private final UserService userService;
    private final SessionManagementService sessionManagementService;
    private final com.auraboot.framework.auth.util.JwtUtil jwtUtil;

    public record BindSchoolRequest(String inviteCode, String realName) {}

    @PostMapping("/bind-school")
    @Operation(summary = "Bind the authenticated account to a school via its join code")
    public ApiResponse<Map<String, Object>> bindSchool(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody BindSchoolRequest request) {
        if (authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResponseCode.BadParam, "缺少登录凭证");
        }
        String token = authorization.substring(7).trim();
        if (request.inviteCode() == null || request.inviteCode().isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "请输入学校绑定码");
        }
        Invitation invite = tenantInviteService.findByInvitationCode(request.inviteCode().trim());
        // Platform convention (TenantInviteServiceImpl): generated invites are born
        // status=active and that is the valid state — not the entity-comment PENDING.
        if (invite == null || Boolean.TRUE.equals(invite.getDeletedFlag()) || invite.getTenantId() == null
                || !"active".equalsIgnoreCase(invite.getStatus())
                || (invite.getExpiredAt() != null && invite.getExpiredAt().isBefore(Instant.now()))) {
            throw new BusinessException(ResponseCode.BadParam, "邀请码无效或已过期，请联系学校管理员");
        }

        // Bearer token → platform user. The JWT subject is the user pid — the same
        // identifier JwtAuthenticationFilter resolves via findByPid on every call
        // (JwtUtil.extractUserPid is an alias of extractIdentifier), not an email.
        User user = userService.findByPid(jwtUtil.extractIdentifier(token));
        if (user == null) throw new BusinessException(ResponseCode.BadParam, "账号不存在");
        Long userId = user.getId();

        if (request.realName() != null && !request.realName().isBlank()
                && ("微信用户".equals(user.getUserName()) || user.getUserName() == null)) {
            user.setUserName(request.realName().trim());
            userService.update(user);
        }

        try {
            tenantMemberService.addMember(user.getId(), invite.getTenantId(), "active");
        } catch (BusinessException e) {
            log.info("bind-school: user {} already a member of tenant {}", userId, invite.getTenantId());
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(invite.getTenantId(), userId);

        List<String> roleCodes = invite.getRoleCodes() == null || invite.getRoleCodes().isBlank()
                ? List.of() : List.of(invite.getRoleCodes().split(","));
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
        sessionManagementService.createSession(userId, newJwt, null, "wechat-mini-bind-school");

        Map<String, Object> out = new HashMap<>();
        out.put("jwt", newJwt);
        out.put("tenantId", invite.getTenantId());
        out.put("assigned", assigned);
        return ApiResponse.success(out);
    }
}
