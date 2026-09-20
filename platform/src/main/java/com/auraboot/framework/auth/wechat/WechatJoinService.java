package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.dto.WechatJoinRequest;
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
 * Pure-wechat school join (FR-077/078): an unbound WeChat account scanning the
 * school teacher code auto-provisions its platform user — no email, no password,
 * no admin pre-provisioning. The invitation (existing tenant invite code) is the
 * pointer to the school tenant; role assignment is driven by the join request so
 * the platform stays product-agnostic.
 *
 * Idempotent end to end: an already-bound WeChat just joins (or re-enters) the
 * tenant; role assignment only adds.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatJoinService {

    private final WechatMiniClient wechatMiniClient;
    private final WechatMiniIdentityService wechatMiniIdentityService;
    private final UserService userService;
    private final TenantInviteService tenantInviteService;
    private final TenantMemberService tenantMemberService;
    private final UserRoleService userRoleService;
    private final LoginCompletionHelper loginCompletionHelper;

    @Transactional
    public AuthenticationResponse join(WechatJoinRequest request, String ipAddress, String userAgent) {
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
            throw new BusinessException(ResponseCode.BadParam, "邀请码无效或已过期，请联系学校管理员");
        }

        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(request.getCode());
        User user = wechatMiniIdentityService.resolveLoginUserBySession(session);
        if (user == null) {
            String realName = request.getRealName() == null || request.getRealName().isBlank()
                    ? "微信老师" : request.getRealName().trim();
            String syntheticEmail = "wx-" + Integer.toHexString(session.openid().hashCode()) + "-"
                    + Long.toHexString(System.currentTimeMillis()) + "@wx.wechat";
            user = userService.signUp(syntheticEmail, java.util.UUID.randomUUID().toString(), realName, null);
            wechatMiniIdentityService.attachIdentity(user.getId(), session);
            log.info("wechat-join provisioned user {} for openid ...{}", user.getId(),
                    session.openid().substring(Math.max(0, session.openid().length() - 6)));
        }

        try {
            tenantMemberService.addMember(user.getId(), invite.getTenantId(), "active");
        } catch (BusinessException e) {
            log.info("wechat-join: user {} already a member of tenant {}", user.getId(), invite.getTenantId());
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(invite.getTenantId(), user.getId());
        List<String> roleCodes = request.getRoleCodes() == null ? List.of() : request.getRoleCodes();
        Map<String, Boolean> assigned = new HashMap<>();
        for (String roleCode : roleCodes) {
            boolean ok = member != null && member.getPid() != null && userRoleService.assignRolesToMemberByRoleCodes(
                    member.getPid(), List.of(roleCode), invite.getTenantId(), invite.getInviterUserId());
            assigned.put(roleCode, ok);
            if (!ok) log.warn("wechat-join: role {} not assignable in tenant {}", roleCode, invite.getTenantId());
        }

        FederatedLoginContext federated = new FederatedLoginContext();
        federated.setTenantId(invite.getTenantId());
        federated.setLoginChannelCode(WechatMiniAuthStrategy.CHANNEL);
        log.info("wechat-join complete: userId={}, tenantId={}, roles={}", user.getId(), invite.getTenantId(), assigned);
        return loginCompletionHelper.completeLogin(user, federated, ipAddress, userAgent);
    }
}
