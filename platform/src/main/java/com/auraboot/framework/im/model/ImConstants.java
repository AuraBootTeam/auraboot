package com.auraboot.framework.im.model;

public final class ImConstants {
    private ImConstants() {}

    public static final String TYPE_PRIVATE = "private";
    public static final String TYPE_GROUP = "group";
    public static final String TYPE_BOT = "bot";
    public static final String TYPE_OBJECT = "object";

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_ADMIN = "admin";

    // Member types (polymorphic: human user vs AI agent)
    public static final String MEMBER_TYPE_HUMAN = "human";
    public static final String MEMBER_TYPE_AGENT = "agent";

    // Sender types (message sender classification)
    public static final String SENDER_TYPE_HUMAN = "human";
    public static final String SENDER_TYPE_AGENT = "agent";
    public static final String SENDER_TYPE_SYSTEM = "system";

    // WebSocket event types for group management
    public static final String WS_CONVERSATION_DELETED = "conversation_deleted";
    public static final String WS_CONVERSATION_UPDATED = "conversation_updated";
    public static final String WS_MEMBER_LEFT = "member_left";

    // WebSocket event types for read receipts
    public static final String WS_READ_RECEIPT = "read_receipt";
}
