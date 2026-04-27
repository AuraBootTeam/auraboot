package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.common.util.UniqueIdGenerator;

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
) {

    /**
     * Phase A factory used by the legacy {@code AuraBotChatService.streamChat} async wrapper
     * to bridge into {@link ConversationTurnService#runTurn}'s sync core when the caller has
     * not yet plumbed a real {@link TurnRequest}. All B-phase fields ({@code agentId},
     * {@code channelSessionId}, {@code conversationId}, {@code inboundMessageId},
     * {@code triageBucket}, {@code traceId}) default to null because Phase A side effects
     * are NOOP except metrics.
     */
    public static TurnContext legacyDefault(long tenantId, long userId, Long humanMemberId) {
        return new TurnContext(
                UniqueIdGenerator.generate(),
                tenantId,
                userId,
                humanMemberId,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());
    }
}
