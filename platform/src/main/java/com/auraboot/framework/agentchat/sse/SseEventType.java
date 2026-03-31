package com.auraboot.framework.agentchat.sse;

public enum SseEventType {
    TYPING("typing"),
    STREAM_CHUNK("stream_chunk"),
    STREAM_END("stream_end"),
    MESSAGE("message");

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
