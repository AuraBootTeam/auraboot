package com.auraboot.framework.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the unread-summary join ({@code ab_im_conversation_member} ⋈ {@code ab_im_conversation}).
 *
 * <p>Used by {@code getUnreadSummary} to compute a member's unread counts in a single query
 * instead of an N+1 loop (per-conversation {@code selectById} + {@code findMember}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationUnreadRow {
    private Long conversationId;
    private Long maxSeq;
    private Long lastReadSeq;
}
