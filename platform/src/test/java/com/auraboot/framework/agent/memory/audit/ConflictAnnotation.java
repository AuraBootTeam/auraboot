package com.auraboot.framework.agent.memory.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One annotation entry produced by a human reviewer for Spike-2. Matches
 * {@code platform/src/test/resources/memory-audit/annotation.schema.json}.
 *
 * <p>Test-only DTO; not for production wiring.
 */
public record ConflictAnnotation(
        @JsonProperty("sample_id") String sampleId,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("agent_code") String agentCode,
        @JsonProperty("snippet_count") int snippetCount,
        String tag,
        @JsonProperty("conflicting_pids") List<String> conflictingPids,
        String rationale,
        @JsonProperty("second_reviewer_tag") String secondReviewerTag
) {

    public ConflictAnnotation {
        conflictingPids = conflictingPids == null ? List.of() : List.copyOf(conflictingPids);
        rationale = rationale == null ? "" : rationale;
        secondReviewerTag = secondReviewerTag == null ? "" : secondReviewerTag;
    }

    /**
     * Effective tag — if {@code unclear} got a second reviewer adjudication, use that.
     */
    public ConflictTag effectiveTag() {
        if ("unclear".equals(tag) && !secondReviewerTag.isBlank()) {
            return ConflictTag.fromWire(secondReviewerTag);
        }
        return ConflictTag.fromWire(tag);
    }
}
