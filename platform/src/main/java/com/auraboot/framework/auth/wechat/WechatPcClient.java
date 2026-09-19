package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * WeChat website-app OAuth client for PC QR login: builds the qrconnect URL,
 * exchanges the callback code for an access token and fetches the user's
 * openid/unionid (+ nickname/headimg, which are optional profile hints only —
 * never used as identity).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatPcClient {

    public record WxWebUser(String openid, String unionid, String nickname, String headimgurl) {}

    private final WechatPcProperties properties;

    private RestClient restClient() {
        return RestClient.create();
    }

    /** Build the QR-code authorize URL the browser redirects to. */
    public String qrConnectUrl(String state, String redirectUri) {
        String base = trimSlash(properties.getQrConnectBaseUrl());
        String effective = redirectUri == null || redirectUri.isBlank()
                ? properties.getRedirectUri() : redirectUri;
        return base + "/connect/qrconnect?appid=" + enc(properties.getAppId())
                + "&redirect_uri=" + enc(effective)
                + "&response_type=code&scope=snsapi_login"
                + "&state=" + enc(state) + "#wechat_redirect";
    }

    /** Exchange the callback code for the user identity. */
    public WxWebUser exchange(String code) {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "WeChat PC login is not configured (aura.wechat.pc.*)");
        }
        String base = trimSlash(properties.getApiBaseUrl());
        String tokenUri = base + "/sns/oauth2/access_token?appid=" + enc(properties.getAppId())
                + "&secret=" + enc(properties.getAppSecret())
                + "&code=" + enc(code) + "&grant_type=authorization_code";
        // WeChat answers with Content-Type: text/plain even for JSON bodies — the
        // Spring converters can't bind that to a POJO, so fetch the raw string and
        // parse explicitly.
        TokenResponse token;
        try {
            String body = restClient().get().uri(URI.create(tokenUri)).retrieve().body(String.class);
            token = json().readValue(body, TokenResponse.class);
        } catch (Exception e) {
            log.warn("WeChat access_token call failed: {}", e.getMessage());
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login exchange failed");
        }
        if (token == null || isBlank(token.getOpenid())) {
            int code2 = token == null || token.getErrcode() == null ? -1 : token.getErrcode();
            log.warn("WeChat access_token rejected: errcode={}", code2);
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login code rejected (" + code2 + ")");
        }
        String userUri = base + "/sns/userinfo?access_token=" + enc(token.getAccessToken())
                + "&openid=" + enc(token.getOpenid()) + "&lang=zh_CN";
        UserInfoResponse user;
        try {
            String userBody = restClient().get().uri(URI.create(userUri)).retrieve().body(String.class);
            user = json().readValue(userBody, UserInfoResponse.class);
        } catch (Exception e) {
            log.warn("WeChat userinfo call failed (identity fields already obtained): {}", e.getMessage());
            user = null;
        }
        return new WxWebUser(token.getOpenid(), token.getUnionid(),
                user == null ? null : user.getNickname(), user == null ? null : user.getHeadimgurl());
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.replaceAll("/+$", "");
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Data
    static class TokenResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("access_token")
        private String accessToken;
        private Integer expiresIn;
        private String refreshToken;
        private String openid;
        private String scope;
        private String unionid;
        private Integer errcode;
        private String errmsg;
    }

    @Data
    static class UserInfoResponse {
        private String openid;
        private String nickname;
        private String headimgurl;
        private String unionid;
        private Integer errcode;
        }

    private static com.fasterxml.jackson.databind.ObjectMapper json() {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}

