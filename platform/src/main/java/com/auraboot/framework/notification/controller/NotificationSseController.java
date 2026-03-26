package com.auraboot.framework.notification.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.datasync.DataSyncSseRegistry;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * REST controller for Server-Sent Events (SSE) notification streaming.
 * Provides real-time notification updates to connected clients.
 *
 * @since 5.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationSseController {

    private final NotificationSseService notificationSseService;
    private final NotificationQueryService notificationQueryService;
    private final DataSyncSseRegistry dataSyncSseRegistry;

    /**
     * Establish SSE connection for notification updates.
     * GET /api/notifications/stream
     *
     * Events sent:
     * - "connected": Connection established confirmation
     * - "unread-count": Updated unread notification count
     * - "heartbeat": Keep-alive ping (every 30 seconds)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        log.debug("SSE stream requested by user {}", userId);

        SseEmitter emitter = notificationSseService.subscribe(userId);

        // Register for data sync and get connectionId
        Long connectionId = dataSyncSseRegistry.registerEmitter(userId, tenantId, emitter);

        // Send initial unread count immediately after connection
        try {
            int unreadCount = notificationQueryService.getUnreadCount(userId);
            emitter.send(SseEmitter.event()
                    .name("unread-count")
                    .data(Map.of("count", unreadCount)));
            // Send connectionId for data sync subscription binding
            emitter.send(SseEmitter.event()
                    .name("data-sync-connected")
                    .data(Map.of("connectionId", connectionId)));
        } catch (IOException e) {
            log.warn("Failed to send initial events to user {}: {}", userId, e.getMessage());
        }

        return emitter;
    }
}
