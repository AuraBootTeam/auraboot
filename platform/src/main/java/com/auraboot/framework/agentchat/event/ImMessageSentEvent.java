package com.auraboot.framework.agentchat.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.util.List;

@Getter
public class ImMessageSentEvent extends ApplicationEvent {
    private final Long conversationId;
    private final Long tenantId;
    private final Long senderId;
    private final String senderType;
    private final String content;
    private final List<String> mentions;
    private final Long messageId;
    private final String conversationType;

    public ImMessageSentEvent(Object source, Long conversationId, Long tenantId,
                               Long senderId, String senderType, String content,
                               List<String> mentions, Long messageId,
                               String conversationType) {
        super(source);
        this.conversationId = conversationId;
        this.tenantId = tenantId;
        this.senderId = senderId;
        this.senderType = senderType;
        this.content = content;
        this.mentions = mentions;
        this.messageId = messageId;
        this.conversationType = conversationType;
    }
}
