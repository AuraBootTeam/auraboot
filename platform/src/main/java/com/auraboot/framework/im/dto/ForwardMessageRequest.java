package com.auraboot.framework.im.dto;

import lombok.Data;

@Data
public class ForwardMessageRequest {
    private Long messageId;
    private Long targetConversationId;
}
