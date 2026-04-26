package com.auraboot.framework.agent.triage;

/**
 * Stage 2.5 decision before D1 Grounding. Determines whether the turn
 * needs the full ACP compilation chain or can short-circuit to a lighter path.
 *
 * <p>Implementations must be {@code stateless + idempotent}: same {@link TriageRequest}
 * yields same {@link TriageVerdict}. Implementations must NOT write to
 * {@code ab_agent_run / ab_agent_bif / ab_agent_action}.
 *
 * <p>Failure (timeout / error) must fall back to {@link TriageBucket#ACP_RUN}, never
 * {@link TriageBucket#LIGHT_CHAT} (fail-closed for safety).
 *
 * <p>Contract: enterprise/docs/agent/contracts/pre-grounding-triage.md
 */
public interface PreGroundingTriage {

    TriageVerdict triage(TriageRequest request);
}
