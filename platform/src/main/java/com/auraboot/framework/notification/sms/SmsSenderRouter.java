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

    /**
     * Return true only when a non-noop SMS provider is configured and available.
     * NoOp remains useful for verification-code local development, but product actions must not
     * advertise SMS as executable when delivery would only be logged.
     */
    public boolean hasRealSender() {
        return realSenderAvailability().available();
    }

    public SmsProviderAvailability realSenderAvailability() {
        List<SmsSender> realSenders = senders.stream()
                .filter(this::isRealSender)
                .toList();
        List<String> providerCodes = realSenders.stream()
                .map(SmsSender::getProviderCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        List<String> availableProviderCodes = realSenders.stream()
                .filter(this::senderAvailable)
                .map(SmsSender::getProviderCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        if (!availableProviderCodes.isEmpty()) {
            return new SmsProviderAvailability(true, availableProviderCodes, null);
        }
        if (providerCodes.isEmpty()) {
            return new SmsProviderAvailability(false, List.of(), "当前环境未配置真实短信 provider");
        }
        return new SmsProviderAvailability(false, providerCodes, "真实短信 provider 当前不可用");
    }

    /**
     * Send through a real SMS provider and reject the noop fallback.
     */
    public RoutedSmsResult sendWithRealProvider(String phone, String templateId, Map<String, String> params) {
        SmsSender sender = senders.stream()
                .filter(this::isRealSender)
                .filter(SmsSender::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No real SMS sender available"));
        return new RoutedSmsResult(sender.getProviderCode(), sender.send(phone, templateId, params));
    }

    private boolean isRealSender(SmsSender sender) {
        if (sender instanceof NoOpSmsSender) {
            return false;
        }
        String providerCode = sender.getProviderCode();
        return providerCode != null && !providerCode.isBlank() && !"noop".equalsIgnoreCase(providerCode.trim());
    }

    private boolean senderAvailable(SmsSender sender) {
        try {
            return sender.isAvailable();
        } catch (RuntimeException e) {
            log.warn("SMS sender availability check failed for provider={}: {}",
                    sender.getProviderCode(), e.getMessage());
            return false;
        }
    }

    public record RoutedSmsResult(String providerCode, SmsSendResult sendResult) {
    }

    public record SmsProviderAvailability(boolean available, List<String> providerCodes, String reason) {
    }
}
