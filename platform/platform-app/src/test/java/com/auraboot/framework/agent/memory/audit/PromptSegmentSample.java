package com.auraboot.framework.agent.memory.audit;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One sampled (tenant, agent, user) triple's prompt snippet bundle. Test-only
 * DTO produced by Spike-2 phase 2 audit run; written to
 * {@code prompt-segments-<ts>.json} for human annotation.
 *
 * <p>Each {@code snippet} mirrors the map shape emitted by
 * {@code ActiveMemoryService.snippet(Map)} — keys: pid / memory_type /
 * memory_title / memory_content / importance / scope. Reviewer reads the
 * bundle and tags it with a {@link ConflictTag}.
 */
public record PromptSegmentSample(
        @JsonProperty("sample_id") String sampleId,
        @JsonProperty("tenant_id") long tenantId,
        @JsonProperty("agent_code") String agentCode,
        @JsonProperty("user_id") String userId,
        @JsonProperty("memory_count_total") int memoryCountTotal,
        @JsonProperty("snippet_bundle") List<Map<String, Object>> snippetBundle
) {

    public PromptSegmentSample {
        snippetBundle = snippetBundle == null ? List.of() : List.copyOf(snippetBundle);
    }
}
