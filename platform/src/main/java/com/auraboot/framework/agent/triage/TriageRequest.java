package com.auraboot.framework.agent.triage;

/**
 * Input for {@link PreGroundingTriage}. Adapter (AuraBotController etc.) builds
 * this from incoming chat / webhook / event payload.
 *
 * <p>2026-07-19 (review G5): the {@code recentLightTurnCount} history-hotness
 * input was removed — it was hardwired to {@code 0} at the only call site, so
 * the rule it fed could never fire. History-derived routing should return as a
 * feature of the LLM classifier (Rule 5 slot) informed by turn-observation
 * telemetry, not as a hand-tuned counter.
 */
public record TriageRequest(
        long tenantId,
        Long userId,
        String channel,                  // "web" / "slack" / "webhook" / "im_group" / ...
        String profileId,                // null = tenant default
        String userMessage,
        boolean hasPageContext,
        boolean hasRecordContext
) {}
