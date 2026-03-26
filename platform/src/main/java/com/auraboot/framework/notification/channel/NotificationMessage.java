package com.auraboot.framework.notification.channel;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Channel-agnostic notification message.
 * Built by the service layer, consumed by channel implementations.
 *
 * @since 5.3.0
 */
@Getter
@Builder
public class NotificationMessage {

    private final Long tenantId;
    private final List<Long> recipientUserIds;
    private final String templateCode;
    private final String subject;
    private final String body;
    private final String category;
    private final String sourceType;
    private final String sourceId;

    /**
     * Extra channel-specific data (e.g. "email" for EmailChannel).
     */
    private final Map<String, Object> extras;
}
