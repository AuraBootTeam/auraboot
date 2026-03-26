package com.auraboot.framework.notification.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Routes SMS send requests to the first available {@link SmsSender} implementation.
 * <p>
 * Spring auto-discovers all {@link SmsSender} beans, ordered by {@link org.springframework.core.annotation.Order}.
 * Real providers (e.g. TencentSmsSender) should have a lower order than NoOpSmsSender.
 *
 * @since 7.0.0
 */
@Slf4j
@Service
public class SmsSenderRouter {

    private final List<SmsSender> senders;

    public SmsSenderRouter(List<SmsSender> senders) {
        this.senders = senders;
        log.info("SmsSenderRouter initialized with {} provider(s): {}",
                senders.size(),
                senders.stream().map(SmsSender::getProviderCode).toList());
    }

    /**
     * Send an SMS via the first available provider.
     *
     * @param phone      target phone number
     * @param templateId provider-specific template ID
     * @param params     template parameter map
     * @return send result
     */
    public SmsSendResult send(String phone, String templateId, Map<String, String> params) {
        return senders.stream()
                .filter(SmsSender::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No SMS sender available"))
                .send(phone, templateId, params);
    }
}
