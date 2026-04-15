package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Summary of read receipts for a message in a group conversation.
 * Contains the total read count and the list of members who have read.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReadReceiptSummary {
    private Long messageId;
    private Long conversationId;
    /** Number of members who have read this message (excluding the sender). */
    private int readCount;
    /** Total number of members in the conversation (excluding the sender). */
    private int totalMembers;
    /** Detailed list of members who have read the message. */
    private List<ReadReceiptInfo> readers;
}
