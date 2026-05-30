package com.auraboot.framework.conversation.dto;

public record ActiveTurnDTO(
        String turnId,
        Long conversationId,
        Long agentId,
        String agentName,
        Long initiatorUserId,
        Long replyToMessageId,
        String status
) {
    public static ActiveTurnDTO from(com.auraboot.framework.conversation.turn.TurnHandle h) {
        return new ActiveTurnDTO(
                h.getTurnId(), h.getConversationId(), h.getAgentId(), h.getAgentName(),
                h.getInitiatorUserId(), h.getReplyToMessageId(),
                h.getStatus() != null ? h.getStatus().name() : null);
    }
}
