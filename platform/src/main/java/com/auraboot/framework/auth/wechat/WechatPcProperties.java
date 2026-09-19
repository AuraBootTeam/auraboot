package com.auraboot.framework.auth.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** WeChat open-platform website-app credentials for PC QR-code login. */
@Data
@Component
@ConfigurationProperties(prefix = "aura.wechat.pc")
public class WechatPcProperties {
    private String appId;
    private String appSecret;
    private String apiBaseUrl = "https://api.weixin.qq.com";
    private String redirectUri;
    /** Overridable for local stub/integration tests. */
    private String qrConnectBaseUrl = "https://open.weixin.qq.com";
}
