package com.auraboot.framework.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Human-member count per conversation, fetched in a single {@code GROUP BY conversation_id} query
 * for the conversation-list endpoint instead of an N+1 per-conversation {@code findHumanMemberIds} loop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemberCountRow {
    private Long conversationId;
    private Long memberCount;
}
