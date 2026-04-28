package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.identity.AuraBotAgentResolver;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase B.1 implementation of {@link TurnSideEffects.Persistence}. Replaces
 * {@link TurnSideEffects.Persistence#NOOP} that Phase A injected so the
 * server-side {@code /chat/stream} path now writes both inbound (human) and
 * outbound (agent) rows into {@code ab_im_message} — eliminating the
 * frontend-driven {@code appendUserMessage} / {@code appendAssistantMessage}
 * detour documented in design v3.3 §1.4.
 *
 * <p>Decisions applied:
 * <ul>
 *     <li>Q-B1.1 = A: outbound rows use {@code sender_type='agent'} +
 *         {@code sender_id=agentId} (not the legacy {@code system}/0 shape).
 *         Symmetric with the group-chat path so future cross-channel sync
 *         does not need to fork.</li>
 *     <li>Q-B1.2 = α: agentId comes from {@link AuraBotAgentResolver} which
 *         lazy-seeds a default {@code ab_agent_definition} row for the
 *         tenant on first call.</li>
 *     <li>Q-B1.5 = β: idempotency by {@code (conversation_id, client_msg_id)}
 *         via the existing {@code idx_ab_im_message_dedup} unique index;
 *         {@code ImMessageService.sendMessage} / {@code sendAgentMessage}
 *         look up the dedup row and short-circuit before insert.</li>
 * </ul>
 *
 * <p>Phase A invariants preserved:
 * <ul>
 *     <li>{@code persistInbound} returns the new {@code message.id} for
 *         {@code TurnContext.inboundMessageId}; or null when the request did
 *         not carry a {@code conversationId} (legacy frontend before B.1).
 *         A null return keeps Phase A backward-compatible until the frontend
 *         contract switch (also part of B.1) lands.</li>
 *     <li>{@code persistOutbound} on a {@link TurnOutcome.Failed} writes a
 *         {@code messageType='system'} row with the error message so the UI
 *         can render the failure consistently with the historical assistant
 *         error path.</li>
 * </ul>
 *
 * <p>Out of scope for B.1 (TODO B.2/B.6):
 * <ul>
 *     <li>traceId in card_payload — Phase A.4 left {@code TurnContext.traceId}
 *         null; threading it through requires a small chat-impl change which
 *         B.2 picks up.</li>
 *     <li>{@link TurnOutcome.PendingConfirmation} {@code chatSessionStore.savePending}
 *         migration — that lives with the {@code /execute} pendingTurnId
 *         end-to-end contract in B.6.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuraBotTurnPersistence implements TurnSideEffects.Persistence {

    private final ImMessageService imMessageService;
    private final AuraBotAgentResolver agentResolver;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public Long persistInbound(TurnRequest request,
                                 com.auraboot.framework.agent.triage.TriageVerdict triageVerdict) {
        if (request == null || request.conversationId() == null) {
            log.debug("persistInbound: missing conversationId on TurnRequest, skipping "
                    + "(legacy frontend path that has not migrated to Phase B.1 contract yet)");
            return null;
        }
        if (request.humanMemberId() == null) {
            log.warn("persistInbound: humanMemberId is null for tenantId={} conversationId={}; "
                    + "skipping inbound persistence to avoid invalid sender_id",
                    request.tenantId(), request.conversationId());
            return null;
        }
        try {
            SendMessageRequest req = new SendMessageRequest();
            req.setConversationId(request.conversationId());
            req.setMessageType("text");
            req.setContent(request.userMessage());
            req.setClientMsgId(request.clientMsgId());
            // Phase C.1: forward triage verdict onto the inbound row. Bucket goes
            // in lowercase to match the table CHECK constraint
            // (light_chat / contextual_answer / acp_run).
            if (triageVerdict != null && triageVerdict.bucket() != null) {
                req.setTriageBucket(triageVerdict.bucket().name().toLowerCase());
                req.setTriageConfidence(java.math.BigDecimal.valueOf(triageVerdict.confidence()));
                if (triageVerdict.reasonCodes() != null && !triageVerdict.reasonCodes().isEmpty()) {
                    try {
                        req.setTriageReasonCodes(
                                objectMapper.writeValueAsString(triageVerdict.reasonCodes()));
                    } catch (Exception jsonEx) {
                        log.warn("Failed to serialize triage reasonCodes, dropping: {}",
                                jsonEx.getMessage());
                    }
                }
            }
            ImMessage saved = imMessageService.sendMessage(
                    req, request.humanMemberId(), request.tenantId());
            return saved != null ? saved.getId() : null;
        } catch (Exception e) {
            // Per design §3.4: persistence failure on inbound = whole runTurn fails fast.
            // Re-throw so ConversationTurnServiceImpl.runTurn translates it to Failed.
            log.error("persistInbound failed for tenantId={} conversationId={}: {}",
                    request.tenantId(), request.conversationId(), e.getMessage());
            throw e;
        }
    }

    @Override
    public Long persistOutbound(TurnContext ctx, TurnOutcome outcome) {
        if (ctx == null || ctx.conversationId() == null) {
            log.debug("persistOutbound: missing conversationId, skipping");
            return null;
        }
        try {
            return switch (outcome) {
                case TurnOutcome.Success s ->
                        writeAgentRow(ctx, "ai_response", s.finalResponse(), null);
                case TurnOutcome.Interrupted i ->
                        writeAgentRow(ctx, "ai_response", i.partialResponse(), null);
                case TurnOutcome.PendingConfirmation pc ->
                        writeAgentRow(ctx, "ai_response", pc.partialResponse(), null);
                case TurnOutcome.Failed f ->
                        // Failure outbound is recorded as a system message so the UI
                        // renders the error consistently with the historical
                        // appendAssistantMessage(error=true) path.
                        writeSystemRow(ctx, f.errorMessage());
            };
        } catch (Exception e) {
            // Per design §3.4 endTurn(Success): persistence failure on outbound writes
            // audit but does NOT roll back the SSE response already sent. The orchestrator
            // (ConversationTurnServiceImpl.finalizeTurn) catches and logs at warn so the
            // outcome still propagates to the caller.
            log.warn("persistOutbound failed for tenantId={} conversationId={}: {}",
                    ctx.tenantId(), ctx.conversationId(), e.getMessage());
            throw e;
        }
    }

    private Long writeAgentRow(TurnContext ctx, String messageType, String content, String cardPayload) {
        long agentId = agentResolver.resolve(ctx.tenantId(), AuraBotAgentResolver.DEFAULT_AGENT_CODE);
        ImMessage saved = imMessageService.sendAgentMessage(
                ctx.conversationId(),
                ctx.tenantId(),
                agentId,
                messageType,
                content,
                cardPayload,
                outboundClientMsgId(ctx));
        return saved != null ? saved.getId() : null;
    }

    private Long writeSystemRow(TurnContext ctx, String errorMessage) {
        ImMessage saved = imMessageService.sendSystemMessage(
                ctx.conversationId(),
                ctx.tenantId(),
                "system",
                errorMessage,
                null,
                outboundClientMsgId(ctx));
        return saved != null ? saved.getId() : null;
    }

    /** Outbound rows are deduped per turn — turnId is the natural per-turn key. */
    private static String outboundClientMsgId(TurnContext ctx) {
        return ctx.turnId() != null ? "out-" + ctx.turnId() : null;
    }
}
