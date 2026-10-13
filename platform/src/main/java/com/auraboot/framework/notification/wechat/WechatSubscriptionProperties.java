package com.auraboot.framework.notification.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Explicit template contract; no recipient, template or route defaults. */
@Data
@Component
@ConfigurationProperties(prefix = "aura.notification.wechat-subscription")
public class WechatSubscriptionProperties {
    private boolean enabled;
    private boolean allowLoopback;
    private String templateId;
    private String miniprogramState;
    private Map<String,Integer> fieldMaxLengths = Map.of();
    private long connectTimeoutMs = 10_000;
    private long readTimeoutMs = 10_000;
}
