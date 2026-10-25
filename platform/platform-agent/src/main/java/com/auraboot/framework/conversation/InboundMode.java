package com.auraboot.framework.conversation;

/**
 * How the inbound side of a turn is materialized.
 * See conversation-turn-service-design v3.3 §3.4.
 */
public enum InboundMode {
    /** Default: the user message is created and persisted from the request. SSE direct entry. */
    NEW_FROM_REQUEST,

    /**
     * Group-chat / IM-event-driven: the inbound message has already been persisted by
     * {@code ImMessageService}; the request carries the existing
     * {@code ab_im_message.id} via {@link TurnRequest#inboundMessageId()}. The
     * chokepoint's {@code Persistence.persistInbound} skips the INSERT and instead
     * updates only the triage metadata columns ({@code triage_bucket /
     * triage_confidence / triage_reason_codes}) on the existing row.
     *
     * <p>Phase D.1 (2026-04-30): activated for entry points #7 (group-chat
     * @mention) and #8 (WebSocket @AI / IM panel) per design v3.3 §3.5 +
     * Phase D §2 v2 lock. Q-D.5 field mapping.
     */
    EXISTING_MESSAGE_ID
}
