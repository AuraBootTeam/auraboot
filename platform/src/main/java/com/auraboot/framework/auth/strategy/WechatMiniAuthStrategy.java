package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * WeChat mini-program login (channel wechat_mini).
 *
 * The request `code` field carries the wx.login() code. The WeChat identity must
 * already be bound to a platform user (bind happens after an authenticated
 * password login via /api/auth/wechat/mini/bind) — an unbound code is rejected
 * with a bind-first hint rather than silently creating an account.
 */
@Component
@RequiredArgsConstructor
public class WechatMiniAuthStrategy implements AuthStrategy {

    public static final String CHANNEL = "wechat_mini";

    private final WechatMiniIdentityService wechatMiniIdentityService;
    private final LoginCompletionHelper loginCompletionHelper;

    @Override
    public String getChannelCode() {
        return CHANNEL;
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        String code = request.getCode();
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "wx.login code is required");
        }
        // Two-step pure-wechat flow (SOT 05): an unknown WeChat self-provisions a
        // bare account (no tenant, no roles) and logs in; the app then dispatches
        // by product-specific tenant-invitation state.
        User user = wechatMiniIdentityService.selfProvision(code);
        return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());
    }
}
