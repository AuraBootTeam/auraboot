package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of NotificationQueryService.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationQueryServiceImpl implements NotificationQueryService {

    private final NotificationMapper notificationMapper;
    private final NotificationSseService notificationSseService;

    @Override
    public PaginationResult<NotificationDTO> listByUser(Long userId, NotificationQueryRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int pageNum = Math.max(1, request.getPageNum());
        int pageSize = Math.min(100, Math.max(1, request.getPageSize()));
        int offset = (pageNum - 1) * pageSize;

        List<Notification> notifications;
        long total;

        if (Boolean.FALSE.equals(request.getIsRead())) {
            notifications = notificationMapper.findUnreadByUser(tenantId, userId, pageSize, offset);
            total = notificationMapper.countUnread(tenantId, userId);
        } else {
            notifications = notificationMapper.findByUser(tenantId, userId, pageSize, offset);
            total = notificationMapper.countByUser(tenantId, userId);
        }

        List<NotificationDTO> dtos = notifications.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return PaginationResult.of(dtos, total, pageNum, pageSize);
    }

    @Override
    public int getUnreadCount(Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return notificationMapper.countUnread(tenantId, userId);
    }

    @Override
    @Transactional
    public void markAsRead(Long notificationId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        // Get the userId before marking as read
        Long userId = notificationMapper.findUserIdById(tenantId, notificationId);
        notificationMapper.markAsRead(tenantId, notificationId);

        // Push updated unread count via SSE
        if (userId != null) {
            pushUnreadCountUpdate(userId);
        }
    }

    @Override
    @Transactional
    public void markAllAsRead(Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        notificationMapper.markAllAsRead(tenantId, userId);

        // Push updated unread count via SSE (will be 0 after marking all as read)
        pushUnreadCountUpdate(userId);
    }

    /**
     * Push updated unread count to user via SSE.
     */
    private void pushUnreadCountUpdate(Long userId) {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            int unreadCount = notificationMapper.countUnread(tenantId, userId);
            notificationSseService.pushUnreadCount(userId, unreadCount);
        } catch (Exception e) {
            log.warn("Failed to push unread count update to user {}: {}", userId, e.getMessage());
        }
    }

    private NotificationDTO toDTO(Notification entity) {
        return NotificationDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .content(entity.getContent())
                .category(entity.getCategory())
                .priority(entity.getPriority())
                .sourceType(entity.getSourceType())
                .sourceId(entity.getSourceId())
                .isRead(entity.getIsRead())
                .readAt(entity.getReadAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
