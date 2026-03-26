package com.auraboot.framework.aurabot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Store for pending tool confirmations in AuraBot chat sessions.
 * <p>
 * Uses Redis for persistence (survives server restarts). Falls back to
 * in-memory ConcurrentHashMap if Redis is unavailable.
 * <p>
 * Key pattern: {@code aurabot:pending:{sessionId}:{toolId}} with 10-minute TTL.
 */
@Slf4j
@Component
public class ChatSessionStore {

    private static final String PENDING_KEY_PREFIX = "aurabot:pending:";
    private static final long PENDING_TTL_MINUTES = 10;

    // Fallback in-memory store (used only when Redis is unavailable)
    private static final long IN_MEMORY_TTL_MILLIS = PENDING_TTL_MINUTES * 60 * 1000L;
    private final ConcurrentHashMap<String, PendingTool> inMemoryFallback = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisAvailable;

    @Autowired
    public ChatSessionStore(ObjectMapper objectMapper,
                            @Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.redisAvailable = redisTemplate != null;
        if (!redisAvailable) {
            log.warn("Redis not available - ChatSessionStore falling back to in-memory storage");
        }
    }

    /**
     * Store a pending tool confirmation for a session.
     */
    public void storePending(String sessionId, PendingTool pendingTool) {
        if (redisAvailable) {
            try {
                String key = pendingKey(sessionId, pendingTool.getToolId());
                String json = objectMapper.writeValueAsString(pendingTool);
                redisTemplate.opsForValue().set(key, json, PENDING_TTL_MINUTES, TimeUnit.MINUTES);
                log.debug("Stored pending tool [{}] for session [{}] in Redis (TTL={}min)",
                        pendingTool.getToolId(), sessionId, PENDING_TTL_MINUTES);
                return;
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize PendingTool to JSON, falling back to in-memory", e);
            } catch (Exception e) {
                log.error("Redis write failed for pending tool [{}], falling back to in-memory",
                        pendingTool.getToolId(), e);
            }
        }
        // Fallback: in-memory
        String fallbackKey = sessionId + ":" + pendingTool.getToolId();
        inMemoryFallback.put(fallbackKey, pendingTool);
        log.debug("Stored pending tool [{}] for session [{}] in memory", pendingTool.getToolId(), sessionId);
    }

    /**
     * Retrieve AND remove a pending tool (one-time use).
     *
     * @return the PendingTool, or null if not found / expired
     */
    public PendingTool consumePending(String sessionId, String toolId) {
        if (redisAvailable) {
            try {
                String key = pendingKey(sessionId, toolId);
                String json = redisTemplate.opsForValue().getAndDelete(key);
                if (json == null) {
                    log.debug("No pending tool [{}] in session [{}] (not found or expired)", toolId, sessionId);
                    return null;
                }
                PendingTool tool = objectMapper.readValue(json, PendingTool.class);
                log.debug("Consumed pending tool [{}] from session [{}] via Redis", toolId, sessionId);
                return tool;
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize PendingTool from Redis", e);
                return null;
            } catch (Exception e) {
                log.error("Redis read failed for pending tool [{}], trying in-memory fallback", toolId, e);
            }
        }
        // Fallback: in-memory with manual TTL check
        String fallbackKey = sessionId + ":" + toolId;
        PendingTool tool = inMemoryFallback.remove(fallbackKey);
        if (tool == null) {
            return null;
        }
        if (Instant.now().toEpochMilli() - tool.getCreatedAt() > IN_MEMORY_TTL_MILLIS) {
            log.debug("Pending tool [{}] in session [{}] has expired (in-memory)", toolId, sessionId);
            return null;
        }
        log.debug("Consumed pending tool [{}] from session [{}] via in-memory fallback", toolId, sessionId);
        return tool;
    }

    private String pendingKey(String sessionId, String toolId) {
        return PENDING_KEY_PREFIX + sessionId + ":" + toolId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingTool {

        // --- Tool call info ---
        private String toolId;
        private String toolName;
        private Map<String, Object> input;
        private String description;
        private String modelCode;
        private String toolSpanId;  // for trace: span ID of the pending tool

        // --- Conversation context needed to resume LLM after confirmation ---
        private List<Map<String, Object>> messages;
        private String providerCode;
        private String apiKey;
        private String baseUrl;
        private String model;
        private String systemPrompt;
        private Integer maxTokens;
        private int currentLoop;

        // --- Metadata ---
        @Builder.Default
        private long createdAt = Instant.now().toEpochMilli();
    }
}
