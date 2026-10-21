package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.plugin.extension.VerifiedPhoneGrantAccessor;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A synchronous request capability, pinned to its host-resolved tenant and account. */
public final class VerifiedPhoneGrantAccessorImpl implements VerifiedPhoneGrantAccessor {
    private final Long tenantId;
    private final Long userId;
    private final String userPid;
    private final WechatMiniIdentityService identities;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private VerifiedPhoneGrantAccessorImpl(Long tenantId, Long userId, String userPid, WechatMiniIdentityService identities) {
        this.tenantId = tenantId; this.userId = userId; this.userPid = userPid; this.identities = identities;
    }

    public static void install(Map<String,Object> settings, Long tenantId, Long userId, boolean dryRun,
                               WechatMiniIdentityService identities) {
        settings.remove(SETTINGS_KEY);
        if (dryRun || !Boolean.TRUE.equals(settings.get(OPT_IN_HANDLER_PARAM)) || identities == null) return;
        String pid = MetaContext.exists() ? MetaContext.getCurrentUserPid() : null;
        if (tenantId == null || userId == null || userId <= 0 || pid == null || pid.isBlank()
                || !Objects.equals(tenantId, MetaContext.getCurrentTenantId())
                || !Objects.equals(userId, MetaContext.getCurrentUserId()) || MetaContext.isImpersonating())
            throw new SecurityException("PHONE_GRANT_CONTEXT_REQUIRED");
        settings.put(SETTINGS_KEY, new VerifiedPhoneGrantAccessorImpl(tenantId, userId, pid, identities));
    }

    @Override public String verifyCurrentPhone(String loginCode, String phoneCode) {
        if (!MetaContext.exists() || MetaContext.isImpersonating()
                || !Objects.equals(tenantId, MetaContext.getCurrentTenantId())
                || !Objects.equals(userId, MetaContext.getCurrentUserId())
                || !Objects.equals(userPid, MetaContext.getCurrentUserPid()))
            throw new SecurityException("PHONE_GRANT_CONTEXT_REQUIRED");
        if (loginCode == null || loginCode.isBlank() || phoneCode == null || phoneCode.isBlank())
            throw new IllegalArgumentException("PHONE_GRANT_CODES_REQUIRED");
        if (!consumed.compareAndSet(false, true)) throw new SecurityException("PHONE_GRANT_ALREADY_CONSUMED");
        try {
            var phone = identities.verifyCurrentUserPhoneGrant(loginCode, phoneCode);
            if (phone == null || phone.countryCode() == null || phone.nationalNumber() == null
                    || !phone.countryCode().matches("[1-9][0-9]{0,3}") || !phone.nationalNumber().matches("[0-9]{4,14}")
                    || phone.countryCode().length() + phone.nationalNumber().length() > 15)
                throw new SecurityException("PHONE_GRANT_REJECTED");
            return "+" + phone.countryCode() + phone.nationalNumber();
        } catch (RootUnCheckedException rejected) {
            throw new SecurityException("PHONE_GRANT_REJECTED");
        }
    }
}
