package com.auraboot.framework.notification.service;

import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;

/**
 * Service for querying user notifications.
 *
 * @since 5.1.0
 */
public interface NotificationQueryService {

    /**
     * List notifications for a user with pagination and filters.
     */
    PaginationResult<NotificationDTO> listByUser(Long userId, NotificationQueryRequest request);

    /**
     * Get unread notification count.
     */
    int getUnreadCount(Long userId);

    /**
     * Mark a single notification as read.
     */
    void markAsRead(Long notificationId);

    /**
     * Mark all notifications for a user as read.
     */
    void markAllAsRead(Long userId);
}
