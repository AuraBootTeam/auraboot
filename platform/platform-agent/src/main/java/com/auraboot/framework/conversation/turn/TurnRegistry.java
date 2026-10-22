package com.auraboot.framework.conversation.turn;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of AI turns. Single-node only — G1 accepts that cancel requests
 * arriving on the wrong node return 200 idempotent no-op (clustered Redis variant deferred).
 */
@Component
public class TurnRegistry {

    private final ConcurrentHashMap<String, TurnHandle> turns = new ConcurrentHashMap<>();

    public TurnHandle register(String turnId, Long conversationId, Long agentId, String agentName,
                                Long initiatorUserId, Long replyToMessageId) {
        TurnHandle h = new TurnHandle(turnId, conversationId, agentId, agentName, initiatorUserId, replyToMessageId);
        turns.put(turnId, h);
        return h;
    }

    public Optional<TurnHandle> get(String turnId) {
        return Optional.ofNullable(turns.get(turnId));
    }

    public void markCompleted(String turnId) {
        TurnHandle h = turns.get(turnId);
        if (h != null) h.setStatus(TurnStatus.COMPLETED);
    }

    public void markFailed(String turnId) {
        TurnHandle h = turns.get(turnId);
        if (h != null) h.setStatus(TurnStatus.FAILED);
    }

    public void markCancelled(String turnId) {
        TurnHandle h = turns.get(turnId);
        if (h != null) {
            h.getCancelled().set(true);
            h.setStatus(TurnStatus.CANCELLED);
        }
    }

    public List<TurnHandle> getActiveByConversation(Long conversationId) {
        return turns.values().stream()
                .filter(h -> h.getConversationId().equals(conversationId))
                .filter(h -> h.getStatus() == TurnStatus.ACTIVE)
                .toList();
    }
}
