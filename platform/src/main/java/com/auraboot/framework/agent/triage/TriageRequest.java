package com.auraboot.framework.agent.triage;

/**
 * Input for {@link PreGroundingTriage}. Adapter (AuraBotController etc.) builds
 * this from incoming chat / webhook / event payload.
 */
public record TriageRequest(
        long tenantId,
        Long userId,
        String channel,                  // "web" / "slack" / "webhook" / "im_group" / ...
        String profileId,                // null = tenant default
        String userMessage,
        boolean hasPageContext,
        boolean hasRecordContext,
        int recentLightTurnCount         // last-N turns marked light_chat; helps history hotness rule
) {}
