package com.auraboot.framework.conversation;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Single-node admission for direct Web conversations. IM multi-agent turns remain concurrent. */
@Component
public class WebConversationTurnCoordinator {
    private record Key(Long tenantId, Long conversationId) {}
    private final ConcurrentHashMap<Key, Lease> active = new ConcurrentHashMap<>();

    public final class Lease implements AutoCloseable {
        private final Key key;
        private final Instant startedAt = Instant.now();
        private final String phase;
        private Lease(Key key, String phase) { this.key = key; this.phase = phase; }
        @Override public void close() { active.remove(key, this); }
    }
    public static final class ConversationBusyException extends RuntimeException {
        public ConversationBusyException() { super("$i18n:aurabot.execution.conversation_busy"); }
    }
    public Lease enter(Long tenantId, Long conversationId, String phase) {
        if (tenantId == null || conversationId == null) throw new IllegalArgumentException("Conversation identity required");
        Key key = new Key(tenantId, conversationId);
        Lease lease = new Lease(key, phase);
        if (active.putIfAbsent(key, lease) != null) throw new ConversationBusyException();
        return lease;
    }
    public Map<String, Object> status(Long tenantId, Long conversationId) {
        Lease lease = active.get(new Key(tenantId, conversationId));
        return lease == null ? Map.of("state", "idle") : Map.of("state", "running", "phase", lease.phase, "startedAt", lease.startedAt);
    }
}
