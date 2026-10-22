package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.wechat.WechatMiniIdentityService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * WeChat mini-program login. A configured product policy requires phone admission;
 * other installations keep their own platform onboarding flow.
 */
@Component
@RequiredArgsConstructor
public class WechatMiniAuthStrategy implements AuthStrategy {

    public static final String CHANNEL = "wechat_mini";

    private final WechatMiniIdentityService wechatMiniIdentityService;
    private final LoginCompletionHelper loginCompletionHelper;
    private final com.auraboot.framework.auth.wechat.WechatMiniPhoneEnrollmentService phoneEnrollment;

    @Override
    public String getChannelCode() {
        return CHANNEL;
    }

    @Override
    public AuthenticationResponse authenticate(AuthStrategyRequest request) {
        if(phoneEnrollment.isConfigured() || request.getEnrollmentCode()!=null || request.getPhoneCode()!=null) return phoneEnrollment.login(request);
        String code = request.getCode();
        if (code == null || code.isBlank()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed, "wx.login code is required");
        }
        // Two-step pure-wechat flow (SOT 05): an unknown WeChat self-provisions a
        // bare account (no tenant, no roles) and logs in; the app then dispatches
        // by product-specific tenant-invitation state.
        User user = wechatMiniIdentityService.selfProvision(code);
        FederatedLoginContext context = new FederatedLoginContext();
        context.setApplicationCode("business-mobile");
        context.setLoginChannelCode("default-business-mobile");
        return loginCompletionHelper.completeLogin(user, context, request.getIpAddress(), request.getUserAgent());
    }
}
