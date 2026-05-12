package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationListItem {
    private Long conversationId;
    private String type;
    private String name;
    private String avatarUrl;
    private LastMessage lastMessage;
    private Long unreadCount;
    private Boolean pinned;
    private Boolean muted;
    private Integer memberCount;
    private String boundModelCode;
    private Long boundRecordId;
    private Long conductorAgentId;
    private Integer aiContextWindow;
    private Boolean aiEnabled;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LastMessage {
        private String content;
        private String senderName;
        private String messageType;
        private Instant createdAt;
    }
}
