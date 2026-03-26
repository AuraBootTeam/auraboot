package com.auraboot.framework.notification.channel;

/**
 * SPI for notification delivery channels.
 * Each implementation handles a specific channel (IN_APP, EMAIL, WECHAT_WORK, etc.).
 *
 * @since 5.3.0
 */
public interface NotificationChannel {

    /**
     * Unique channel code (e.g. "in_app", "email", "wechat_work").
     */
    String getChannelCode();

    /**
     * Send a notification message through this channel.
     *
     * @param message the notification message to send
     * @return result indicating success or failure
     */
    NotificationResult send(NotificationMessage message);

    /**
     * Whether this channel is currently available (configured and ready).
     * Channels that return false will be skipped during dispatch.
     */
    boolean isAvailable();
}
