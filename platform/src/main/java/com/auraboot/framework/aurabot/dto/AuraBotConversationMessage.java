package com.auraboot.framework.aurabot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuraBotConversationMessage {
    private Long id;
    private Long conversationId;
    private Long seq;
    private String sender;
    private String type;
    private String content;
    private String traceId;
    private Instant createdAt;
}
