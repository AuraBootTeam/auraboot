package com.auraboot.framework.agentchat.spi;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ChatMessageDto {
    private Long id;
    private Long conversationId;
    private String senderType;      // human | agent
    private Long senderId;          // userId or agentDefinitionId
    private String senderName;
    private String senderAvatar;
    private Long seq;
    private String messageType;
    private String content;
    private String cardPayload;
    private List<String> mentions;
    private Instant createdAt;
}
