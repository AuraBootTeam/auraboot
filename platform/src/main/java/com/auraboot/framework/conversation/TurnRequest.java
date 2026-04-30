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
 *
 * <p>Phase D.1 (2026-04-30): {@code inboundMessageId} carries the existing
 * {@code ab_im_message.id} when {@link InboundMode#EXISTING_MESSAGE_ID} is in
 * effect — group-chat / IM-event paths persist the user message via
 * {@code ImMessageService} BEFORE firing the Spring event that ultimately
 * calls {@code runTurn}. The chokepoint must NOT write a duplicate row; it
 * only updates the triage metadata columns on the existing one. Always
 * {@code null} for {@link InboundMode#NEW_FROM_REQUEST} (web SSE path).
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
        Long inboundMessageId,                    // D.1: existing ab_im_message.id when inboundMode=EXISTING_MESSAGE_ID
        ChatRequest legacyRequest                 // v4: original ChatRequest preserved for Phase A
) {}
