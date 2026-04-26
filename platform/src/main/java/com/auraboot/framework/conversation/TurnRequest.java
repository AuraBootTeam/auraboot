package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.aurabot.dto.ChatRequest;

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
 * (per Q11 reflection on identity-spoofing risk).
 *
 * <p>v4: {@code legacyRequest} carries the original {@link ChatRequest} so the
 * Phase A chat impl can read fields ({@code sessionId / history / pageContext /
 * knowledgeBaseIds}) that are not yet hoisted into the record. Phase B will
 * absorb these fields into native record fields and drop legacyRequest.
 */
public record TurnRequest(
        long tenantId,
        long userId,
        Long humanMemberId,
        String channel,
        String agentCode,
        Long conversationId,
        String clientMsgId,
        String userMessage,
        Map<String, Object> pageContext,
        Map<String, Object> options,
        InboundMode inboundMode,
        TriageBucket precomputedBucket,
        ChatRequest legacyRequest                 // v4: original ChatRequest preserved for Phase A
) {}
