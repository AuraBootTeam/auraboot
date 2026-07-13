package com.auraboot.framework.im.model;

public final class ImConstants {
    private ImConstants() {}

    public static final String TYPE_PRIVATE = "private";
    public static final String TYPE_GROUP = "group";
    public static final String TYPE_BOT = "bot";
    public static final String TYPE_OBJECT = "object";
    /** A conversation between an anonymous website visitor and the customer-service AI or a seat. */
    public static final String TYPE_VISITOR = "visitor";

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_ADMIN = "admin";

    // Member types (polymorphic: human user vs AI agent)
    public static final String MEMBER_TYPE_HUMAN = "human";
    public static final String MEMBER_TYPE_AGENT = "agent";
    /**
     * An anonymous website visitor. Note that {@code ImConversationMemberMapper.findHumanMemberIds}
     * deliberately does not return these: a visitor reads its own conversation over SSE, and the
     * broadcast is meant for the internal members (the seats), so filtering to humans is correct.
     */
    public static final String MEMBER_TYPE_VISITOR = "visitor";

    // Sender types (message sender classification)
    public static final String SENDER_TYPE_HUMAN = "human";
    public static final String SENDER_TYPE_AGENT = "agent";
    public static final String SENDER_TYPE_SYSTEM = "system";
    /** Sent by an anonymous website visitor; sender_id is ab_cs_visitor.id, not ab_user.id. */
    public static final String SENDER_TYPE_VISITOR = "visitor";

    // WebSocket event types for group management
    public static final String WS_CONVERSATION_DELETED = "conversation_deleted";
    public static final String WS_CONVERSATION_UPDATED = "conversation_updated";
    public static final String WS_MEMBER_LEFT = "member_left";

    // WebSocket event types for read receipts
    public static final String WS_READ_RECEIPT = "read_receipt";

    // WebSocket event types for group member events
    public static final String WS_MEMBER_ADDED = "member_added";
    public static final String WS_MEMBER_REMOVED = "member_removed";
    public static final String WS_SELF_KICKED = "self_kicked";
    public static final String WS_CONVERSATION_RENAMED = "conversation_renamed";
    public static final String WS_CONVERSATION_DISSOLVED = "conversation_dissolved";

    // WebSocket event types for group announcement
    public static final String WS_ANNOUNCEMENT_UPDATED = "announcement_updated";
    public static final String WS_ANNOUNCEMENT_CLEARED = "announcement_cleared";

    // System message subType identifiers (stored as JSON in ab_im_message.content)
    public static final String SYS_MEMBER_JOINED = "member_joined";
    public static final String SYS_MEMBER_LEFT = "member_left";
    public static final String SYS_MEMBER_REMOVED = "member_removed";
    public static final String SYS_CONVERSATION_CREATED = "conversation_created";
    public static final String SYS_CONVERSATION_RENAMED = "conversation_renamed";
    public static final String SYS_ANNOUNCEMENT_UPDATED = "announcement_updated";
    public static final String SYS_ANNOUNCEMENT_CLEARED = "announcement_cleared";
    public static final String SYS_AGENT_SETTINGS_CHANGED = "agent_settings_changed";
    public static final String SYS_CONVERSATION_DISSOLVED = "conversation_dissolved";
    public static final String SYS_CONVERSATION_ARCHIVED = "conversation_archived";
    public static final String SYS_CONVERSATION_PINNED_MSG = "conversation_pinned_msg";

    // WebSocket event types for AI streaming turns (G1)
    public static final String WS_AI_TURN_STARTED = "ai_turn_started";
    public static final String WS_STREAM_CHUNK = "stream_chunk";
    public static final String WS_STREAM_END = "stream_end";
    public static final String WS_AI_TURN_COMPLETED = "ai_turn_completed";
    public static final String WS_AI_TURN_FAILED = "ai_turn_failed";
    public static final String WS_AI_TURN_CANCELLED = "ai_turn_cancelled";

    // AI turn error codes (G1)
    public static final String AI_ERR_SAFETY_REFUSAL = "SAFETY_REFUSAL";
    public static final String AI_ERR_RATE_LIMITED = "RATE_LIMITED";
    public static final String AI_ERR_UPSTREAM_TIMEOUT = "UPSTREAM_TIMEOUT";
    public static final String AI_ERR_INTERNAL = "INTERNAL_ERROR";
    public static final String AI_ERR_CANCELLED = "CANCELLED";
}
