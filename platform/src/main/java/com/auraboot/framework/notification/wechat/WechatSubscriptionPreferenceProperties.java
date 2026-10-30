package com.auraboot.framework.notification.wechat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Explicit client-purpose -> once-only template mapping. No product purposes or template IDs are defaulted. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aura.notification.wechat-subscription.preferences")
public class WechatSubscriptionPreferenceProperties {
    private boolean enabled;
    private Map<String, String> templates = Map.of();
}
