package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.auth.wechat.WechatPcClient.WxWebUser;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * PC website-app identity resolution and binding (provider wechat_web).
 *
 * Same open-platform UnionID model as the mini program: an existing wechat_web
 * identity logs in; a unionid shared with a wechat_mini identity attaches the
 * web openid to the SAME platform user — the cross-device same-account promise.
 * In self-service tenant mode, an unbound scan creates a tenantless account so
 * the user can create a school. Other modes retain the bind-first behavior.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatPcIdentityService {

    public static final String PROVIDER_WECHAT_WEB = "wechat_web";

    private final AuthIdentityMapper authIdentityMapper;
    private final UserMapper userMapper;
    private final WechatPcClient wechatPcClient;
    private final WechatPcProperties wechatPcProperties;
    private final UserService userService;
    private final SystemModeService systemModeService;

    @Value("${aura.wechat.mini.app-id:}")
    private String miniAppId = "";

    /** Resolve the platform user for a QR-scan callback code; self-provision when allowed. */
    @Transactional
    public User resolveLoginUser(String code) {
        WxWebUser wx = wechatPcClient.exchange(code);
        var resolution = lookup(wx);
        AuthIdentity identity = resolution.identity();
        if (resolution.openidMatched()) {
            if (isBlank(identity.getUnionid()) && !isBlank(wx.unionid())) {
                identity.setUnionid(wx.unionid());
            }
            touch(identity);
            return userMapper.selectById(identity.getUserId());
        }
        if (identity != null) {
            AuthIdentity attached = create(identity.getUserId(), wx);
            touch(attached);
            log.info("WeChat web identity attached via unionid: userId={}", identity.getUserId());
            return userMapper.selectById(identity.getUserId());
        }
        if (!systemModeService.isTenantSelfProvisioningAllowed()) {
            return null;
        }
        // No membership or role is granted here. Tenant onboarding is a separate,
        // authenticated step; a unique synthetic credential cannot be used to log in.
        String email = "wx-web-" + UUID.randomUUID() + "@wx.wechat";
        User user = userService.signUp(email, UUID.randomUUID() + "Aa1!", "微信用户", null);
        create(user.getId(), wx);
        log.info("WeChat web account self-provisioned: userId={}", user.getId());
        return user;
    }

    /** Bind the web identity to an existing authenticated user (idempotent per openid). */
    @Transactional
    public void bindToUser(String code, Long userId) {
        WxWebUser wx = wechatPcClient.exchange(code);
        var resolution = lookup(wx);
        AuthIdentity existing = resolution.identity();
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new RootUnCheckedException(ResponseCode.BadParam,
                        "This WeChat account is already bound to another user");
            }
            if (resolution.openidMatched()) {
                touch(existing);
            } else {
                create(userId, wx);
            }
            return;
        }
        create(userId, wx);
    }

    private WechatIdentityLookup.Resolution lookup(WxWebUser wx) {
        return WechatIdentityLookup.resolve(authIdentityMapper, PROVIDER_WECHAT_WEB,
                wechatPcProperties.getAppId(), wx.openid(), wx.unionid(),
                miniAppId, wechatPcProperties.getAppId());
    }

    private AuthIdentity create(Long userId, WxWebUser wx) {
        AuthIdentity identity = new AuthIdentity();
        identity.setPid(com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr());
        identity.setUserId(userId);
        identity.setProvider(PROVIDER_WECHAT_WEB);
        identity.setAppId(wechatPcProperties.getAppId());
        identity.setOpenid(wx.openid());
        identity.setUnionid(wx.unionid());
        identity.setLastLoginAt(Instant.now());
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        authIdentityMapper.insert(identity);
        return identity;
    }

    private void touch(AuthIdentity identity) {
        identity.setLastLoginAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        authIdentityMapper.updateById(identity);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
