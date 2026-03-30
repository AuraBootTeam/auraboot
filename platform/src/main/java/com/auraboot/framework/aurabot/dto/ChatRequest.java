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
