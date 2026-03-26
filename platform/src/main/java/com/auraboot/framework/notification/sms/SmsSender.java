package com.auraboot.framework.notification.sms;

import java.util.Map;

/**
 * SPI interface for SMS sending providers.
 * <p>
 * Implementations are discovered via Spring component scan and routed
 * through {@link SmsSenderRouter} based on availability.
 *
 * @since 7.0.0
 */
public interface SmsSender {

    /**
     * Provider identifier, e.g. "tencent_sms", "aliyun_sms", "aws_sns".
     */
    String getProviderCode();

    /**
     * Send an SMS using a template.
     *
     * @param phoneNumber target phone number (E.164 or national format)
     * @param templateId  provider-specific template ID
     * @param params      template parameter map
     * @return send result
     */
    SmsSendResult send(String phoneNumber, String templateId, Map<String, String> params);

    /**
     * Check whether this sender is properly configured and available.
     */
    boolean isAvailable();
}
