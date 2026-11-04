package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Data;
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
public class WechatPcClient {

    public record WxWebUser(String openid, String unionid, String nickname, String headimgurl) {}

    private final WechatPcProperties properties;
    private final WechatOAuthClient oauth;

    private final RestClient profileClient;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatPcClient(WechatPcProperties properties, WechatOAuthClient oauth) {
        this(properties, oauth, RestClient.create());
    }

    WechatPcClient(WechatPcProperties properties, WechatOAuthClient oauth, RestClient profileClient) {
        this.properties = properties; this.oauth = oauth; this.profileClient = profileClient;
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
        var token = oauth.exchange(properties.getAppId(), properties.getAppSecret(), code,
                "snsapi_login", properties.getApiBaseUrl());
        String base = trimSlash(properties.getApiBaseUrl());
        String userUri = base + "/sns/userinfo?access_token=" + enc(token.accessToken())
                + "&openid=" + enc(token.openId()) + "&lang=zh_CN";
        UserInfoResponse user;
        try {
            String userBody = profileClient.get().uri(URI.create(userUri)).retrieve().body(String.class);
            user = json().readValue(userBody, UserInfoResponse.class);
        } catch (org.springframework.web.client.RestClientException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("WeChat userinfo call failed (identity fields already obtained): {}", e.getClass().getSimpleName());
            user = null;
        }
        return new WxWebUser(token.openId(), token.unionId(),
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

