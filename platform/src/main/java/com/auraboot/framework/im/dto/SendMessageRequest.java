package com.auraboot.framework.im.dto;

import lombok.Data;

import java.util.List;

@Data
public class SendMessageRequest {
    private Long conversationId;
    private String messageType; // TEXT | IMAGE | FILE | CARD
    private String content;
    private String clientMsgId;
    private Object cardPayload; // Card Protocol JSON
    private Object attachments; // [{type, url, ...}]
    private Long replyToId;
    private List<String> mentions; // user ids or "ai" (legacy format)

    /**
     * Typed mention targets for polymorphic mentions (agent vs human).
     * If present, takes precedence over flat `mentions` list.
     */
    @Data
    public static class MentionTarget {
        private String type; // human | agent
        private Long id;     // user ID or agent definition ID
    }

    private List<MentionTarget> mentionTargets;
}
