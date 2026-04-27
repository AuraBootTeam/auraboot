package com.auraboot.framework.conversation;

/**
 * How the inbound side of a turn is materialized.
 * See conversation-turn-service-design v3.3 §3.4.
 */
public enum InboundMode {
    /** Default: the user message is created and persisted from the request. SSE direct entry. */
    NEW_FROM_REQUEST,

    /**
     * Group-chat / event-driven: the inbound message has already been persisted by
     * ImMessageService; the request must carry the existing inbound messageId.
     * (Per Q13=α this mode is reserved for Phase B+ group-chat-adapter; not used in Phase A/B.)
     */
    EXISTING_MESSAGE_ID
}
