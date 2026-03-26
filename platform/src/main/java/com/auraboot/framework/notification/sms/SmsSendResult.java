package com.auraboot.framework.notification.sms;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result of an SMS send operation.
 *
 * @since 7.0.0
 */
@Data
@AllArgsConstructor
public class SmsSendResult {

    private boolean success;
    private String messageId;
    private String errorMessage;

    public static SmsSendResult ok(String messageId) {
        return new SmsSendResult(true, messageId, null);
    }

    public static SmsSendResult fail(String msg) {
        return new SmsSendResult(false, null, msg);
    }
}
