package com.auraboot.framework.conversation.turn;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TurnHandle {
    private final String turnId;
    private final Long conversationId;
    private final Long agentId;
    private final String agentName;
    private final Long initiatorUserId;
    private final Long replyToMessageId;
    private final Instant startedAt;
    private final AtomicReference<TurnStatus> status = new AtomicReference<>(TurnStatus.ACTIVE);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final StringBuilder cumulative = new StringBuilder();

    public TurnHandle(String turnId, Long conversationId, Long agentId, String agentName,
                      Long initiatorUserId, Long replyToMessageId) {
        this.turnId = turnId;
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.initiatorUserId = initiatorUserId;
        this.replyToMessageId = replyToMessageId;
        this.startedAt = Instant.now();
    }

    public String getTurnId() { return turnId; }
    public Long getConversationId() { return conversationId; }
    public Long getAgentId() { return agentId; }
    public String getAgentName() { return agentName; }
    public Long getInitiatorUserId() { return initiatorUserId; }
    public Long getReplyToMessageId() { return replyToMessageId; }
    public Instant getStartedAt() { return startedAt; }
    public TurnStatus getStatus() { return status.get(); }
    synchronized void finish(TurnStatus terminalStatus) {
        if (terminalStatus == TurnStatus.ACTIVE) {
            throw new IllegalArgumentException("A terminal turn status is required");
        }
        if (status.compareAndSet(TurnStatus.ACTIVE, terminalStatus)) {
            if (terminalStatus == TurnStatus.CANCELLED) cancelled.set(true);
            cumulative.setLength(0);
            cumulative.trimToSize();
        }
    }
    public AtomicBoolean getCancelled() { return cancelled; }

    public synchronized void appendCumulative(String chunk) {
        if (status.get() == TurnStatus.ACTIVE) cumulative.append(chunk);
    }

    public synchronized String getCumulative() {
        return cumulative.toString();
    }
}
