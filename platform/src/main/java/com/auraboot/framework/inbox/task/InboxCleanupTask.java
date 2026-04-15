package com.auraboot.framework.inbox.task;

import com.auraboot.framework.inbox.mapper.InboxItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Scheduled task that cleans up expired inbox items.
 *
 * <p>Registered in {@code SystemTaskInitializer} under key {@code sys-inbox-cleanup}.
 * Invoked by DatabaseSchedulerEngine via reflection.
 *
 * @since 6.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboxCleanupTask {

    private final InboxItemMapper inboxItemMapper;

    /**
     * Clean up expired inbox items.
     * Items with expires_at in the past and status still PENDING are marked EXPIRED.
     * Items that have been ACTED/DISMISSED for over 90 days are deleted.
     */
    public void cleanupExpired() {
        int expired = inboxItemMapper.markExpiredItems();
        int deleted = inboxItemMapper.deleteOldItems(90);

        if (expired > 0 || deleted > 0) {
            log.info("Inbox cleanup: {} items expired, {} old items deleted", expired, deleted);
        }
    }
}
