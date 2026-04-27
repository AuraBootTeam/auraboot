package com.auraboot.framework.aurabot.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Chat request DTO for AuraBot.
 *
 * @since 1.0.0
 */
@Data
public class ChatRequest {

    /**
     * Session ID for conversation continuity.
     */
    private String sessionId;

    /**
     * User message content.
     */
    private String message;

    /**
     * Conversation history.
     */
    private List<ChatMessage> history;

    /**
     * Current context (model, page, etc.) — legacy, kept for backward compatibility.
     */
    private Map<String, Object> context;

    /**
     * Structured page context from the frontend copilot panel.
     */
    private PageContext pageContext;

    /**
     * Agent code to route the chat to a specific ACP agent.
     * Default "aurabot" routes to the built-in AuraBot assistant.
     */
    private String agentCode;

    /**
     * Phase B.1: target conversation row in {@code ab_im_message}. Required for
     * server-side persistence (TurnSideEffects.Persistence). When null the server
     * falls back to legacy non-persisting behavior so the API stays binary-compat
     * with frontends that have not yet migrated.
     */
    private Long conversationId;

    /**
     * Phase B.1: client-side dedup key for the inbound message. Maps to
     * {@code ab_im_message.client_msg_id}; deduped via
     * {@code idx_ab_im_message_dedup (conversation_id, client_msg_id)}.
     * Resending the same {@code clientMsgId} for the same {@code conversationId}
     * returns the previously persisted row instead of inserting a duplicate.
     */
    private String clientMsgId;

    /**
     * Knowledge base PIDs for RAG context augmentation.
     * If set, the system prompt will include relevant chunks from these KBs.
     */
    private List<String> knowledgeBaseIds;

    /**
     * Chat options.
     */
    private ChatOptions options;

    @Data
    public static class PageContext {
        /** Page kind: list, detail, form, dashboard */
        private String kind;
        /** DSL page slug */
        private String pageKey;
        /** Model code */
        private String modelCode;
        /** Current record PID */
        private String recordPid;
        /** Field:value pairs of the current record */
        private Map<String, Object> recordData;
        /** Navigation breadcrumb */
        private List<String> breadcrumb;
    }

    @Data
    public static class ExecuteRequest {
        private String sessionId;
        private String toolId;
        private boolean confirmed;
    }

    @Data
    public static class ChatOptions {
        private String model;
        /** Explicit provider override (e.g. "anthropic", "openai", "deepseek") */
        private String provider;
        private Double temperature = 0.7;
        private Integer maxTokens = 4096;
        private Boolean stream = true;
    }
}
