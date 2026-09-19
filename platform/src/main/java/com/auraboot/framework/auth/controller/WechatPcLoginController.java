package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.wechat.WechatPcClient;
import com.auraboot.framework.auth.wechat.WechatPcIdentityService;
import com.auraboot.framework.auth.wechat.WechatPcProperties;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.user.dao.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * PC WeChat QR login (open-platform website app).
 *  - GET  /api/auth/login/wechat-pc/qr-url → { url, state } for the browser to open
 *  - GET  /api/auth/login/wechat-pc/callback?code&state → platform JWT (WeChat redirects here)
 *  - POST /api/auth/wechat/pc/bind → bind to the currently authenticated user
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class WechatPcLoginController {

    private final WechatPcClient wechatPcClient;
    private final WechatPcIdentityService wechatPcIdentityService;
    private final WechatPcProperties wechatPcProperties;
    private final LoginCompletionHelper loginCompletionHelper;

    @GetMapping("/login/wechat-pc/status")
    public ApiResponse<Map<String, Object>> status() {
        boolean enabled = !isBlank(wechatPcProperties.getAppId())
                && !isBlank(wechatPcProperties.getAppSecret());
        return ApiResponse.success(Map.of("enabled", enabled));
    }

    @GetMapping("/login/wechat-pc/qr-url")
    public ApiResponse<Map<String, Object>> qrUrl(
            @RequestParam(value = "redirectUri", required = false) String redirectUri) {
        String state = UUID.randomUUID().toString().replace("-", "");
        String effective = isBlank(redirectUri) ? wechatPcProperties.getRedirectUri() : redirectUri;
        return ApiResponse.success(Map.of(
                "url", wechatPcClient.qrConnectUrl(state, effective),
                "state", state));
    }

    /**
     * Code exchange for the platform social-callback page (provider wechat_web).
     * Resolves the identity and issues the standard platform session payload.
     */
    @PostMapping("/login/social/wechat_web/callback")
    public ApiResponse<?> socialCallback(@RequestParam("code") String code,
                                         @RequestParam(value = "state", required = false) String state) {
        User user = wechatPcIdentityService.resolveLoginUser(code);
        if (user == null) {
            return ApiResponse.error(com.auraboot.framework.common.constant.ResponseCode.BadParam,
                    "This WeChat account is not bound yet — log in with email/password once and bind WeChat");
        }
        return ApiResponse.success(loginCompletionHelper.completeLogin(user, null, null));
    }

    @GetMapping("/login/wechat-pc/callback")
    public ApiResponse<?> callback(@RequestParam("code") String code,
                                   @RequestParam(value = "state", required = false) String state) {
        User user = wechatPcIdentityService.resolveLoginUser(code);
        if (user == null) {
            return ApiResponse.error(com.auraboot.framework.common.constant.ResponseCode.BadParam,
                    "This WeChat account is not bound yet — log in with email/password once and bind WeChat", null);
        }
        return ApiResponse.success(loginCompletionHelper.completeLogin(user, null, null));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PostMapping("/wechat/pc/bind")
    public ApiResponse<Map<String, Object>> bind(@RequestBody Map<String, String> body) {
        Long userId = MetaContext.getCurrentUserId();
        wechatPcIdentityService.bindToUser(body.get("code"), userId);
        return ApiResponse.success(Map.of("bound", true));
    }
}
