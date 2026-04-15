package com.auraboot.framework.inbox.service;

import com.auraboot.framework.inbox.model.InboxItem;

/**
 * SPI port for pushing real-time inbox notifications to connected users.
 * <p>
 * Implemented by enterprise-comm (via IM WebSocket channel) when that module
 * is on the classpath. Core inbox logic uses this interface so it has no
 * compile-time dependency on IM/WebSocket types.
 *
 * @since 6.4.0
 */
public interface InboxRealtimePushPort {

    /**
     * Push a newly created inbox item to the recipient user in real time.
     *
     * @param item the persisted inbox item to push
     */
    void pushNewItem(InboxItem item);
}
