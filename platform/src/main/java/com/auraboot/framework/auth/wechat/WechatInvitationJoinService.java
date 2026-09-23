package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.dto.WechatInvitationJoinRequest;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.strategy.WechatMiniAuthStrategy;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WeChat invitation join: an unbound account accepting a tenant invitation can
 * provision its platform user without email/password. Membership and role
 * assignment are driven exclusively by the server-side invitation.
 *
 * Idempotent end to end: an already-bound WeChat just joins (or re-enters) the
 * tenant; role assignment only adds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatInvitationJoinService {

    private final WechatMiniClient wechatMiniClient;
    private final WechatMiniIdentityService wechatMiniIdentityService;
    private final UserService userService;
    private final TenantInviteService tenantInviteService;
    private final TenantMemberService tenantMemberService;
    private final UserRoleService userRoleService;
    private final LoginCompletionHelper loginCompletionHelper;

    @Transactional
    public AuthenticationResponse join(WechatInvitationJoinRequest request, String ipAddress, String userAgent) {
        if (request.getCode() == null || request.getCode().isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "wx.login code is required");
        }
        if (request.getInviteCode() == null || request.getInviteCode().isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "邀请码不能为空");
        }
        Invitation invite = tenantInviteService.findByInvitationCode(request.getInviteCode().trim());
        // Platform convention (TenantInviteServiceImpl): generated invites are born
        // status=active and that is the valid state — not the entity-comment PENDING.
        if (invite == null || Boolean.TRUE.equals(invite.getDeletedFlag()) || invite.getTenantId() == null
                || !"active".equalsIgnoreCase(invite.getStatus())
                || (invite.getExpiredAt() != null && invite.getExpiredAt().isBefore(Instant.now()))) {
            throw new BusinessException(ResponseCode.BadParam, "邀请码无效或已过期，请联系组织管理员");
        }

        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(request.getCode());
        User user = wechatMiniIdentityService.resolveLoginUserBySession(session);
        if (user == null) {
            String displayName = request.getDisplayName() == null || request.getDisplayName().isBlank()
                    ? "微信用户" : request.getDisplayName().trim();
            String syntheticEmail = "wx-" + Integer.toHexString(session.openid().hashCode()) + "-"
                    + Long.toHexString(System.currentTimeMillis()) + "@wx.wechat";
            user = userService.signUp(syntheticEmail, java.util.UUID.randomUUID().toString(), displayName, null);
            wechatMiniIdentityService.attachIdentity(user.getId(), session);
            log.info("wechat-invitation provisioned user {} for openid ...{}", user.getId(),
                    session.openid().substring(Math.max(0, session.openid().length() - 6)));
        }

        try {
            tenantMemberService.addMember(user.getId(), invite.getTenantId(), "active");
        } catch (BusinessException e) {
            log.info("wechat-invitation: user {} already a member of tenant {}", user.getId(), invite.getTenantId());
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(invite.getTenantId(), user.getId());
        List<String> roleCodes = invite.getRoleCodes() == null || invite.getRoleCodes().isBlank()
                ? List.of()
                : java.util.Arrays.stream(invite.getRoleCodes().split(","))
                        .map(String::trim)
                        .filter(code -> !code.isBlank())
                        .distinct()
                        .toList();
        Map<String, Boolean> assigned = new HashMap<>();
        for (String roleCode : roleCodes) {
            boolean ok = member != null && member.getPid() != null && userRoleService.assignRolesToMemberByRoleCodes(
                    member.getPid(), List.of(roleCode), invite.getTenantId(), invite.getInviterUserId());
            assigned.put(roleCode, ok);
            if (!ok) log.warn("wechat-invitation: role {} not assignable in tenant {}", roleCode, invite.getTenantId());
        }

        FederatedLoginContext federated = new FederatedLoginContext();
        federated.setTenantId(invite.getTenantId());
        federated.setLoginChannelCode(WechatMiniAuthStrategy.CHANNEL);
        log.info("wechat-invitation complete: userId={}, tenantId={}, roles={}", user.getId(), invite.getTenantId(), assigned);
        return loginCompletionHelper.completeLogin(user, federated, ipAddress, userAgent);
    }
}
