package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Read-only projection of a single {@code ab_im_message} row that belongs to
 * an agent conversation turn.
 */
@Data
@Builder
public class AgentConversationMessageItem {

    private Long messageId;
    private Long conversationId;
    private String senderType;
    private Long senderId;
    private Long seq;
    private String messageType;
    private String content;
    private String cardPayload;
    private String clientMsgId;
    private String triageBucket;
    private String triageConfidence;
    private String triageReasonCodes;
    private String thinkingContent;
    private String thinkingSignature;
    private Instant createdAt;
}
