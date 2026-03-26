package com.auraboot.framework.notification.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationPreferenceDTO;
import com.auraboot.framework.notification.dto.NotificationPreferenceUpdateRequest;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import jakarta.validation.Valid;
import com.auraboot.framework.notification.entity.NotificationPreference;
import com.auraboot.framework.notification.service.NotificationPreferenceService;
import com.auraboot.framework.notification.service.NotificationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for user notifications.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Notification management")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationPreferenceService preferenceService;

    /**
     * List notifications for current user.
     * GET /api/notifications
     */
    @GetMapping
    public ApiResponse<PaginationResult<NotificationDTO>> list(NotificationQueryRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        return ApiResponse.success(notificationQueryService.listByUser(userId, request));
    }

    /**
     * Get unread count.
     * GET /api/notifications/unread-count
     */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> getUnreadCount() {
        Long userId = MetaContext.getCurrentUserId();
        int count = notificationQueryService.getUnreadCount(userId);
        return ApiResponse.success(Map.of("count", count));
    }

    /**
     * Mark a notification as read.
     * PUT /api/notifications/{id}/read
     */
    @PutMapping("/{id}/read")
    public ApiResponse<Void> markAsRead(@PathVariable Long id) {
        notificationQueryService.markAsRead(id);
        return ApiResponse.success();
    }

    /**
     * Mark all notifications as read.
     * PUT /api/notifications/read-all
     */
    @PutMapping("/read-all")
    public ApiResponse<Void> markAllAsRead() {
        Long userId = MetaContext.getCurrentUserId();
        notificationQueryService.markAllAsRead(userId);
        return ApiResponse.success();
    }

    /**
     * Get notification preferences for current user.
     * GET /api/notifications/preferences
     */
    @GetMapping("/preferences")
    public ApiResponse<List<NotificationPreferenceDTO>> getPreferences() {
        Long userId = MetaContext.getCurrentUserId();
        List<NotificationPreference> prefs = preferenceService.getPreferences(userId);
        List<NotificationPreferenceDTO> dtos = prefs.stream()
                .map(this::toPreferenceDTO)
                .collect(Collectors.toList());
        return ApiResponse.success(dtos);
    }

    /**
     * Update a notification preference for current user.
     * PUT /api/notifications/preferences
     */
    @PutMapping("/preferences")
    public ApiResponse<Void> updatePreference(@Valid @RequestBody NotificationPreferenceUpdateRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        preferenceService.updatePreference(userId, request.getChannel(),
                request.getCategory(), request.getEnabled());
        return ApiResponse.success();
    }

    private NotificationPreferenceDTO toPreferenceDTO(NotificationPreference entity) {
        return NotificationPreferenceDTO.builder()
                .id(entity.getId())
                .channel(entity.getChannel())
                .category(entity.getCategory())
                .enabled(entity.getEnabled())
                .build();
    }
}
