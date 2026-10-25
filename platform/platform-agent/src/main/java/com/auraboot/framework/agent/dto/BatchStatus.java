package com.auraboot.framework.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Snapshot of a single Anthropic Messages Batch as returned by
 * {@code GET /v1/messages/batches/{id}}.
 *
 * <p>Maps the upstream JSON's snake_case fields to camelCase Java accessors so
 * callers see the project's standard naming. {@code @JsonIgnoreProperties} is
 * intentionally lenient — Anthropic occasionally adds new sub-fields and the
 * batch-poller path must keep working without redeploys.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchStatus {

    /** Anthropic batch identifier ({@code msgbatch_...}). */
    private String id;

    /**
     * One of {@code in_progress} / {@code canceling} / {@code ended}.
     *
     * <p>{@code ended} is the only terminal state; both fully-succeeded and
     * partial-failure batches surface as {@code ended} and the per-request
     * outcomes must be inspected via {@link #getRequestCounts()}.
     */
    @JsonProperty("processing_status")
    private String processingStatus;

    /** Per-status counts of the requests inside this batch. */
    @JsonProperty("request_counts")
    private Counts requestCounts;

    @JsonProperty("created_at")
    private Instant createdAt;

    /** Set only when {@code processing_status == "ended"}. */
    @JsonProperty("ended_at")
    private Instant endedAt;

    /**
     * Pre-signed download URL for the JSONL results stream. Anthropic populates
     * this only after the batch ends; clients should fetch the JSONL via this
     * URL (or via the dedicated {@code /results} endpoint).
     */
    @JsonProperty("results_url")
    private String resultsUrl;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Counts {
        /** Still queued / running in the batch worker. */
        private int processing;
        /** Returned a normal {@code message} block. */
        private int succeeded;
        /** Returned an {@code error} block (provider-side fault). */
        private int errored;
        /** Caller cancelled the batch before this request ran. */
        private int canceled;
        /** Batch hit the 24h SLA without completing this request. */
        private int expired;
    }
}
