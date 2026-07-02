package com.auraboot.framework.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Last message per conversation, fetched in a single {@code DISTINCT ON (conversation_id)} query
 * for the conversation-list endpoint instead of an N+1 per-conversation {@code findBeforeSeq} loop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationLastMessageRow {
    private Long conversationId;
    private String content;
    private String messageType;
    private Instant createdAt;
}
