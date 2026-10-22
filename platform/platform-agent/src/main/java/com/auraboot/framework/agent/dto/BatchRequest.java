package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One entry in an Anthropic Messages Batch submission.
 *
 * <p>Wire format (per <a href="https://docs.anthropic.com/en/api/creating-message-batches">
 * Anthropic batch API docs</a>): each request in the {@code requests[]} array carries
 * <ul>
 *   <li>{@code custom_id} — caller-supplied identifier (≤64 chars, unique per
 *       batch) used to reconcile results back to the originating workload row.</li>
 *   <li>{@code params} — a regular {@link AnthropicRequest} body (model,
 *       max_tokens, system, messages, tools, thinking, ...).</li>
 * </ul>
 *
 * <p>P0-4 wraps both fields with a thin DTO so callers do not have to remember
 * the snake_case JSON shape on the request side. Submission code will serialise
 * a {@code List<BatchRequest>} into the {@code {"requests":[...]}} envelope.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchRequest {

    /**
     * Caller-supplied opaque identifier — propagated verbatim into the result
     * stream so the caller can map a {@link BatchResult} back to the source
     * row (e.g. {@code memoryPid} for memory promotion scoring).
     *
     * <p>Anthropic enforces uniqueness within a batch and a max length of
     * 64 chars; callers should use the row PID or a deterministic hash.
     */
    private String customId;

    /**
     * The body that would otherwise be POSTed to {@code /v1/messages} for a
     * synchronous call. Reused verbatim — no batch-only fields exist.
     */
    private AnthropicRequest params;
}
