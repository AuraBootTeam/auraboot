package com.auraboot.framework.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * No-op SMS sender used as fallback when no real SMS provider is configured.
 * Logs the message content instead of sending.
 *
 * @since 7.0.0
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class NoOpSmsSender implements SmsSender {

    @Override
    public String getProviderCode() {
        return "noop";
    }

    @Override
    public SmsSendResult send(String phoneNumber, String templateId, Map<String, String> params) {
        log.info("[NoOp SMS] to={}, templateId={}, params={}", phoneNumber, templateId, params);
        return SmsSendResult.ok("noop-" + System.currentTimeMillis());
    }

    @Override
    public boolean isAvailable() {
        // Always available as the last-resort fallback
        return true;
    }
}
