package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;

import java.time.Instant;

/**
 * Materialized turn context. Built by {@link ConversationTurnService#runTurn} after
 * inbound persistence (or NOOP for Phase A). Passed to chat impl + persisted side effects.
 */
public record TurnContext(
        String turnId,                       // VARCHAR(26) PID, runId for nested ACP integration
        long tenantId,
        long userId,
        Long humanMemberId,
        Long agentId,                        // resolved by AuraBotAgentResolver during Phase B
        String channelSessionId,             // resolved by ChannelSessionResolver during Phase B
        Long conversationId,
        Long inboundMessageId,               // null in Phase A (Persistence.NOOP)
        TriageBucket triageBucket,           // null in Phase A unless caller injected via TurnRequest.precomputedBucket
        String traceId,
        Instant beginAt
) {}
