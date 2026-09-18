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
        User user = wechatMiniIdentityService.resolveLoginUser(code);
        if (user == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "This WeChat account is not bound yet — log in with email/password once and bind WeChat");
        }
        return loginCompletionHelper.completeLogin(user, request.getIpAddress(), request.getUserAgent());
    }
}
