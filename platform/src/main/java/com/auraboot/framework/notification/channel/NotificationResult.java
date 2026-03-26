package com.auraboot.framework.notification.channel;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of a channel send operation.
 *
 * @since 5.3.0
 */
@Getter
@AllArgsConstructor
public class NotificationResult {

    private final boolean success;
    private final String errorMessage;

    public static NotificationResult ok() {
        return new NotificationResult(true, null);
    }

    public static NotificationResult fail(String msg) {
        return new NotificationResult(false, msg);
    }
}
