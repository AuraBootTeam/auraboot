package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dao.entity.AuthIdentity;
import com.auraboot.framework.auth.mapper.AuthIdentityMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import java.util.List;
import java.util.Objects;

/** Resolves only configured WeChat applications and rejects ambiguous ownership. */
final class WechatIdentityLookup {
    private WechatIdentityLookup() {}

    record Resolution(AuthIdentity identity, boolean openidMatched) {}

    static Resolution resolve(AuthIdentityMapper mapper, String provider, String appId,
                              String openid, String unionid, String miniAppId, String webAppId) {
        if (blank(appId) || blank(openid)) {
            throw conflict();
        }
        mapper.lockWechatIdentity(!blank(unionid) ? "wechat-union:" + unionid
                : "wechat-open:" + provider + ":" + appId + ":" + openid);
        List<AuthIdentity> direct = mapper.selectList(new QueryWrapper<AuthIdentity>()
                .eq("provider", provider).eq("app_id", appId).eq("openid", openid));
        requireUniqueOwner(direct);
        AuthIdentity identity = direct.isEmpty() ? null : direct.getFirst();
        if (identity != null && !blank(identity.getUnionid()) && !blank(unionid)
                && !identity.getUnionid().equals(unionid)) {
            throw conflict();
        }
        String effectiveUnionid = !blank(unionid) ? unionid
                : identity == null ? null : identity.getUnionid();
        if (blank(effectiveUnionid)) {
            return new Resolution(identity, identity != null);
        }
        QueryWrapper<AuthIdentity> query = new QueryWrapper<AuthIdentity>()
                .eq("unionid", effectiveUnionid);
        query.and(scope -> {
            if (!blank(miniAppId)) {
                scope.eq("provider", WechatMiniIdentityService.PROVIDER_WECHAT_MINI)
                        .eq("app_id", miniAppId);
            }
            if (!blank(webAppId)) {
                if (!blank(miniAppId)) scope.or();
                scope.eq("provider", WechatPcIdentityService.PROVIDER_WECHAT_WEB)
                        .eq("app_id", webAppId);
            }
        });
        List<AuthIdentity> matches = mapper.selectList(query);
        requireUniqueOwner(matches);
        if (identity != null && matches.stream()
                .anyMatch(match -> !Objects.equals(match.getUserId(), identity.getUserId()))) {
            throw conflict();
        }
        return new Resolution(identity != null ? identity
                : matches.isEmpty() ? null : matches.getFirst(), identity != null);
    }

    private static void requireUniqueOwner(List<AuthIdentity> identities) {
        if (identities.stream().anyMatch(identity -> identity.getUserId() == null)
                || identities.stream().map(AuthIdentity::getUserId).distinct().count() > 1) {
            throw conflict();
        }
    }

    private static RootUnCheckedException conflict() {
        return new RootUnCheckedException(ResponseCode.BadParam,
                "WeChat identity conflict; contact the platform administrator");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
