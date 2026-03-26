package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.dto.NotificationRecipient;
import com.auraboot.framework.notification.dto.NotificationSendRequest;

import java.util.List;
import java.util.Map;

/**
 * Unified notification sending service.
 *
 * @since 5.1.0
 */
public interface NotificationService {

    /**
     * Send a notification using a template.
     */
    void send(NotificationSendRequest request);

    /**
     * Batch send using the same template with different recipients.
     */
    void sendBatch(String templateCode, List<NotificationRecipient> recipients,
                   Map<String, Object> variables);

    /**
     * Send an in-app notification directly (no template).
     */
    void sendInApp(Long userId, String title, String content,
                   String category, String sourceType, String sourceId);
}
