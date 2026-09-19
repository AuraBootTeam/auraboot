package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Resolution and binding of WeChat mini-program identities to platform users.
 *
 * Login resolution (PRD-agnostic, per the open-platform UnionID model):
 *  1. (provider, appId, openid) hit  → that user logs in.
 *  2. no openid hit, but the session carries a unionid that an existing identity
 *     already holds → the SAME user; attach this app's openid to them.
 *  3. neither → null (caller decides: login endpoint rejects with
 *     "bind first"; the bind endpoint attaches to the current user).
 *
 * UnionID tolerance: absent unionid never blocks login — openid-only identities
 * are created and merged later when a unionid first appears.
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

    /** Resolve the platform user for a wx.login code, or null when unbound. */
    @Transactional
    public User resolveLoginUser(String jsCode) {
        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(jsCode);
        AuthIdentity identity = findByOpenid(session.openid());
        if (identity != null) {
            touchLastLogin(identity);
            return userMapper.selectById(identity.getUserId());
        }
        if (!isBlank(session.unionid())) {
            AuthIdentity byUnion = findByUnionid(session.unionid());
            if (byUnion != null) {
                // Same WeChat user, first login from this app — attach the openid.
                AuthIdentity attached = createIdentity(byUnion.getUserId(), session, byUnion.getUnionid());
                touchLastLogin(attached);
                log.info("WeChat identity attached via unionid: userId={} app={}",
                        byUnion.getUserId(), session.openid());
                return userMapper.selectById(byUnion.getUserId());
            }
        }
        return null;
    }

    /** Bind the WeChat identity to an existing, authenticated user. Idempotent per openid. */
    @Transactional
    public void bindToUser(String jsCode, Long userId) {
        WechatMiniClient.WxSession session = wechatMiniClient.code2Session(jsCode);
        AuthIdentity existing = findByOpenid(session.openid());
        if (existing != null) {
            if (!existing.getUserId().equals(userId)) {
                throw new RootUnCheckedException(ResponseCode.BadParam,
                        "This WeChat account is already bound to another user");
            }
            touchLastLogin(existing);
            return;
        }
        createIdentity(userId, session, session.unionid());
    }

    private AuthIdentity findByOpenid(String openid) {
        return authIdentityMapper.selectOne(new QueryWrapper<AuthIdentity>()
                .eq("provider", PROVIDER_WECHAT_MINI)
                .eq("openid", openid)
                .last("LIMIT 1"));
    }

    private AuthIdentity findByUnionid(String unionid) {
        return authIdentityMapper.selectOne(new QueryWrapper<AuthIdentity>()
                .eq("provider", PROVIDER_WECHAT_MINI)
                .eq("unionid", unionid)
                .isNotNull("unionid")
                .last("LIMIT 1"));
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
