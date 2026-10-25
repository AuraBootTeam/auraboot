package com.auraboot.framework.aurabot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuraBotConversationItem {
    private Long conversationId;
    private String title;
    private String agentCode;
    private String agentName;
    private String lastMessagePreview;
    private String lastMessageType;
    private Integer messageCount;
    private Instant updatedAt;
}
