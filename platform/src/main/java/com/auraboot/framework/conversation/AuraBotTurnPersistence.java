package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.identity.AuraBotAgentResolver;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.mapper.ImMessageMapper;
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
    private final ImMessageMapper imMessageMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Override
    public Long persistInbound(TurnRequest request,
                                 com.auraboot.framework.agent.triage.TriageVerdict triageVerdict) {
        if (request == null || request.conversationId() == null) {
            log.debug("persistInbound: missing conversationId on TurnRequest, skipping "
                    + "(legacy frontend path that has not migrated to Phase B.1 contract yet)");
            return null;
        }
        // Phase D.1: dispatch by InboundMode. EXISTING_MESSAGE_ID means the IM
        // event handler already persisted the user message before firing — we
        // only need to backfill the triage decision onto the existing row.
        if (request.inboundMode() == InboundMode.EXISTING_MESSAGE_ID) {
            return updateTriageOnExistingRow(request, triageVerdict);
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
        // DC.3c Fix 2: resolve agent identity from ctx.agentCode (chokepoint
        // beginTurn fills it from request.agentCode). Falls back to
        // DEFAULT_AGENT_CODE when null for legacy code paths. Without this
        // fix, named-agent group-chat (Alpha/Beta/...) outbound rows would
        // incorrectly land with sender_id=aurabot_agent_id.
        String resolveCode = (ctx.agentCode() != null && !ctx.agentCode().isBlank())
                ? ctx.agentCode()
                : AuraBotAgentResolver.DEFAULT_AGENT_CODE;
        long agentId = agentResolver.resolve(ctx.tenantId(), resolveCode);
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

    /**
     * Phase D.1 ({@link InboundMode#EXISTING_MESSAGE_ID} branch): the IM /
     * group-chat event handler already wrote the user message via
     * {@code ImMessageService.sendMessage} before firing the Spring event
     * that drives {@code runTurn}. We only need to backfill the triage
     * verdict columns on that pre-existing row, then return its id so
     * {@link TurnContext#inboundMessageId()} carries it through the rest
     * of the lifecycle.
     *
     * <p>Validation:
     * <ul>
     *     <li>{@code inboundMessageId} must be present — without it we cannot
     *         locate the existing row, which is a contract bug at the call
     *         site (the IM event handler has the {@code ImMessage.id}).</li>
     *     <li>The UPDATE is tenant-scoped (mapper's WHERE clause includes
     *         {@code tenant_id}), so a misbehaving caller cannot stamp
     *         triage values cross-tenant.</li>
     *     <li>A null triage verdict means the SPI was absent — we still
     *         return the id (so {@code TurnContext.inboundMessageId} is
     *         populated) but skip the UPDATE; this matches how
     *         {@code Persistence.persistInbound} treats null triage on the
     *         {@code NEW_FROM_REQUEST} branch (no triage column write).</li>
     * </ul>
     */
    private Long updateTriageOnExistingRow(TurnRequest request,
                                            com.auraboot.framework.agent.triage.TriageVerdict triageVerdict) {
        Long inboundMessageId = request.inboundMessageId();
        if (inboundMessageId == null) {
            log.warn("persistInbound (EXISTING_MESSAGE_ID): inboundMessageId is null for "
                            + "tenantId={} conversationId={}; cannot backfill triage on phantom row",
                    request.tenantId(), request.conversationId());
            return null;
        }
        if (triageVerdict == null || triageVerdict.bucket() == null) {
            // Triage SPI absent — preserve TurnContext.inboundMessageId without
            // touching the row.
            log.debug("persistInbound (EXISTING_MESSAGE_ID): no triage verdict for "
                            + "messageId={}; returning id without UPDATE",
                    inboundMessageId);
            return inboundMessageId;
        }
        try {
            String reasonCodesJson = null;
            if (triageVerdict.reasonCodes() != null && !triageVerdict.reasonCodes().isEmpty()) {
                try {
                    reasonCodesJson = objectMapper.writeValueAsString(triageVerdict.reasonCodes());
                } catch (Exception jsonEx) {
                    log.warn("persistInbound (EXISTING_MESSAGE_ID): reasonCodes serialization failed: {}",
                            jsonEx.getMessage());
                }
            }
            int updated = imMessageMapper.updateTriageMetadata(
                    inboundMessageId,
                    request.tenantId(),
                    triageVerdict.bucket().name().toLowerCase(),
                    java.math.BigDecimal.valueOf(triageVerdict.confidence()),
                    reasonCodesJson);
            if (updated == 0) {
                log.warn("persistInbound (EXISTING_MESSAGE_ID): UPDATE matched 0 rows "
                                + "(tenantId={} messageId={}); cross-tenant write attempt or row deleted",
                        request.tenantId(), inboundMessageId);
            }
            return inboundMessageId;
        } catch (Exception e) {
            // Per design §3.4: persistInbound failure = whole runTurn fails fast.
            log.error("persistInbound (EXISTING_MESSAGE_ID) failed for tenantId={} messageId={}: {}",
                    request.tenantId(), inboundMessageId, e.getMessage());
            throw e;
        }
    }
}
