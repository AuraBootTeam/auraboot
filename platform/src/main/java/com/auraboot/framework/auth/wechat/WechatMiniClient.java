package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * WeChat mini-program client: exchanges a wx.login code for the caller's
 * openid/unionid via jscode2session. The session_key never leaves this class —
 * callers receive only identity fields (and it is not persisted anywhere).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatMiniClient {

    public record WxSession(String openid, String unionid) {}

    private final WechatMiniProperties properties;

    private RestClient restClient() {
        return RestClient.create();
    }

    public WxSession code2Session(String jsCode) {
        if (isBlank(properties.getAppId()) || isBlank(properties.getAppSecret())) {
            throw new RootUnCheckedException(ResponseCode.BadParam,
                    "WeChat mini-program login is not configured (aura.wechat.mini.*)");
        }
        SessionResponse body;
        try {
            body = restClient().get()
                    .uri(uri -> uri.scheme("https").host(hostOf(properties.getApiBase()))
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.getAppId())
                            .queryParam("secret", properties.getAppSecret())
                            .queryParam("js_code", jsCode)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(SessionResponse.class);
        } catch (Exception e) {
            log.warn("WeChat jscode2session call failed: {}", e.getMessage());
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login exchange failed");
        }
        if (body == null || isBlank(body.getOpenid())) {
            int code = body == null ? 0 : body.getErrcode() == null ? -1 : body.getErrcode();
            String msg = body == null || body.getErrmsg() == null ? "unknown" : body.getErrmsg();
            log.warn("WeChat jscode2session rejected: errcode={} errmsg={}", code, msg);
            throw new RootUnCheckedException(ResponseCode.BadParam, "WeChat login code rejected (" + code + ")");
        }
        return new WxSession(body.getOpenid(), body.getUnionid());
    }

    /** apiBase is "https://api.weixin.qq.com" or an https://host[:port] override. */
    private static String hostOf(String apiBase) {
        String s = apiBase.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Data
    static class SessionResponse {
        private String openid;
        private String unionid;
        private String sessionKey;
        private Integer errcode;
        private String errmsg;
    }
}
