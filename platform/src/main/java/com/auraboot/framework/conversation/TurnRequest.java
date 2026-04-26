package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;

import java.util.Map;

/**
 * Input to {@link ConversationTurnService#runTurn}.
 *
 * <p>Phase A allows the persistence-related fields ({@code conversationId},
 * {@code clientMsgId}, {@code humanMemberId}) to be null because Phase A injects
 * {@link TurnSideEffects.Persistence#NOOP}. Phase B promotes them to required.
 *
 * <p>{@code humanMemberId} is the {@code ab_im_conversation_member.member_id}
 * (NOT {@code user_id}); it is server-side injected by the controller via
 * {@code AuraBotController.currentHumanMemberId()}, never sent by the frontend
 * (per Q10/Q11 reflection on identity-spoofing risk).
 */
public record TurnRequest(
        long tenantId,
        long userId,
        Long humanMemberId,                  // ab_im_conversation_member.member_id; nullable in Phase A
        String channel,                      // "web" / "slack" / "mobile" / "webhook" / ...
        String agentCode,                    // default "aurabot"
        Long conversationId,                 // nullable in Phase A; required in Phase B
        String clientMsgId,                  // nullable in Phase A; required in Phase B (for idempotent insert)
        String userMessage,
        Map<String, Object> pageContext,
        Map<String, Object> options,
        InboundMode inboundMode,
        TriageBucket precomputedBucket       // nullable: caller may inject triage verdict; null = Phase A skips triage
) {}
