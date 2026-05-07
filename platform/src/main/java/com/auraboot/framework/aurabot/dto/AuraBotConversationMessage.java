package com.auraboot.framework.aurabot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuraBotConversationMessage {
    private Long id;
    private Long conversationId;
    private Long seq;
    private String sender;
    private String type;
    private String content;
    private String traceId;
    /**
     * D.1 (2026-05-07): Anthropic Extended Thinking reasoning prose persisted
     * on assistant rows, surfaced so the frontend can re-render the thinking
     * pane on history reload (mirrors the live SSE thinking event payload).
     * Null when the row carries no reasoning (legacy rows / non-thinking
     * turns / non-Anthropic providers).
     */
    private String thinkingContent;
    private String thinkingSignature;
    private Instant createdAt;
}
