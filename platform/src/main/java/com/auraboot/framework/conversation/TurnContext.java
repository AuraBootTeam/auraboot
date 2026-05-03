package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.common.util.UniqueIdGenerator;

import java.time.Instant;

/**
 * Materialized turn context. Built by {@link ConversationTurnService#runTurn} after
 * inbound persistence (or NOOP for Phase A). Passed to chat impl + persisted side effects.
 *
 * <p>DC.3c (design v5 §10.7 Fix 2 + Fix 3) added two fields:
 * <ul>
 *   <li>{@link #agentCode} — non-null when the dispatch path knows which named
 *       agent the turn targets (aurabot main path → "aurabot"; group-chat path →
 *       Alpha/Beta/etc. agentCode). {@code AuraBotTurnPersistence} reads this
 *       to write the correct outbound {@code sender_id} (was hardcoded to the
 *       aurabot agent before, breaking named-agent group-chat outbound rows).</li>
 *   <li>{@link #taskPid} — the {@code ab_agent_task.pid} that the chokepoint
 *       creates for this turn (named-agent + ACP_RUN paths). Surfaces on
 *       {@code TurnOutcome.Success.meta._taskPid} so callers can pass it as
 *       {@code TurnRequest.parentTaskPid} on a handoff hop, threading the
 *       {@code parent_id} chain through {@code ab_agent_task}.</li>
 * </ul>
 */
public record TurnContext(
        String turnId,                       // VARCHAR(26) PID, runId for nested ACP integration
        long tenantId,
        long userId,
        Long humanMemberId,
        Long agentId,                        // resolved by AuraBotAgentResolver during Phase B
        String agentCode,                    // DC.3c Fix 2: drives outbound sender_id resolution
        String channelSessionId,             // resolved by ChannelSessionResolver during Phase B
        Long conversationId,
        Long inboundMessageId,               // null in Phase A (Persistence.NOOP)
        TriageBucket triageBucket,           // null in Phase A unless caller injected via TurnRequest.precomputedBucket
        String traceId,
        String taskPid,                      // DC.3c Fix 3: ab_agent_task.pid for this turn (chokepoint creates)
        Instant beginAt
) {

    /**
     * Phase A factory used by the legacy {@code AuraBotChatService.streamChat} async wrapper
     * to bridge into {@link ConversationTurnService#runTurn}'s sync core when the caller has
     * not yet plumbed a real {@link TurnRequest}. All B-phase fields default to null because
     * Phase A side effects are NOOP except metrics.
     */
    public static TurnContext legacyDefault(long tenantId, long userId, Long humanMemberId) {
        return new TurnContext(
                UniqueIdGenerator.generate(),
                tenantId,
                userId,
                humanMemberId,
                null,                            // agentId
                null,                            // agentCode (DC.3c)
                null,                            // channelSessionId
                null,                            // conversationId
                null,                            // inboundMessageId
                null,                            // triageBucket
                null,                            // traceId
                null,                            // taskPid (DC.3c)
                Instant.now());
    }

    /**
     * DC.3c Fix 3 helper: produce a copy of this TurnContext with {@code taskPid}
     * filled in. Used by {@code ConversationTurnServiceImpl.dispatchToNamedAgent}
     * after it creates the {@code ab_agent_task} row, before passing the ctx into
     * {@code AgentChatPort.runAgentTurn}.
     */
    public TurnContext withTaskPid(String newTaskPid) {
        return new TurnContext(turnId, tenantId, userId, humanMemberId, agentId, agentCode,
                channelSessionId, conversationId, inboundMessageId, triageBucket, traceId,
                newTaskPid, beginAt);
    }
}
