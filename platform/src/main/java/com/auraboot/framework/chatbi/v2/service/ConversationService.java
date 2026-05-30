package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiConversation;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiConversationMapper;
import com.auraboot.framework.chatbi.v2.provider.ConversationContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Multi-turn conversation state for ChatBI v2. Implements the 5-round
 * sliding window from PRD 17 §15 + the context shape from §7.4.
 *
 * <p>Lifecycle:
 *
 * <pre>
 *   create()      → ACTIVE conversation, empty messages_json
 *   append(role, content) — appends one turn; sliding window auto-trims
 *                          to {@link #SLIDING_WINDOW} latest pairs
 *   loadContext() → ConversationContext with last N messages, ready to be
 *                   serialised into the LLM prompt
 *   resetContext() — clears messages_json + stamps context_reset_at,
 *                   keeps conversation pid stable (UI doesn't reload)
 *   close()        — marks CLOSED, refuses further appends, idempotent
 * </pre>
 *
 * <p>Sliding window rationale (PRD §15 decision 3): beyond 5 turns the LLM
 * starts to drift on what the active filter actually is — auto-trim instead
 * of asking the user. Edge case 8.11 names "context 漂移" as the failure
 * mode this guards against.
 *
 * <p>Storage policy: {@code messages_json} carries the full trimmed history
 * (mirrors what we'd hand the LLM), so even a process restart can rehydrate.
 * Per PRD §7.4, the context that the prompt actually sees is a strict
 * subset (last N {@code (user, assistant)} pairs); we never persist more
 * than {@link #SLIDING_WINDOW} pairs in the row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    /** PRD 17 §15 decision 3 — multi-turn = 5. */
    public static final int SLIDING_WINDOW = 5;

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final TypeReference<List<ConversationContext.Message>> MESSAGES_TYPE =
            new TypeReference<>() {};

    private final ChatBiConversationMapper mapper;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    // ---------------------------------------------------------------------
    // lifecycle
    // ---------------------------------------------------------------------

    /**
     * Create a new ACTIVE conversation.
     *
     * @param semanticModelPid optional — null means cross-model conversation
     * @return the new conversation pid (ULID, 26 chars)
     */
    @Transactional
    public String create(Long tenantId, Long userId, String semanticModelPid) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");

        ChatBiConversation row = new ChatBiConversation();
        row.setPid(UlidGenerator.generate());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setSemanticModelPid(semanticModelPid);
        row.setMessagesJson("[]");
        row.setTokenBudgetUsed(0);
        row.setStatus(STATUS_ACTIVE);
        mapper.insert(row);
        return row.getPid();
    }

    /**
     * Append one turn to an ACTIVE conversation. Sliding-window trim ensures
     * the persisted history never exceeds {@link #SLIDING_WINDOW} pairs.
     *
     * @throws IllegalStateException if the conversation is missing or CLOSED
     */
    @Transactional
    public void append(Long tenantId, String pid, String role, String content) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("role must be 'user' or 'assistant', got: " + role);
        }
        ChatBiConversation row = requireActive(tenantId, pid);
        List<ConversationContext.Message> msgs = deserialise(row.getMessagesJson());
        msgs.add(new ConversationContext.Message(role.toLowerCase(Locale.ROOT), content));

        // Sliding window: keep only the last SLIDING_WINDOW pairs.
        // A pair is (user, assistant). 2 * SLIDING_WINDOW messages cap.
        int cap = SLIDING_WINDOW * 2;
        if (msgs.size() > cap) {
            msgs = new ArrayList<>(msgs.subList(msgs.size() - cap, msgs.size()));
        }

        row.setMessagesJson(serialise(msgs));
        mapper.updateById(row);
    }

    /**
     * Mark CLOSED. Idempotent — second call returns false but does not throw,
     * so frontend retries are safe.
     */
    @Transactional
    public boolean close(Long tenantId, String pid) {
        int rows = mapper.close(tenantId, pid);
        return rows > 0;
    }

    /**
     * Wipe messages_json and stamp context_reset_at. Conversation pid stays
     * stable so the UI keeps the same window open; the next user message
     * starts a fresh turn.
     */
    @Transactional
    public boolean resetContext(Long tenantId, String pid) {
        // refuse to reset a CLOSED conversation — caller must create a new one
        ChatBiConversation row = mapper.findByPid(tenantId, pid);
        if (row == null || STATUS_CLOSED.equals(row.getStatus())) {
            return false;
        }
        int rows = mapper.clearContext(tenantId, pid);
        return rows > 0;
    }

    // ---------------------------------------------------------------------
    // read
    // ---------------------------------------------------------------------

    public Optional<ChatBiConversation> findByPid(Long tenantId, String pid) {
        return Optional.ofNullable(mapper.findByPid(tenantId, pid));
    }

    /**
     * Build the {@link ConversationContext} the LLM should see for the next
     * turn. Returns an empty context for CLOSED conversations or when the
     * row is missing — callers can treat both as "start fresh".
     *
     * <p>The returned context only carries {@code messageHistory}; the
     * {@code lastMetrics / lastDimensions / lastFilters / lastTimeRange}
     * fields are populated by W4 once an answer is finalised (those derive
     * from the {@link com.auraboot.framework.chatbi.v2.compiler.TokenCompiler}
     * output, not from raw chat text).
     */
    public ConversationContext loadContext(Long tenantId, String pid) {
        ChatBiConversation row = mapper.findByPid(tenantId, pid);
        if (row == null || STATUS_CLOSED.equals(row.getStatus())) {
            return ConversationContext.empty();
        }
        ConversationContext ctx = new ConversationContext();
        ctx.setMessageHistory(deserialise(row.getMessagesJson()));
        return ctx;
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    private ChatBiConversation requireActive(Long tenantId, String pid) {
        ChatBiConversation row = mapper.findByPid(tenantId, pid);
        if (row == null) {
            throw new IllegalStateException("Conversation not found: " + pid);
        }
        if (STATUS_CLOSED.equals(row.getStatus())) {
            throw new IllegalStateException("Conversation is CLOSED: " + pid);
        }
        return row;
    }

    private List<ConversationContext.Message> deserialise(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(jsonMapper.readValue(json, MESSAGES_TYPE));
        } catch (Exception e) {
            log.warn("Failed to deserialise conversation messages: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String serialise(List<ConversationContext.Message> msgs) {
        try {
            return jsonMapper.writeValueAsString(msgs);
        } catch (Exception e) {
            log.warn("Failed to serialise conversation messages: {}", e.getMessage());
            return "[]";
        }
    }
}
