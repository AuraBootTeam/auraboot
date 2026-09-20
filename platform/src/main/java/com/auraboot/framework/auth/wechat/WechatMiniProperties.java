package com.auraboot.framework.auth.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WeChat mini-program credentials for code2Session. Values come from
 * environment (AURA_WECHAT_MINI_APP_ID / AURA_WECHAT_MINI_APP_SECRET) or
 * Spring config; absent values disable the channel with a clear error.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aura.wechat.mini")
// PC website-app credentials live in aura.wechat.pc (open-platform website app).
public class WechatMiniProperties {
    private String appId;
    private String appSecret;
    /** Overridable for integration tests; production default is the real endpoint. */
    private String apiBaseUrl = "https://api.weixin.qq.com";
}
