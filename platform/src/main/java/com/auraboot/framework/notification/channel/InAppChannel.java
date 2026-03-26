package com.auraboot.framework.notification.channel;

import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * In-app notification channel.
 * Persists notifications to DB and pushes unread count via SSE.
 *
 * @since 5.3.0
 */
@Slf4j
@Component
public class InAppChannel implements NotificationChannel {

    private final NotificationMapper notificationMapper;
    private final NotificationSseService sseService;
    private final NotificationQueryService queryService;

    public InAppChannel(NotificationMapper notificationMapper,
                        NotificationSseService sseService,
                        @Lazy NotificationQueryService queryService) {
        this.notificationMapper = notificationMapper;
        this.sseService = sseService;
        this.queryService = queryService;
    }

    @Override
    public String getChannelCode() {
        return "in_app";
    }

    @Override
    public NotificationResult send(NotificationMessage message) {
        try {
            for (Long userId : message.getRecipientUserIds()) {
                Notification notification = new Notification();
                notification.setTenantId(message.getTenantId());
                notification.setUserId(userId);
                notification.setTitle(message.getSubject() != null ? message.getSubject() : "");
                notification.setContent(message.getBody());
                notification.setCategory(message.getCategory() != null ? message.getCategory() : "system");
                notification.setPriority("normal");
                notification.setSourceType(message.getSourceType());
                notification.setSourceId(message.getSourceId());
                notification.setIsRead(false);
                notificationMapper.insert(notification);

                pushUnreadCount(userId);
            }
            return NotificationResult.ok();
        } catch (Exception e) {
            log.error("InAppChannel send failed: {}", e.getMessage(), e);
            return NotificationResult.fail(e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private void pushUnreadCount(Long userId) {
        try {
            int count = queryService.getUnreadCount(userId);
            sseService.pushUnreadCount(userId, count);
        } catch (Exception e) {
            log.warn("Failed to push SSE unread count for user {}: {}", userId, e.getMessage());
        }
    }
}
