package com.auraboot.framework.agent.triage;

import java.util.Set;

/**
 * Stage 2.5 decision before D1 Grounding. Determines whether the turn
 * needs the full ACP compilation chain or can short-circuit to a lighter path.
 *
 * <p>Implementations must be {@code stateless + idempotent}: same {@link TriageRequest}
 * yields same {@link TriageVerdict}. Implementations must NOT write to
 * {@code ab_agent_run / ab_agent_bif / ab_agent_action}.
 *
 * <p>Failure (timeout / error) fails closed <b>channel-sensitively</b>
 * (execution-architecture review R2 §6-3, 2026-07-19): {@link #SYSTEM_CHANNELS}
 * fall back to {@link TriageBucket#ACP_RUN} (trusted automation belongs on the
 * durable path anyway); human channels fall back to
 * {@link TriageBucket#CONTEXTUAL_ANSWER} with {@link #READONLY_CONTEXT_TOOLS}
 * — a read-only chat turn, enforced at the tool envelope — because ACP_RUN is
 * the <i>heavier, more capable</i> runtime, not a safe default for an
 * unclassifiable human message, and deployments without ACP wiring would turn
 * the fallback into a user-visible failure. Never {@link TriageBucket#LIGHT_CHAT}.
 *
 * <p>Contract: enterprise/docs/agent/contracts/pre-grounding-triage.md
 */
public interface PreGroundingTriage {

    /**
     * Channels driven by trusted automation rather than a human keyboard.
     * Rule 1 forces these to ACP_RUN; the chokepoint's triage-failure
     * fallback keeps them durable.
     */
    Set<String> SYSTEM_CHANNELS = Set.of("webhook", "bpm", "scheduled");

    /**
     * Read-only context tool grant attached to read/explain verdicts and to
     * the human-channel failure fallback. Enforced as a CAP on the tool
     * envelope (review G10): the names are aspirational, the enforcement is
     * capability-level (write tools dropped from the round catalog).
     */
    Set<String> READONLY_CONTEXT_TOOLS = Set.of("schema.lookup", "record.view");

    TriageVerdict triage(TriageRequest request);
}
