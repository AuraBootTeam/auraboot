package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Read receipt info for a single member who has read a message.
 * Used in the response of GET /api/im/messages/{messageId}/read-receipts.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadReceiptInfo {
    private Long userId;
    private String displayName;
    private String avatarUrl;
    /** The time the user marked the message as read (approximated by last_read_seq update). */
    private Instant readAt;
}
