package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregated shadow-run KPIs grouped by Skill Draft.
 *
 * <p>One row per draft that has at least one shadow run recorded. Surfaces the
 * three Phase-1 columns the operator needs at a glance:
 * {@code fidelityMatchRate}, {@code outputMatchRate}, {@code costDelta}, plus
 * supporting context (run count / draft status / latest activity).
 *
 * <p>Computation rules (all from {@code ab_agent_shadow_run}):
 * <ul>
 *   <li>{@code fidelityMatchRate} = AVG(fidelity_match::int) over rows with
 *       fidelity_match NOT NULL — fraction of runs whose draft action graph
 *       reconstructs the production action graph.</li>
 *   <li>{@code outputMatchRate}   = AVG(output_match::int) over rows with
 *       output_match NOT NULL — fraction of runs whose final output matches
 *       production.</li>
 *   <li>{@code costDelta}         = SUM(shadow_cost_usd) - SUM(original_cost_usd)
 *       across all rows; positive means draft is more expensive than prod.</li>
 *   <li>{@code latestAt}          = MAX(created_at) — drives default sort
 *       order so freshly-active drafts surface first.</li>
 * </ul>
 *
 * <p>Rates are reported in [0, 1]; null when the denominator is zero (no
 * shadow runs with non-null match flag yet).
 */
@Data
@Builder
public class ShadowRunAggregation {

    private String draftId;
    private String draftSkillCode;
    private String draftStatus;
    private long   runCount;
    /** Subset of {@code runCount} where {@code fidelity_match IS NOT NULL}. */
    private long   fidelitySamples;
    /** Subset of {@code runCount} where {@code output_match IS NOT NULL}. */
    private long   outputSamples;
    /** [0, 1] — null when fidelitySamples == 0. */
    private Double fidelityMatchRate;
    /** [0, 1] — null when outputSamples == 0. */
    private Double outputMatchRate;
    /** {@code shadowCost - originalCost} (USD). Null when both sides null. */
    private BigDecimal costDelta;
    private Instant latestAt;
}
