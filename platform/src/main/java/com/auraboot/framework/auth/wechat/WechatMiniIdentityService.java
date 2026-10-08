package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * WeChat mini-program identity binding: one openid (per app) to one platform
 * user. Two-step login (SOT 05): an unknown WeChat self-provisions a bare
 * account here; tenant invitation acceptance happens later via the
 * authenticated invitation flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WechatMiniIdentityService {

    public static final String PROVIDER_WECHAT_MINI = "wechat_mini";

    private final AuthIdentityMapper authIdentityMapper;
    private final UserMapper userMapper;
    private final WechatMiniClient wechatMiniClient;
    private final WechatMiniProperties wechatMiniProperties;
    private final UserService userService;

    @Value("${aura.wechat.pc.app-id:}")
    private String webAppId = "";

    /** Resolve the platform user for a wx.login code, or null when unbound. */
    @Transactional
    public User resolveLoginUser(String jsCode) {
        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(jsCode);
        return resolveLoginUserBySession(session);
    }

    /** Resolve the platform user behind an already-exchanged session (null when unbound). */
    @Transactional
    public User resolveLoginUserBySession(WechatMiniClient.WxSession session) {
        var resolution = lookup(session);
        AuthIdentity identity = resolution.identity();
        if (resolution.openidMatched()) {
            // Backfill: identities created before the app joined the WeChat open
            // platform carry no unionid; once logins start delivering it, persist it
            // on first sight so cross-app resolution (PC scan ↔ mini) works for the
            // existing row instead of only for newly created ones.
            if (isBlank(identity.getUnionid()) && !isBlank(session.unionid())) {
                identity.setUnionid(session.unionid());
                log.info("WeChat mini identity unionid backfilled: userId={}", identity.getUserId());
            }
            touchLastLogin(identity);
            return userMapper.selectById(identity.getUserId());
        }
        if (identity != null) {
            AuthIdentity attached = createIdentity(identity.getUserId(), session, session.unionid());
            touchLastLogin(attached);
            log.info("WeChat mini identity attached via unionid: userId={}", identity.getUserId());
            return userMapper.selectById(identity.getUserId());
        }
        return null;
    }

    /**
     * Pure-wechat login: an unknown WeChat account gets a bare platform account —
     * synthetic email, unusable random password, no tenant, no roles. The school
     * tenant membership happens later via the authenticated invitation flow.
     */
    @Transactional
    public User selfProvision(String jsCode) {
        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(jsCode);
        User existing = resolveLoginUserBySession(session);
        if (existing != null) return existing;
        String email = "wx-" + Integer.toHexString(session.openid().hashCode()) + "-"
                + Long.toHexString(System.currentTimeMillis()) + "@wx.wechat";
        try {
            User user = userService.signUp(email, java.util.UUID.randomUUID().toString(), "微信用户", null);
            createIdentity(user.getId(), session, session.unionid());
            log.info("wechat self-provision: userId={} openid ...{}",
                    user.getId(), session.openid().substring(Math.max(0, session.openid().length() - 6)));
            return user;
        } catch (Exception e) {
            User byEmail = userMapper.selectOne(new QueryWrapper<User>().eq("email", email).last("LIMIT 1"));
            if (byEmail != null) return byEmail;
            throw e;
        }
    }

    /** Bind the WeChat identity to an existing, authenticated user. Idempotent per openid. */
    @Transactional
    public void bindToUser(String jsCode, Long userId) {
        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(jsCode);
        var resolution = lookup(session);
        AuthIdentity existing = resolution.identity();
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new RootUnCheckedException(ResponseCode.BadParam,
                        "This WeChat account is already bound to another user");
            }
            if (!resolution.openidMatched()) {
                createIdentity(userId, session, session.unionid());
            } else {
                touchLastLogin(existing);
            }
            return;
        }
        createIdentity(userId, session, session.unionid());
    }

    /** Attach an identity from an already-exchanged session (join flow). */
    @Transactional
    public void attachIdentity(Long userId, WechatMiniClient.WxSession session) {
        var resolution = lookup(session);
        AuthIdentity existing = resolution.identity();
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new RootUnCheckedException(ResponseCode.BadParam,
                        "This WeChat account is already bound to another user");
            }
            if (!resolution.openidMatched()) {
                createIdentity(userId, session, session.unionid());
            } else {
                touchLastLogin(existing);
            }
            return;
        }
        createIdentity(userId, session, session.unionid());
    }

    private WechatIdentityLookup.Resolution lookup(WechatMiniClient.WxSession session) {
        return WechatIdentityLookup.resolve(authIdentityMapper, PROVIDER_WECHAT_MINI,
                wechatMiniProperties.getAppId(), session.openid(), session.unionid(),
                wechatMiniProperties.getAppId(), webAppId);
    }

    private AuthIdentity createIdentity(Long userId, WechatMiniClient.WxSession session, String unionid) {
        AuthIdentity identity = new AuthIdentity();
        identity.setPid(com.baomidou.mybatisplus.core.toolkit.IdWorker.getIdStr());
        identity.setUserId(userId);
        identity.setProvider(PROVIDER_WECHAT_MINI);
        identity.setAppId(wechatMiniProperties.getAppId());
        identity.setOpenid(session.openid());
        identity.setUnionid(isBlank(session.unionid()) ? unionid : session.unionid());
        identity.setLastLoginAt(Instant.now());
        identity.setCreatedAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        authIdentityMapper.insert(identity);
        return identity;
    }

    private void touchLastLogin(AuthIdentity identity) {
        identity.setLastLoginAt(Instant.now());
        identity.setUpdatedAt(Instant.now());
        authIdentityMapper.updateById(identity);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
