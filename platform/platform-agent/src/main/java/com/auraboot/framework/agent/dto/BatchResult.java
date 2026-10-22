package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One line of the JSONL stream returned by
 * {@code GET /v1/messages/batches/{id}/results}.
 *
 * <p>Anthropic wraps each result as
 * {@code { "custom_id": "...", "result": { "type": "succeeded", "message": {...} } }}.
 * We flatten {@code result.type} to {@link #type} and {@code result.message} to
 * {@link #message} via a small custom-deserialiser-free shape: the consumer
 * sees {@code customId} + {@code type} + {@code message} (or {@code error})
 * directly without nested-result-knowledge leaking out.
 *
 * <p>{@link #type} is one of {@code succeeded} / {@code errored} /
 * {@code canceled} / {@code expired}. Successful results carry an
 * {@link AnthropicResponse}; failed results carry an {@link Map} with the
 * upstream error envelope (we keep it as a Map to avoid coupling tests to
 * Anthropic's evolving error shape).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchResult {

    /** Caller-supplied identifier from the original {@code BatchRequest.customId}. */
    @JsonProperty("custom_id")
    private String customId;

    /**
     * Result envelope — exposes {@link Envelope#type}, {@link Envelope#message},
     * {@link Envelope#error} so callers can branch on outcome without touching
     * the {@code result.*} JSON path.
     */
    private Envelope result;

    /** Convenience: the {@code result.type} string. {@code null} when result is null. */
    public String getType() {
        return result == null ? null : result.getType();
    }

    /** Convenience: the {@code result.message} on success; {@code null} otherwise. */
    public AnthropicResponse getMessage() {
        return result == null ? null : result.getMessage();
    }

    /** Convenience: the {@code result.error} envelope on failure; {@code null} on success. */
    public Map<String, Object> getError() {
        return result == null ? null : result.getError();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Envelope {
        /** {@code succeeded} / {@code errored} / {@code canceled} / {@code expired}. */
        private String type;
        /** Populated when {@link #type} is {@code succeeded}. */
        private AnthropicResponse message;
        /**
         * Populated when {@link #type} is {@code errored}. Anthropic's error
         * envelope is small but evolves over time, so we keep it as a generic
         * map rather than cementing a DTO shape that may drift.
         */
        private Map<String, Object> error;
    }
}
