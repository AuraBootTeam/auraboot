package com.auraboot.framework.notification.wechat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/** Deployment-owned callback credentials and explicit once-only template policy. Disabled by default. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aura.notification.wechat-subscription.callback")
public class WechatSubscriptionCallbackProperties {
    private boolean enabled;
    private String token;
    private String encodingAesKey;
    private String originalAccountId;
    private WechatSubscriptionCallbackVerifier.Format format;
    private Duration requestWindow = Duration.ofMinutes(10);
    /** Explicit application retention of usable consent, not a statement about WeChat's own expiry policy. */
    private Duration consentValidity;
    private Set<String> onceTemplateIds = Set.of();
    @Override public String toString() { return "WechatSubscriptionCallbackConfiguration[redacted]"; }
}
