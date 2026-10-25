package com.auraboot.framework.conversation;

/**
 * Thrown from {@code BroadcastResponseSink.onTextChunk} when the AI turn has been cancelled
 * by the originating user. The upstream LLM call stack should unwind cleanly; AgentReplyTask
 * catches this and does NOT mark it as an error.
 */
public class TurnCancelledException extends RuntimeException {
    private final String turnId;

    public TurnCancelledException(String turnId) {
        super("AI turn cancelled: " + turnId);
        this.turnId = turnId;
    }

    public String getTurnId() {
        return turnId;
    }
}
