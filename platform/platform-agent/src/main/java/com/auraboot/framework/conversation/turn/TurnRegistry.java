package com.auraboot.framework.conversation.turn;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Single-node active turns and bounded terminal metadata for idempotent cancellation. */
@Component
public class TurnRegistry {
    private final ConcurrentHashMap<String, TurnHandle> active = new ConcurrentHashMap<>();
    private final Cache<String, TurnHandle> terminal;

    public TurnRegistry() { this(600000L, 1000L); }

    @Autowired
    public TurnRegistry(@Value("${agent.turn.terminal-retention-ms:600000}") long retentionMillis,
                        @Value("${agent.turn.terminal-max-size:1000}") long maxSize) {
        this(retentionMillis, maxSize, Ticker.systemTicker());
    }

    TurnRegistry(long retentionMillis, long maxSize, Ticker ticker) {
        if (retentionMillis <= 0 || maxSize <= 0) throw new IllegalArgumentException("Positive retention bounds required");
        terminal = Caffeine.newBuilder().expireAfterWrite(Duration.ofMillis(retentionMillis))
                .maximumSize(maxSize).ticker(ticker).build();
    }

    public synchronized TurnHandle register(String turnId, Long conversationId, Long agentId, String agentName,
                                Long initiatorUserId, Long replyToMessageId) {
        TurnHandle h = new TurnHandle(turnId, conversationId, agentId, agentName, initiatorUserId, replyToMessageId);
        terminal.invalidate(turnId);
        active.put(turnId, h);
        return h;
    }

    public synchronized Optional<TurnHandle> get(String turnId) {
        TurnHandle h = active.get(turnId);
        return Optional.ofNullable(h == null ? terminal.getIfPresent(turnId) : h);
    }

    public void markCompleted(String turnId) { finish(turnId, TurnStatus.COMPLETED); }
    public void markFailed(String turnId) { finish(turnId, TurnStatus.FAILED); }
    public void markCancelled(String turnId) { finish(turnId, TurnStatus.CANCELLED); }

    private synchronized void finish(String turnId, TurnStatus status) {
        TurnHandle h = active.remove(turnId);
        if (h != null) {
            h.finish(status);
            terminal.put(turnId, h);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() { terminal.cleanUp(); }

    public List<TurnHandle> getActiveByConversation(Long conversationId) {
        return active.values().stream()
                .filter(h -> h.getConversationId().equals(conversationId))
                .filter(h -> h.getStatus() == TurnStatus.ACTIVE).toList();
    }
}
