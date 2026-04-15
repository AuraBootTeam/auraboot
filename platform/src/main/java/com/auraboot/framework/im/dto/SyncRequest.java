package com.auraboot.framework.im.dto;

import lombok.Data;

import java.util.List;

@Data
public class SyncRequest {
    private List<ConversationSync> conversations;
    private Integer limit;

    @Data
    public static class ConversationSync {
        private Long conversationId;
        private Long afterSeq;
    }
}
