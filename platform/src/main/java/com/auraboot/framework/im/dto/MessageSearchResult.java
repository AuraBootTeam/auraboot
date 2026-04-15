package com.auraboot.framework.im.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class MessageSearchResult {
    private Long messageId;
    private Long conversationId;
    private String conversationName;
    private String conversationType;
    private String senderName;
    private String content;
    private Long seq;
    private Instant createdAt;
}
