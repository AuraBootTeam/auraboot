package com.auraboot.framework.im.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UnreadSummary {
    private Long totalUnread;
    private List<ConversationUnread> conversations;

    @Data
    @Builder
    public static class ConversationUnread {
        private Long conversationId;
        private Long unread;
    }
}
