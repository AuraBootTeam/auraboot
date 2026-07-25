package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.notification.dto.NotificationDTO;
import com.auraboot.framework.notification.dto.NotificationQueryRequest;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationQueryService;
import com.auraboot.framework.notification.service.NotificationSseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

        // Both filters are honoured here, and they compose. Category values are
        // persisted lower-case (InAppChannel defaults to "system", the UI tabs send
        // "system"/"approval"/"business"/"alert"), but older rows may carry upper-case
        // values, so the comparison is case-insensitive on both sides.
        String category = request.getCategory();
        boolean hasCategory = category != null && !category.isBlank();
        String normalizedCategory = hasCategory ? category.trim().toLowerCase() : null;

        LambdaQueryWrapper<Notification> filter = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, userId)
                .eq(request.getIsRead() != null, Notification::getIsRead, request.getIsRead())
                .apply(hasCategory, "LOWER(category) = {0}", normalizedCategory);

        long total = notificationMapper.selectCount(filter);

        LambdaQueryWrapper<Notification> pageQuery = filter.clone()
                .orderByDesc(Notification::getCreatedAt)
                .last("LIMIT " + pageSize + " OFFSET " + offset);
        List<Notification> notifications = notificationMapper.selectList(pageQuery);

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
        // Object-level authorization: only the notification's own recipient may
        // mark it read. Scope the UPDATE by the caller's userId so a user cannot
        // mark another (same-tenant) user's notification read via an enumerable id.
        Long userId = MetaContext.getCurrentUserId();
        int updated = notificationMapper.markAsRead(tenantId, notificationId, userId);

        // Push updated unread count via SSE only when the caller actually owned it.
        if (updated > 0) {
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

    @Override
    @Transactional
    public int deleteByIds(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        // Scope the DELETE by (tenantId, userId) so an enumerable id cannot be used
        // to remove another member's notification — same object-level rule markAsRead uses.
        int deleted = notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTenantId, tenantId)
                .eq(Notification::getUserId, userId)
                .in(Notification::getId, ids));

        if (deleted > 0) {
            pushUnreadCountUpdate(userId);
        }
        return deleted;
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
