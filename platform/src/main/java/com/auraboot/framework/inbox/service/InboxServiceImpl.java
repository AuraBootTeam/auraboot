package com.auraboot.framework.inbox.service;

import com.auraboot.framework.inbox.mapper.InboxItemMapper;
import com.auraboot.framework.inbox.model.InboxItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * @since 6.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxServiceImpl implements InboxService {

    private final InboxItemMapper inboxItemMapper;

    /**
     * Optional real-time push adapter — provided by enterprise-comm when present.
     * Injected as optional to avoid hard dependency on IM/WebSocket module.
     */
    @Autowired(required = false)
    private InboxRealtimePushPort realtimePushPort;

    private static final List<String> ITEM_TYPES = List.of(
            "approval", "task", "mention", "ai_suggestion", "alert", "assignment", "system"
    );

    @Override
    @Transactional
    public InboxItem createItem(InboxItem item) {
        // Dedup by clientItemId
        if (item.getClientItemId() != null) {
            Long existingId = inboxItemMapper.findByClientItemId(item.getTenantId(), item.getClientItemId());
            if (existingId != null) {
                log.debug("Inbox item already exists: clientItemId={}, id={}", item.getClientItemId(), existingId);
                return inboxItemMapper.selectById(existingId);
            }
        }

        if (item.getStatus() == null) item.setStatus(StatusConstants.PENDING);
        if (item.getPriority() == null) item.setPriority("normal");
        if (item.getIsRead() == null) item.setIsRead(false);
        if (item.getCreatedAt() == null) item.setCreatedAt(Instant.now());

        inboxItemMapper.insert(item);
        log.debug("Inbox item created: id={}, type={}, user={}", item.getId(), item.getItemType(), item.getUserId());

        // Push real-time notification via WebSocket
        pushToUser(item);

        return item;
    }

    /**
     * Push a new inbox item notification to the user via WebSocket (IM channel).
     * Delegates to {@link InboxRealtimePushPort} when enterprise-comm is present.
     */
    private void pushToUser(InboxItem item) {
        if (realtimePushPort == null) {
            log.debug("No InboxRealtimePushPort available, skipping real-time push for userId={}", item.getUserId());
            return;
        }
        try {
            realtimePushPort.pushNewItem(item);
            log.debug("Pushed INBOX_NEW to userId={}", item.getUserId());
        } catch (Exception e) {
            // Non-critical — don't fail inbox creation if push fails
            log.debug("Failed to push inbox notification to userId={}: {}", item.getUserId(), e.getMessage());
        }
    }

    @Override
    public InboxItem getItem(Long id, Long userId, Long tenantId) {
        InboxItem item = inboxItemMapper.selectById(id);
        if (item == null || !item.getTenantId().equals(tenantId) || !item.getUserId().equals(userId)) {
            return null;
        }
        return item;
    }

    @Override
    public IPage<InboxItem> listByUser(Long userId, Long tenantId, String itemType,
                                        String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<InboxItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InboxItem::getTenantId, tenantId)
               .eq(InboxItem::getUserId, userId);

        if (itemType != null && !itemType.isBlank()) {
            wrapper.eq(InboxItem::getItemType, itemType);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(InboxItem::getStatus, status);
        }

        wrapper.orderByDesc(InboxItem::getCreatedAt);

        return inboxItemMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public IPage<InboxItem> listByUser(Long userId, Long tenantId, List<String> itemTypes,
                                        String status, int pageNum, int pageSize) {
        LambdaQueryWrapper<InboxItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InboxItem::getTenantId, tenantId)
               .eq(InboxItem::getUserId, userId);

        if (itemTypes != null && !itemTypes.isEmpty()) {
            wrapper.in(InboxItem::getItemType, itemTypes);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(InboxItem::getStatus, status);
        }

        wrapper.orderByDesc(InboxItem::getCreatedAt);

        return inboxItemMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public Map<String, Integer> getUnreadSummary(Long userId, Long tenantId) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        int total = 0;
        for (String type : ITEM_TYPES) {
            int count = inboxItemMapper.countUnreadByType(tenantId, userId, type);
            if (count > 0) {
                summary.put(type, count);
                total += count;
            }
        }
        summary.put("total", total);
        return summary;
    }

    @Override
    public int getUnreadCount(Long userId, Long tenantId) {
        return inboxItemMapper.countUnread(tenantId, userId);
    }

    @Override
    @Transactional
    public void markRead(Long id, Long userId, Long tenantId) {
        LambdaUpdateWrapper<InboxItem> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(InboxItem::getId, id)
               .eq(InboxItem::getTenantId, tenantId)
               .eq(InboxItem::getUserId, userId)
               .eq(InboxItem::getIsRead, false)
               .set(InboxItem::getIsRead, true)
               .set(InboxItem::getReadAt, Instant.now());
        inboxItemMapper.update(null, wrapper);
    }

    @Override
    @Transactional
    public int markAllRead(Long userId, Long tenantId) {
        return inboxItemMapper.markAllRead(tenantId, userId);
    }

    @Override
    @Transactional
    public void markActed(Long id, Long userId, Long tenantId, String action) {
        int rows = inboxItemMapper.markActed(id, tenantId, userId, action);
        if (rows == 0) {
            log.warn("Failed to mark inbox item as acted: id={}, userId={} (not found or already acted)", id, userId);
        }
    }

    @Override
    @Transactional
    public void dismiss(Long id, Long userId, Long tenantId) {
        int rows = inboxItemMapper.dismiss(id, tenantId, userId);
        if (rows == 0) {
            log.warn("Failed to dismiss inbox item: id={}, userId={} (not found or already acted)", id, userId);
        }
    }

    @Override
    @Transactional
    public int batchMarkRead(List<Long> ids, Long userId, Long tenantId) {
        if (ids == null || ids.isEmpty()) return 0;
        return inboxItemMapper.batchMarkRead(tenantId, userId, ids);
    }

    @Override
    @Transactional
    public int batchMarkActed(List<Long> ids, Long userId, Long tenantId, String action) {
        if (ids == null || ids.isEmpty()) return 0;
        return inboxItemMapper.batchMarkActed(tenantId, userId, ids, action);
    }

    @Override
    @Transactional
    public int batchDismiss(List<Long> ids, Long userId, Long tenantId) {
        if (ids == null || ids.isEmpty()) return 0;
        return inboxItemMapper.batchDismiss(tenantId, userId, ids);
    }

    @Override
    @Transactional
    public void closeByClientItemIdPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return;
        inboxItemMapper.closeByClientItemIdPrefix(prefix + "%");
    }

    @Override
    @Transactional
    public void closeByClientItemId(String clientItemId) {
        if (clientItemId == null || clientItemId.isBlank()) return;
        inboxItemMapper.closeByClientItemId(clientItemId);
    }

    @Override
    @Transactional
    public void closeByClientItemIdPrefixExcluding(String prefix, String excludeClientItemId, String reason) {
        if (prefix == null || prefix.isBlank() || excludeClientItemId == null) return;
        inboxItemMapper.closeByClientItemIdPrefixExcluding(prefix + "%", excludeClientItemId, reason);
    }

    @Override
    @Transactional
    public void closeByClientItemIdWithReason(String clientItemId, String reason) {
        if (clientItemId == null || clientItemId.isBlank()) return;
        inboxItemMapper.closeByClientItemIdWithReason(clientItemId, reason);
    }
}
