package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shadow-run drilldown row, projected from {@code ab_agent_shadow_run}.
 *
 * <p>One per shadow execution comparing a Skill Draft against its production
 * counterpart. Carries enough context for the drawer to render side-by-side
 * cost / duration / output match without an extra round-trip.
 *
 * <p>{@code outputDiff} is the raw JSONB serialised to text — the UI is
 * responsible for pretty-printing.
 */
@Data
@Builder
public class ShadowRunListItem {

    private String pid;
    private String draftId;
    private String originalRunId;

    private String shadowStatus;
    private Long shadowDurationMs;
    private BigDecimal shadowCostUsd;
    private Integer shadowTokens;
    private String shadowOutputHash;

    private String originalStatus;
    private Long originalDurationMs;
    private BigDecimal originalCostUsd;
    private String originalOutputHash;

    private Boolean outputMatch;
    private Boolean fidelityMatch;
    private String outputDiff;

    private Instant createdAt;
}
