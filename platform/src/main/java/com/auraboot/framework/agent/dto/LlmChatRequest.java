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

    /**
     * Optional structured system prompt — ordered list of segments where each
     * segment carries a {@code cacheable} flag. Providers that support
     * multi-segment caching (e.g. Anthropic) can mark stable prefix segments
     * (tenant-level templates) for the ephemeral cache while leaving volatile
     * suffix segments (user/session details) uncached, so a session-level
     * suffix change does not bust the prefix cache.
     *
     * <p>When non-empty this field takes precedence over {@link #systemPrompt}.
     * When null/empty, providers fall back to wrapping {@code systemPrompt} as
     * a single segment so legacy callers stay byte-identical.
     */
    private List<SystemSegment> systemSegments;

    private List<Message> messages;
    private List<Tool> tools;

    /**
     * Optional provider-agnostic tool choice hint. OpenAI-compatible providers
     * pass this through as {@code tool_choice}; Anthropic currently ignores it
     * because the unified request only needs deterministic tool forcing for
     * chat-completions models that otherwise may answer freely despite tools
     * being attached.
     *
     * <p>Supported values today: {@code "auto"} and {@code "required"}.
     * Callers should leave this null unless the current turn is a tool-mandatory
     * round. The provider only emits it when a non-empty tools array is sent.
     */
    private String toolChoice;

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
     * Single segment of a structured system prompt. Used by providers that
     * support multi-segment prompt caching to keep stable prefix (tenant /
     * agent template) cache entries alive across user/session-level changes.
     *
     * <p>{@code cacheable=true} marks the segment as eligible for the
     * ephemeral prompt cache marker; {@code false} keeps it inline in the
     * prompt without any cache_control hint. The Anthropic provider further
     * gates on a 1024-token minimum (Anthropic's documented cache floor) so
     * tiny "cacheable" segments are silently downgraded to plain text rather
     * than producing wasted cache_control markers that never hit.
     */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class SystemSegment {
        private String text;
        @Builder.Default
        private boolean cacheable = false;
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
