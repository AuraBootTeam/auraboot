package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Enriched message response returned by REST endpoints.
 * Includes sender display name (joined from ab_user) and a pid alias for the Android client.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImMessageResponse {
    /** Numeric message id */
    private Long id;
    /** String pid — equals id.toString() for REST clients that expect a pid */
    private String pid;
    private Long conversationId;
    /** Numeric sender id (user ID or agent definition ID) */
    private Long senderId;
    /** Sender id as string — alias used by mobile clients */
    private String senderPid;
    /** Sender type: human | agent | system */
    private String senderType;
    /** Sender display name (joined from ab_user or ab_agent_definition) */
    private String senderName;
    /** Sender avatar URL (null if not set) */
    private String senderAvatar;
    /** Agent-specific fields (null for human senders) */
    private String agentCode;
    private String agentName;
    private String employeeTitle;
    /** Message type: text | image | file | system */
    private String type;
    private String content;
    private Long seq;
    private Instant createdAt;
    private Long replyToId;
    private List<String> mentions;
    private Boolean recalled;
    private Long forwardedFromId;
    private Object cardPayload;
}
