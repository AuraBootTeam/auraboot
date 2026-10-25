package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * ConversationTurn replay projection for a single agent run.
 *
 * <p>There is no dedicated {@code ab_conversation_turn} table. This DTO
 * reconstructs the turn from existing audit state:
 * {@code ab_agent_run -> ab_agent_task.input_data -> ab_im_message}.
 */
@Data
@Builder
public class AgentConversationTurnReplay {

    private String runId;
    private String taskPid;
    private String turnId;
    private Long conversationId;
    private Long inboundMessageId;
    private Long outboundMessageId;
    private String triageBucket;
    private String triageConfidence;
    private String triageReasonCodes;
    private String userMessage;
    private String finalResponse;
    private String outcomeStatus;
    private Instant startedAt;
    private Instant completedAt;
    private List<AgentConversationMessageItem> messages;
    private List<String> resultContractIds;
}
