package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified LLM chat request — provider-agnostic.
 * Each LlmProvider translates this into their API format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChatRequest {

    private String model;
    private String providerCode;
    private String systemPrompt;
    private List<Message> messages;
    private List<Tool> tools;
    private int maxTokens;

    /**
     * Optional Anthropic Extended Thinking configuration. When {@code null} the
     * provider must NOT add a {@code thinking} field to the wire request — this
     * keeps existing chat callers byte-identical with the pre-P0-2 behaviour.
     *
     * <p>Capability gating happens in the provider: even when this is set with
     * {@code enabled=true}, providers that cannot handle thinking (legacy
     * Claude 3, OpenAI/DeepSeek/Qwen, etc.) silently drop it.
     */
    private ThinkingConfig thinking;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Message {
        private String role;        // "user", "assistant"
        private Object content;     // String, List<ContentBlock>, or List<MessageContentBlock>

        /**
         * Build a plain-text message. Equivalent to setting {@code content} as a
         * String — kept as the default path so legacy code stays byte-identical.
         */
        public static Message text(String role, String text) {
            return Message.builder().role(role).content(text).build();
        }

        /**
         * Build a multimodal message carrying a base64-encoded image plus an
         * optional text segment. The two blocks are emitted in the order
         * [image, text] which Anthropic recommends so the model anchors the
         * prompt to the image first. {@code text} may be null/blank — only the
         * image block is appended in that case.
         *
         * @param role      "user" — Anthropic only accepts image content on user messages
         * @param mediaType MIME type, one of image/jpeg, image/png, image/gif, image/webp
         * @param data      base64-encoded image bytes (no data: URI prefix)
         * @param text      optional accompanying prompt text
         */
        public static Message imageBase64(String role, String mediaType, String data, String text) {
            java.util.List<MessageContentBlock> blocks = new java.util.ArrayList<>(2);
            blocks.add(MessageContentBlock.builder()
                    .type("image")
                    .source(ImageSource.builder()
                            .type("base64")
                            .mediaType(mediaType)
                            .data(data)
                            .build())
                    .build());
            if (text != null && !text.isBlank()) {
                blocks.add(MessageContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build());
            }
            return Message.builder().role(role).content(blocks).build();
        }

        /**
         * Build a multimodal message referencing a remote image URL plus an
         * optional text segment. URL-source images skip base64 round-trip — the
         * model fetches them directly.
         */
        public static Message imageUrl(String role, String url, String text) {
            java.util.List<MessageContentBlock> blocks = new java.util.ArrayList<>(2);
            blocks.add(MessageContentBlock.builder()
                    .type("image")
                    .source(ImageSource.builder()
                            .type("url")
                            .url(url)
                            .build())
                    .build());
            if (text != null && !text.isBlank()) {
                blocks.add(MessageContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build());
            }
            return Message.builder().role(role).content(blocks).build();
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ContentBlock {
        private String type;        // "text", "tool_use", "tool_result"
        private String text;
        private String id;          // tool_use id
        private String name;        // tool name
        private Object input;       // tool input
        private String toolUseId;   // for tool_result
        private Object result;      // tool result content
    }

    /**
     * Multimodal content block carried inside a {@link Message#getContent()} list.
     * Distinct from {@link ContentBlock} (which models tool_use / tool_result for
     * assistant turns) — this block is for INBOUND user content where the user
     * attaches text + image segments. Anthropic's wire-format counterpart lives
     * in {@code AnthropicRequest.ImageContentBlock}.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class MessageContentBlock {
        private String type;        // "text" | "image"
        private String text;        // populated when type=text
        private ImageSource source; // populated when type=image
    }

    /**
     * Image source descriptor. Either base64-encoded inline (no data: prefix —
     * just raw base64 + mediaType) or a URL the model fetches itself.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ImageSource {
        private String type;       // "base64" | "url"
        private String mediaType;  // image/jpeg, image/png, image/gif, image/webp (when type=base64)
        private String data;       // raw base64 string (when type=base64)
        private String url;        // remote URL (when type=url)
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Tool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema;
        private Map<String, Object> nativeToolConfig; // For LLM_NATIVE tools — passed directly to provider
    }

    /**
     * Anthropic Extended Thinking knob. Only honoured by Claude Sonnet 4.6+,
     * Opus 4.x, and Haiku 4.x — see {@code AnthropicLlmProvider#supportsThinking}.
     *
     * <p>{@code budgetTokens} is the maximum tokens the model may spend in its
     * private thinking block. Anthropic requires
     * {@code request.max_tokens > thinking.budget_tokens}; if not, the provider
     * auto-extends max_tokens to {@code budget + 4096} to avoid HTTP 400.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ThinkingConfig {
        private boolean enabled;
        @Builder.Default
        private int budgetTokens = 10_000;
    }
}
