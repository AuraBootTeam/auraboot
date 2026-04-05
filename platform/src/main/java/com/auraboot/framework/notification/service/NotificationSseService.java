package com.auraboot.framework.notification.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Service for managing Server-Sent Events (SSE) connections for notifications.
 * Provides real-time push capabilities for notification updates.
 *
 * @since 5.2.0
 */
public interface NotificationSseService {

    /**
     * Subscribe a user to receive SSE notification updates.
     *
     * @param userId the user ID
     * @return SseEmitter for the connection
     */
    SseEmitter subscribe(Long userId);

    /**
     * Push the current unread count to all active connections for a user.
     *
     * @param userId the user ID
     * @param count the unread notification count
     */
    void pushUnreadCount(Long userId, int count);

    /**
     * Remove an emitter from the user's connection list.
     * Called by the controller's unified lifecycle callbacks.
     *
     * @param userId the user ID
     * @param emitter the emitter to remove
     */
    void removeEmitter(Long userId, SseEmitter emitter);

    /**
     * Get the number of active connections for a user.
     *
     * @param userId the user ID
     * @return number of active SSE connections
     */
    int getActiveConnectionCount(Long userId);

    /**
     * Get the total number of active connections across all users.
     *
     * @return total number of active SSE connections
     */
    int getTotalActiveConnections();
}
